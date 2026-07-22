package com.lul.shop.payment.application;

import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PaymentIdempotencyRaceIntegrationTest
        extends PostgresIntegrationTest {

    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 2;

    private static final BigDecimal UNIT_PRICE =
            new BigDecimal("100000.00");

    private static final BigDecimal ORDER_TOTAL =
            new BigDecimal("200000.00");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private PaymentIdempotencyRepository idempotencyRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    private PaymentIdempotencyRepository idempotencyRepositorySpy;
    private OrderRepository orderRepositorySpy;
    private PaymentRepository paymentRepositorySpy;

    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach
    void unwrapRepositorySpies() {
        idempotencyRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        idempotencyRepository
                );

        orderRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        orderRepository
                );

        paymentRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        paymentRepository
                );
    }

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    """
                    delete from outbox_events
                    where aggregate_id in (?, ?)
                    """,
                    fixture.firstOrderId(),
                    fixture.secondOrderId()
            );

            jdbcTemplate.update(
                    """
                    delete from payment_idempotency_records
                    where user_id = ?
                    """,
                    fixture.userId()
            );

            jdbcTemplate.update(
                    """
                    delete from payments
                    where order_id in (?, ?)
                    """,
                    fixture.firstOrderId(),
                    fixture.secondOrderId()
            );

            jdbcTemplate.update(
                    """
                    delete from order_status_history
                    where order_id in (?, ?)
                    """,
                    fixture.firstOrderId(),
                    fixture.secondOrderId()
            );

            jdbcTemplate.update(
                    """
                    delete from order_items
                    where order_id in (?, ?)
                    """,
                    fixture.firstOrderId(),
                    fixture.secondOrderId()
            );

            jdbcTemplate.update(
                    "delete from orders where id in (?, ?)",
                    fixture.firstOrderId(),
                    fixture.secondOrderId()
            );

            jdbcTemplate.update(
                    "delete from products where id = ?",
                    fixture.productId()
            );

            jdbcTemplate.update(
                    "delete from users where id = ?",
                    fixture.userId()
            );
        }
    }

    @Test
    void shouldReturnOriginalPaymentForConcurrentMatchingKey()
            throws Exception {
        Fixture fixture = seedFixture();
        String key = newKey("same-payment");
        RaceGate gate = gateFirstIdempotencyClaim(key);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<PaymentResult> owner = executor.submit(() ->
                paymentService.payMock(command(
                        fixture.userId(),
                        fixture.firstOrderId(),
                        key
                ))
        );

        try {
            await(
                    gate.firstAcquired(),
                    "first payment claim"
            );

            Future<PaymentResult> follower =
                    executor.submit(() ->
                            paymentService.payMock(command(
                                    fixture.userId(),
                                    fixture.firstOrderId(),
                                    key
                            ))
                    );

            await(
                    gate.secondAttempted(),
                    "matching claim attempt"
            );

            gate.releaseFirst().countDown();

            PaymentResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);

            PaymentResult followerResult =
                    follower.get(20, TimeUnit.SECONDS);

            assertThat(followerResult.id())
                    .isEqualTo(ownerResult.id());

            assertPaidOutcome(
                    fixture,
                    fixture.firstOrderId(),
                    ownerResult.id()
            );

            assertPendingOutcome(
                    fixture.secondOrderId()
            );

            assertThat(idempotencyCount(fixture.userId()))
                    .isEqualTo(1);

            assertThat(loadIdempotency(
                    fixture.userId(),
                    key
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            verify(orderRepositorySpy, times(1))
                    .findByIdAndUserIdForUpdate(
                            fixture.firstOrderId(),
                            fixture.userId()
                    );

            verify(paymentRepositorySpy, times(1))
                    .save(any(Payment.class));

            verify(paymentRepositorySpy, times(1))
                    .findById(ownerResult.id());

            verify(paymentRepositorySpy, never())
                    .findByOrderId(any(UUID.class));
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldRejectConcurrentKeyReuseForAnotherOrder()
            throws Exception {
        Fixture fixture = seedFixture();
        String key = newKey("reused-payment");
        RaceGate gate = gateFirstIdempotencyClaim(key);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<PaymentResult> owner = executor.submit(() ->
                paymentService.payMock(command(
                        fixture.userId(),
                        fixture.firstOrderId(),
                        key
                ))
        );

        try {
            await(
                    gate.firstAcquired(),
                    "first payment claim"
            );

            Future<PaymentResult> follower =
                    executor.submit(() ->
                            paymentService.payMock(command(
                                    fixture.userId(),
                                    fixture.secondOrderId(),
                                    key
                            ))
                    );

            await(
                    gate.secondAttempted(),
                    "conflicting claim attempt"
            );

            gate.releaseFirst().countDown();

            PaymentResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);

            BusinessException failure =
                    awaitBusinessFailure(follower);

            assertThat(failure.getErrorCode())
                    .isEqualTo(
                            PaymentErrorCode.IDEMPOTENCY_KEY_REUSED
                    );

            assertPaidOutcome(
                    fixture,
                    fixture.firstOrderId(),
                    ownerResult.id()
            );

            assertPendingOutcome(
                    fixture.secondOrderId()
            );

            assertThat(idempotencyCount(fixture.userId()))
                    .isEqualTo(1);

            assertThat(loadIdempotency(
                    fixture.userId(),
                    key
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            verify(orderRepositorySpy, times(1))
                    .findByIdAndUserIdForUpdate(
                            fixture.firstOrderId(),
                            fixture.userId()
                    );

            verify(orderRepositorySpy, never())
                    .findByIdAndUserIdForUpdate(
                            fixture.secondOrderId(),
                            fixture.userId()
                    );

            verify(paymentRepositorySpy, times(1))
                    .save(any(Payment.class));

            verify(paymentRepositorySpy, never())
                    .findById(any(UUID.class));

            verify(paymentRepositorySpy, never())
                    .findByOrderId(any(UUID.class));
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldConvergeDifferentKeysOnOnePayment()
            throws Exception {
        Fixture fixture = seedFixture();

        String ownerKey =
                newKey("payment-owner");

        String followerKey =
                newKey("payment-follower");

        RaceGate gate = gateFirstOrderLock(
                fixture.firstOrderId(),
                fixture.userId()
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<PaymentResult> owner = executor.submit(() ->
                paymentService.payMock(command(
                        fixture.userId(),
                        fixture.firstOrderId(),
                        ownerKey
                ))
        );

        try {
            await(
                    gate.firstAcquired(),
                    "first payment order lock"
            );

            Future<PaymentResult> follower =
                    executor.submit(() ->
                            paymentService.payMock(command(
                                    fixture.userId(),
                                    fixture.firstOrderId(),
                                    followerKey
                            ))
                    );

            await(
                    gate.secondAttempted(),
                    "second order lock attempt"
            );

            gate.releaseFirst().countDown();

            PaymentResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);

            PaymentResult followerResult =
                    follower.get(20, TimeUnit.SECONDS);

            assertThat(followerResult.id())
                    .isEqualTo(ownerResult.id());

            assertPaidOutcome(
                    fixture,
                    fixture.firstOrderId(),
                    ownerResult.id()
            );

            assertPendingOutcome(
                    fixture.secondOrderId()
            );

            assertThat(idempotencyCount(fixture.userId()))
                    .isEqualTo(2);

            assertThat(loadIdempotency(
                    fixture.userId(),
                    ownerKey
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            assertThat(loadIdempotency(
                    fixture.userId(),
                    followerKey
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            verify(orderRepositorySpy, times(2))
                    .findByIdAndUserIdForUpdate(
                            fixture.firstOrderId(),
                            fixture.userId()
                    );

            verify(paymentRepositorySpy, times(1))
                    .save(any(Payment.class));

            verify(paymentRepositorySpy, times(1))
                    .findByOrderId(
                            fixture.firstOrderId()
                    );

            verify(paymentRepositorySpy, never())
                    .findById(any(UUID.class));
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    private RaceGate gateFirstIdempotencyClaim(
            String key
    ) {
        RaceGate gate = RaceGate.create();
        AtomicInteger attempts = new AtomicInteger();

        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();

            if (attempt == 2) {
                gate.secondAttempted().countDown();
            }

            boolean inserted =
                    (boolean) invocation.callRealMethod();

            if (attempt == 1) {
                if (!inserted) {
                    throw new IllegalStateException(
                            "first payment claim was not inserted"
                    );
                }

                gate.firstAcquired().countDown();

                await(
                        gate.releaseFirst(),
                        "release first payment claim"
                );
            }

            return inserted;
        }).when(idempotencyRepositorySpy)
                .insertIfAbsent(
                        argThat(record ->
                                record != null
                                        && key.equals(
                                        record.idempotencyKey()
                                )
                        )
                );

        return gate;
    }

    private RaceGate gateFirstOrderLock(
            UUID orderId,
            UUID userId
    ) {
        RaceGate gate = RaceGate.create();
        AtomicInteger attempts = new AtomicInteger();

        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();

            if (attempt == 2) {
                gate.secondAttempted().countDown();
            }

            Object lockedOrder =
                    invocation.callRealMethod();

            if (attempt == 1) {
                gate.firstAcquired().countDown();

                await(
                        gate.releaseFirst(),
                        "release first payment order lock"
                );
            }

            return lockedOrder;
        }).when(orderRepositorySpy)
                .findByIdAndUserIdForUpdate(
                        eq(orderId),
                        eq(userId)
                );

        return gate;
    }

    private Fixture seedFixture() {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        fixtures.add(fixture);

        insertUser(fixture.userId());
        insertProduct(fixture.productId());

        insertOrder(
                fixture.firstOrderId(),
                fixture.userId()
        );

        insertOrderItem(
                fixture.firstOrderId(),
                fixture.productId()
        );

        insertOrder(
                fixture.secondOrderId(),
                fixture.userId()
        );

        insertOrderItem(
                fixture.secondOrderId(),
                fixture.productId()
        );

        return fixture;
    }

    private void insertUser(UUID userId) {
        jdbcTemplate.update(
                """
                insert into users (
                    id, email, name, password_hash,
                    enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                "payment-race-"
                        + userId
                        + "@example.com",
                "Payment Race Test User",
                "password-hash"
        );
    }

    private void insertProduct(UUID productId) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, sku, name, description,
                    price, stock_quantity, status,
                    image_key, image_url,
                    created_at, updated_at
                )
                values (
                    ?, ?, ?, ?,
                    ?, ?, 'ACTIVE',
                    null, null,
                    now(), now()
                )
                """,
                productId,
                "PAYMENT-RACE-" + productId,
                "Payment Race Product",
                "Product used by payment race tests",
                UNIT_PRICE,
                INITIAL_STOCK
        );
    }

    private void insertOrder(
            UUID orderId,
            UUID userId
    ) {
        Instant now = Instant.now();
        Instant createdAt =
                now.minusSeconds(60);

        Instant expiresAt =
                now.plusSeconds(1800);

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
                orderId,
                userId,
                ORDER_TOTAL,
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private void insertOrderItem(
            UUID orderId,
            UUID productId
    ) {
        Instant now = Instant.now();

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
                orderId,
                productId,
                "PAYMENT-RACE-SNAPSHOT-" + productId,
                "Payment Race Snapshot",
                UNIT_PRICE,
                ORDER_QUANTITY,
                ORDER_TOTAL,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private PayOrderCommand command(
            UUID userId,
            UUID orderId,
            String key
    ) {
        return new PayOrderCommand(
                userId,
                orderId,
                key
        );
    }

    private String newKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void assertPaidOutcome(
            Fixture fixture,
            UUID orderId,
            UUID paymentId
    ) {
        assertThat(loadOrderStatus(orderId))
                .isEqualTo(OrderStatus.PAID.name());

        assertThat(loadPaymentId(orderId))
                .isEqualTo(paymentId);

        assertThat(paymentCount(orderId))
                .isEqualTo(1);

        assertThat(historyCount(
                orderId,
                OrderStatusChangeActorType.PAYMENT,
                OrderStatus.PAID
        )).isEqualTo(1);

        assertThat(outboxCount(orderId))
                .isEqualTo(1);

        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK);
    }

    private void assertPendingOutcome(
            UUID orderId
    ) {
        assertThat(loadOrderStatus(orderId))
                .isEqualTo(
                        OrderStatus.PENDING_PAYMENT.name()
                );

        assertThat(paymentCount(orderId))
                .isZero();

        assertThat(historyCount(
                orderId,
                OrderStatusChangeActorType.PAYMENT,
                OrderStatus.PAID
        )).isZero();

        assertThat(outboxCount(orderId))
                .isZero();
    }

    private String loadOrderStatus(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "select status from orders where id = ?",
                String.class,
                orderId
        );
    }

    private UUID loadPaymentId(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "select id from payments where order_id = ?",
                UUID.class,
                orderId
        );
    }

    private int loadStock(UUID productId) {
        return count(
                """
                select stock_quantity
                from products
                where id = ?
                """,
                productId
        );
    }

    private int paymentCount(UUID orderId) {
        return count(
                """
                select count(*)
                from payments
                where order_id = ?
                """,
                orderId
        );
    }

    private int historyCount(
            UUID orderId,
            OrderStatusChangeActorType actorType,
            OrderStatus toStatus
    ) {
        return count(
                """
                select count(*)
                from order_status_history
                where order_id = ?
                  and from_status = 'PENDING_PAYMENT'
                  and to_status = ?
                  and actor_type = ?
                """,
                orderId,
                toStatus.name(),
                actorType.name()
        );
    }

    private int outboxCount(UUID orderId) {
        return count(
                """
                select count(*)
                from outbox_events
                where aggregate_id = ?
                  and event_type = 'ORDER_PAID'
                """,
                orderId
        );
    }

    private IdempotencyState loadIdempotency(
            UUID userId,
            String key
    ) {
        return jdbcTemplate.queryForObject(
                """
                select status, payment_id
                from payment_idempotency_records
                where user_id = ?
                  and idempotency_key = ?
                """,
                (resultSet, rowNumber) ->
                        new IdempotencyState(
                                resultSet.getString("status"),
                                resultSet.getObject(
                                        "payment_id",
                                        UUID.class
                                )
                        ),
                userId,
                key
        );
    }

    private int idempotencyCount(UUID userId) {
        return count(
                """
                select count(*)
                from payment_idempotency_records
                where user_id = ?
                """,
                userId
        );
    }

    private int count(
            String sql,
            Object... arguments
    ) {
        Integer result = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                arguments
        );

        return Objects.requireNonNull(result);
    }

    private BusinessException awaitBusinessFailure(
            Future<?> future
    ) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            assertThat(exception.getCause())
                    .isInstanceOf(
                            BusinessException.class
                    );

            return (BusinessException)
                    exception.getCause();
        }

        throw new AssertionError(
                "expected concurrent payment to fail"
        );
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
                        "timed out waiting for "
                                + description
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "interrupted while waiting for "
                            + description,
                    exception
            );
        }
    }

    private void shutdown(
            ExecutorService executor
    ) {
        executor.shutdownNow();

        try {
            executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record RaceGate(
            CountDownLatch firstAcquired,
            CountDownLatch secondAttempted,
            CountDownLatch releaseFirst
    ) {
        private static RaceGate create() {
            return new RaceGate(
                    new CountDownLatch(1),
                    new CountDownLatch(1),
                    new CountDownLatch(1)
            );
        }
    }

    private record Fixture(
            UUID userId,
            UUID productId,
            UUID firstOrderId,
            UUID secondOrderId
    ) {
    }

    private record IdempotencyState(
            String status,
            UUID paymentId
    ) {
    }
}