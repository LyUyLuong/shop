package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.PaymentService;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderLifecycleRaceIntegrationTest
        extends PostgresIntegrationTest {

    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 2;
    private static final BigDecimal UNIT_PRICE =
            new BigDecimal("100000.00");
    private static final BigDecimal ORDER_TOTAL =
            new BigDecimal("200000.00");
    private static final Instant DEADLINE =
            Instant.parse("2026-07-18T10:00:00Z");
    private static final Instant PAYMENT_TIME =
            DEADLINE.minusMillis(1);
    private static final Instant EXPIRY_TIME = DEADLINE;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderLifecycleService lifecycleService;

    @Autowired
    private OrderExpiryBatchProcessor expiryBatchProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    private OrderRepository repositorySpy;

    @MockitoBean
    private Clock clock;

    private final ThreadLocal<Instant> operationTime =
            new ThreadLocal<>();

    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach
    void configureClock() {
        repositorySpy = AopTestUtils.getUltimateTargetObject(
                orderRepository
        );

        when(clock.instant()).thenAnswer(invocation -> {
            Instant configuredTime = operationTime.get();
            return configuredTime == null
                    ? Instant.now()
                    : configuredTime;
        });
    }

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "delete from outbox_events where aggregate_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from payments where order_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from order_status_history where order_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from order_items where order_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from orders where id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from products where id = ?",
                    fixture.productId()
            );
            jdbcTemplate.update(
                    "delete from users where id in (?, ?)",
                    fixture.ownerId(),
                    fixture.adminId()
            );
        }
    }

    @Test
    void shouldLoadLockedAggregateWithExactlyTwoSelects() {
        Fixture fixture = seedPendingOrder();
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        boolean statisticsPreviouslyEnabled =
                statistics.isStatisticsEnabled();

        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();

            TransactionTemplate transaction =
                    new TransactionTemplate(transactionManager);

            Integer adminItemCount = transaction.execute(status ->
                    orderRepository
                            .findByIdForUpdate(fixture.orderId())
                            .orElseThrow()
                            .getItems()
                            .size()
            );
            long adminStatementCount =
                    statistics.getPrepareStatementCount();

            statistics.clear();

            Integer ownerItemCount = transaction.execute(status ->
                    orderRepository
                            .findByIdAndUserIdForUpdate(
                                    fixture.orderId(),
                                    fixture.ownerId()
                            )
                            .orElseThrow()
                            .getItems()
                            .size()
            );
            long ownerStatementCount =
                    statistics.getPrepareStatementCount();

            assertThat(Objects.requireNonNull(adminItemCount))
                    .isEqualTo(1);
            assertThat(adminStatementCount)
                    .isEqualTo(2L);
            assertThat(Objects.requireNonNull(ownerItemCount))
                    .isEqualTo(1);
            assertThat(ownerStatementCount)
                    .isEqualTo(2L);
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(
                    statisticsPreviouslyEnabled
            );
        }
    }

    @Test
    void shouldKeepPaidWhenPaymentLocksBeforeExpiry()
            throws Exception {
        Fixture fixture = seedPendingOrder();
        CountDownLatch paymentLockAcquired = new CountDownLatch(1);
        CountDownLatch releasePayment = new CountDownLatch(1);

        doAnswer(invocation -> {
            Object lockedOrder = invocation.callRealMethod();
            paymentLockAcquired.countDown();
            await(releasePayment, "release payment lock");
            return lockedOrder;
        }).when(repositorySpy).findByIdAndUserIdForUpdate(
                fixture.orderId(),
                fixture.ownerId()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<PaymentResult> paymentFuture = executor.submit(
                () -> atTime(
                        PAYMENT_TIME,
                        () -> paymentService.payMock(
                                new PayOrderCommand(
                                        fixture.ownerId(),
                                        fixture.orderId()
                                )
                        )
                )
        );

        try {
            await(paymentLockAcquired, "payment row lock");

            Future<Integer> expiryFuture = executor.submit(
                    () -> atTime(
                            EXPIRY_TIME,
                            () -> expiryBatchProcessor
                                    .expireNextBatch(1)
                    )
            );

            assertThat(expiryFuture.get(20, TimeUnit.SECONDS))
                    .isZero();

            releasePayment.countDown();

            assertThat(paymentFuture.get(20, TimeUnit.SECONDS).status())
                    .isEqualTo(PaymentStatus.SUCCEEDED);

            assertPaidOutcome(fixture);
            verify(repositorySpy, times(1))
                    .findByIdAndUserIdForUpdate(
                            fixture.orderId(),
                            fixture.ownerId()
                    );
            verify(repositorySpy, times(1))
                    .claimExpiredForUpdate(EXPIRY_TIME, 1);
            verify(repositorySpy, never())
                    .findByIdForUpdate(fixture.orderId());
        } finally {
            releasePayment.countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldKeepExpiredWhenExpiryLocksBeforePayment()
            throws Exception {
        Fixture fixture = seedPendingOrder();
        CountDownLatch expiryClaimAcquired = new CountDownLatch(1);
        CountDownLatch releaseExpiry = new CountDownLatch(1);
        CountDownLatch paymentLockAttempted = new CountDownLatch(1);

        doAnswer(invocation -> {
            Object claimedOrders = invocation.callRealMethod();
            expiryClaimAcquired.countDown();
            await(releaseExpiry, "release expiry claim");
            return claimedOrders;
        }).when(repositorySpy).claimExpiredForUpdate(
                EXPIRY_TIME,
                1
        );

        doAnswer(invocation -> {
            paymentLockAttempted.countDown();
            return invocation.callRealMethod();
        }).when(repositorySpy).findByIdAndUserIdForUpdate(
                fixture.orderId(),
                fixture.ownerId()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> expiryFuture = executor.submit(
                () -> atTime(
                        EXPIRY_TIME,
                        () -> expiryBatchProcessor.expireNextBatch(1)
                )
        );

        try {
            await(expiryClaimAcquired, "expiry row lock");

            Future<PaymentResult> paymentFuture = executor.submit(
                    () -> atTime(
                            PAYMENT_TIME,
                            () -> paymentService.payMock(
                                    new PayOrderCommand(
                                            fixture.ownerId(),
                                            fixture.orderId()
                                    )
                            )
                    )
            );

            await(paymentLockAttempted, "payment lock attempt");
            releaseExpiry.countDown();

            assertThat(expiryFuture.get(20, TimeUnit.SECONDS))
                    .isEqualTo(1);

            BusinessException failure =
                    awaitBusinessFailure(paymentFuture);

            assertThat(failure.getErrorCode())
                    .isEqualTo(PaymentErrorCode.ORDER_NOT_PAYABLE);

            assertExpiredOutcome(fixture);
            verify(repositorySpy, times(1))
                    .claimExpiredForUpdate(EXPIRY_TIME, 1);
            verify(repositorySpy, times(1))
                    .findByIdAndUserIdForUpdate(
                            fixture.orderId(),
                            fixture.ownerId()
                    );
            verify(repositorySpy, never())
                    .findByIdForUpdate(fixture.orderId());
        } finally {
            releaseExpiry.countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldKeepPaidWhenPaymentLocksBeforeAdminCancellation()
            throws Exception {
        Fixture fixture = seedPendingOrder();
        CountDownLatch paymentLockAcquired = new CountDownLatch(1);
        CountDownLatch releasePayment = new CountDownLatch(1);
        CountDownLatch adminLockAttempted = new CountDownLatch(1);

        doAnswer(invocation -> {
            Object lockedOrder = invocation.callRealMethod();
            paymentLockAcquired.countDown();
            await(releasePayment, "release payment lock");
            return lockedOrder;
        }).when(repositorySpy).findByIdAndUserIdForUpdate(
                fixture.orderId(),
                fixture.ownerId()
        );

        doAnswer(invocation -> {
            adminLockAttempted.countDown();
            return invocation.callRealMethod();
        }).when(repositorySpy).findByIdForUpdate(
                fixture.orderId()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<PaymentResult> paymentFuture = executor.submit(
                () -> atTime(
                        PAYMENT_TIME,
                        () -> paymentService.payMock(
                                new PayOrderCommand(
                                        fixture.ownerId(),
                                        fixture.orderId()
                                )
                        )
                )
        );

        try {
            await(paymentLockAcquired, "payment row lock");

            Future<?> adminFuture = executor.submit(
                    () -> atTime(
                            PAYMENT_TIME,
                            () -> lifecycleService.changeStatusAsAdmin(
                                    cancellationCommand(fixture)
                            )
                    )
            );

            await(adminLockAttempted, "admin lock attempt");
            releasePayment.countDown();

            assertThat(paymentFuture.get(20, TimeUnit.SECONDS).status())
                    .isEqualTo(PaymentStatus.SUCCEEDED);

            BusinessException failure =
                    awaitBusinessFailure(adminFuture);

            assertThat(failure.getErrorCode()).isEqualTo(
                    OrderingErrorCode.INVALID_ORDER_STATUS_TRANSITION
            );

            assertPaidOutcome(fixture);
            verify(repositorySpy, times(1))
                    .findByIdAndUserIdForUpdate(
                            fixture.orderId(),
                            fixture.ownerId()
                    );
            verify(repositorySpy, times(1))
                    .findByIdForUpdate(fixture.orderId());
        } finally {
            releasePayment.countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldKeepCancelledWhenAdminLocksBeforePayment()
            throws Exception {
        Fixture fixture = seedPendingOrder();
        CountDownLatch adminLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseAdmin = new CountDownLatch(1);
        CountDownLatch paymentLockAttempted = new CountDownLatch(1);

        doAnswer(invocation -> {
            Object lockedOrder = invocation.callRealMethod();
            adminLockAcquired.countDown();
            await(releaseAdmin, "release admin lock");
            return lockedOrder;
        }).when(repositorySpy).findByIdForUpdate(
                fixture.orderId()
        );

        doAnswer(invocation -> {
            paymentLockAttempted.countDown();
            return invocation.callRealMethod();
        }).when(repositorySpy).findByIdAndUserIdForUpdate(
                fixture.orderId(),
                fixture.ownerId()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> adminFuture = executor.submit(
                () -> atTime(
                        PAYMENT_TIME,
                        () -> lifecycleService.changeStatusAsAdmin(
                                cancellationCommand(fixture)
                        )
                )
        );

        try {
            await(adminLockAcquired, "admin row lock");

            Future<PaymentResult> paymentFuture = executor.submit(
                    () -> atTime(
                            PAYMENT_TIME,
                            () -> paymentService.payMock(
                                    new PayOrderCommand(
                                            fixture.ownerId(),
                                            fixture.orderId()
                                    )
                            )
                    )
            );

            await(paymentLockAttempted, "payment lock attempt");
            releaseAdmin.countDown();

            adminFuture.get(20, TimeUnit.SECONDS);

            BusinessException failure =
                    awaitBusinessFailure(paymentFuture);

            assertThat(failure.getErrorCode())
                    .isEqualTo(PaymentErrorCode.ORDER_NOT_PAYABLE);

            assertCancelledOutcome(fixture);
            verify(repositorySpy, times(1))
                    .findByIdForUpdate(fixture.orderId());
            verify(repositorySpy, times(1))
                    .findByIdAndUserIdForUpdate(
                            fixture.orderId(),
                            fixture.ownerId()
                    );
        } finally {
            releaseAdmin.countDown();
            shutdown(executor);
        }
    }

    private ChangeOrderStatusCommand cancellationCommand(
            Fixture fixture
    ) {
        return new ChangeOrderStatusCommand(
                fixture.orderId(),
                fixture.adminId(),
                OrderStatus.CANCELLED,
                "Customer requested cancellation"
        );
    }

    private void assertPaidOutcome(Fixture fixture) {
        PersistedOrder order = loadOrder(fixture.orderId());

        assertThat(order.status()).isEqualTo(OrderStatus.PAID.name());
        assertThat(order.inventoryReleasedAt()).isNull();
        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK);
        assertThat(paymentCount(fixture.orderId())).isEqualTo(1);
        assertThat(outboxCount(fixture.orderId())).isEqualTo(1);
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.PAYMENT,
                OrderStatus.PAID
        )).isEqualTo(1);
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.SYSTEM,
                OrderStatus.EXPIRED
        )).isZero();
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.ADMIN,
                OrderStatus.CANCELLED
        )).isZero();
    }

    private void assertExpiredOutcome(Fixture fixture) {
        PersistedOrder order = loadOrder(fixture.orderId());

        assertThat(order.status()).isEqualTo(OrderStatus.EXPIRED.name());
        assertThat(order.inventoryReleasedAt()).isNotNull();
        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK + ORDER_QUANTITY);
        assertThat(paymentCount(fixture.orderId())).isZero();
        assertThat(outboxCount(fixture.orderId())).isZero();
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.SYSTEM,
                OrderStatus.EXPIRED
        )).isEqualTo(1);
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.PAYMENT,
                OrderStatus.PAID
        )).isZero();
    }

    private void assertCancelledOutcome(Fixture fixture) {
        PersistedOrder order = loadOrder(fixture.orderId());

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(order.inventoryReleasedAt()).isNotNull();
        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK + ORDER_QUANTITY);
        assertThat(paymentCount(fixture.orderId())).isZero();
        assertThat(outboxCount(fixture.orderId())).isZero();
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.ADMIN,
                OrderStatus.CANCELLED
        )).isEqualTo(1);
        assertThat(historyCount(
                fixture.orderId(),
                OrderStatusChangeActorType.PAYMENT,
                OrderStatus.PAID
        )).isZero();
    }

    private Fixture seedPendingOrder() {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        fixtures.add(fixture);

        insertUser(fixture.ownerId(), "race-owner-");
        insertUser(fixture.adminId(), "race-admin-");
        insertProduct(fixture.productId());
        insertOrder(fixture);
        insertOrderItem(fixture);

        return fixture;
    }

    private void insertUser(
            UUID userId,
            String emailPrefix
    ) {
        jdbcTemplate.update(
                """
                insert into users (
                    id, email, name, password_hash,
                    enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                emailPrefix + userId + "@example.com",
                "Lifecycle Race User",
                "password-hash"
        );
    }

    private void insertProduct(UUID productId) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, sku, name, description, price,
                    stock_quantity, status,
                    image_key, image_url,
                    created_at, updated_at
                )
                values (
                    ?, ?, ?, ?, ?,
                    ?, 'ACTIVE',
                    null, null,
                    now(), now()
                )
                """,
                productId,
                "RACE-" + productId,
                "Lifecycle Race Product",
                "Product used by lifecycle race tests",
                UNIT_PRICE,
                INITIAL_STOCK
        );
    }

    private void insertOrder(Fixture fixture) {
        Instant createdAt = DEADLINE.minusSeconds(1800);

        jdbcTemplate.update(
                """
                insert into orders (
                    id, user_id, status, total_amount,
                    expires_at, inventory_released_at,
                    created_at, updated_at
                )
                values (
                    ?, ?, 'PENDING_PAYMENT', ?,
                    ?, null,
                    ?, ?
                )
                """,
                fixture.orderId(),
                fixture.ownerId(),
                ORDER_TOTAL,
                Timestamp.from(DEADLINE),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private void insertOrderItem(Fixture fixture) {
        Instant createdAt = DEADLINE.minusSeconds(1800);

        jdbcTemplate.update(
                """
                insert into order_items (
                    id, order_id, product_id,
                    product_sku, product_name,
                    unit_price, quantity, line_total,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                fixture.orderId(),
                fixture.productId(),
                "RACE-SNAPSHOT-" + fixture.productId(),
                "Lifecycle Race Snapshot",
                UNIT_PRICE,
                ORDER_QUANTITY,
                ORDER_TOTAL,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private PersistedOrder loadOrder(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                select status, inventory_released_at
                from orders
                where id = ?
                """,
                (resultSet, rowNumber) -> {
                    Timestamp releasedAt = resultSet.getTimestamp(
                            "inventory_released_at"
                    );

                    return new PersistedOrder(
                            resultSet.getString("status"),
                            releasedAt == null
                                    ? null
                                    : releasedAt.toInstant()
                    );
                },
                orderId
        );
    }

    private int loadStock(UUID productId) {
        return jdbcTemplate.queryForObject(
                "select stock_quantity from products where id = ?",
                Integer.class,
                productId
        );
    }

    private int paymentCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from payments where order_id = ?",
                Integer.class,
                orderId
        );
    }

    private int outboxCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?",
                Integer.class,
                orderId
        );
    }

    private int historyCount(
            UUID orderId,
            OrderStatusChangeActorType actorType,
            OrderStatus toStatus
    ) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                from order_status_history
                where order_id = ?
                  and from_status = 'PENDING_PAYMENT'
                  and to_status = ?
                  and actor_type = ?
                """,
                Integer.class,
                orderId,
                toStatus.name(),
                actorType.name()
        );
    }

    private BusinessException awaitBusinessFailure(
            Future<?> future
    ) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            assertThat(exception.getCause())
                    .isInstanceOf(BusinessException.class);

            return (BusinessException) exception.getCause();
        }

        throw new AssertionError(
                "expected concurrent operation to fail"
        );
    }

    private <T> T atTime(
            Instant instant,
            Callable<T> action
    ) throws Exception {
        operationTime.set(instant);

        try {
            return action.call();
        } finally {
            operationTime.remove();
        }
    }

    private void await(
            CountDownLatch latch,
            String description
    ) {
        try {
            boolean completed = latch.await(
                    20,
                    TimeUnit.SECONDS
            );

            if (!completed) {
                throw new IllegalStateException(
                        "timed out waiting for " + description
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for " + description,
                    exception
            );
        }
    }

    private void shutdown(ExecutorService executor) {
        executor.shutdownNow();

        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record Fixture(
            UUID ownerId,
            UUID adminId,
            UUID productId,
            UUID orderId
    ) {
    }

    private record PersistedOrder(
            String status,
            Instant inventoryReleasedAt
    ) {
    }
}
