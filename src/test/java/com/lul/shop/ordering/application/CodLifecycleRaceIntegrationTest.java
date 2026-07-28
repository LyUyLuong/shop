package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
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
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CodLifecycleRaceIntegrationTest
        extends PostgresIntegrationTest {

    private static final int STOCK_AFTER_CHECKOUT = 8;
    private static final int ORDER_QUANTITY = 2;

    private static final BigDecimal UNIT_PRICE =
            new BigDecimal("100000.00");

    private static final BigDecimal SUBTOTAL =
            new BigDecimal("200000.00");

    private static final BigDecimal SHIPPING_FEE =
            new BigDecimal("30000.00");

    private static final BigDecimal TOTAL =
            new BigDecimal("230000.00");

    private static final String PACKING_REASON =
            "Order accepted for packing";

    private static final String CANCELLATION_REASON =
            "Customer cancelled before packing";

    @Autowired
    private OrderOperationsService orderOperationsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    private OrderRepository orderRepositorySpy;

    private final List<Fixture> fixtures =
            new ArrayList<>();

    @BeforeEach
    void unwrapOrderRepositorySpy() {
        orderRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        orderRepository
                );
    }

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "delete from outbox_events where aggregate_id = ?",
                    fixture.orderId()
            );

            jdbcTemplate.update(
                    """
                    delete from payment_idempotency_records
                    where user_id in (?, ?)
                    """,
                    fixture.customerId(),
                    fixture.adminId()
            );

            jdbcTemplate.update(
                    "delete from payments where order_id = ?",
                    fixture.orderId()
            );

            jdbcTemplate.update(
                    """
                    delete from order_status_history
                    where order_id = ?
                    """,
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
                    fixture.customerId(),
                    fixture.adminId()
            );
        }
    }

    @Test
    void shouldKeepPackingWhenProcessingLocksBeforeCancellation()
            throws Exception {
        Fixture fixture = seedConfirmedCodOrder();
        RaceGate gate = gateFirstOrderLock(fixture.orderId());

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<?> processing = executor.submit(() ->
                orderOperationsService.changeStatus(
                        packingCommand(fixture)
                )
        );

        try {
            await(
                    gate.firstAcquired(),
                    "COD processing order lock"
            );

            Future<?> cancellation = executor.submit(() ->
                    orderOperationsService.changeStatus(
                            cancellationCommand(fixture)
                    )
            );

            await(
                    gate.secondAttempted(),
                    "COD cancellation lock attempt"
            );

            gate.releaseFirst().countDown();

            processing.get(20, TimeUnit.SECONDS);

            BusinessException failure =
                    awaitBusinessFailure(cancellation);

            assertThat(failure.getErrorCode())
                    .isEqualTo(
                            OrderingErrorCode
                                    .INVALID_ORDER_STATUS_TRANSITION
                    );

            assertPackingState(fixture);

            verify(orderRepositorySpy, times(2))
                    .findByIdForUpdate(fixture.orderId());
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldKeepCancelledWhenCancellationLocksBeforeProcessing()
            throws Exception {
        Fixture fixture = seedConfirmedCodOrder();
        RaceGate gate = gateFirstOrderLock(fixture.orderId());

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<?> cancellation = executor.submit(() ->
                orderOperationsService.changeStatus(
                        cancellationCommand(fixture)
                )
        );

        try {
            await(
                    gate.firstAcquired(),
                    "COD cancellation order lock"
            );

            Future<?> processing = executor.submit(() ->
                    orderOperationsService.changeStatus(
                            packingCommand(fixture)
                    )
            );

            await(
                    gate.secondAttempted(),
                    "COD processing lock attempt"
            );

            gate.releaseFirst().countDown();

            cancellation.get(20, TimeUnit.SECONDS);

            BusinessException failure =
                    awaitBusinessFailure(processing);

            assertThat(failure.getErrorCode())
                    .isEqualTo(
                            OrderingErrorCode
                                    .INVALID_ORDER_STATUS_TRANSITION
                    );

            assertCancelledState(fixture);

            verify(orderRepositorySpy, times(2))
                    .findByIdForUpdate(fixture.orderId());
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    private RaceGate gateFirstOrderLock(UUID orderId) {
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
                        "release first COD lifecycle lock"
                );
            }

            return lockedOrder;
        }).when(orderRepositorySpy)
                .findByIdForUpdate(eq(orderId));

        return gate;
    }

    private Fixture seedConfirmedCodOrder() {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        fixtures.add(fixture);

        insertUser(
                fixture.customerId(),
                "cod-lifecycle-customer-"
                        + fixture.customerId()
                        + "@example.com"
        );

        insertUser(
                fixture.adminId(),
                "cod-lifecycle-admin-"
                        + fixture.adminId()
                        + "@example.com"
        );

        insertProduct(fixture.productId());

        Instant createdAt =
                Instant.now().minusSeconds(600);

        jdbcTemplate.update(
                """
                insert into orders (
                    id, user_id, status, total_amount,
                    expires_at, inventory_released_at,
                    recipient_name, recipient_phone,
                    shipping_address, shipping_method,
                    payment_mode, subtotal_amount,
                    shipping_fee, created_at, updated_at
                )
                values (
                    ?, ?, 'CONFIRMED', ?,
                    null, null,
                    'Nguyen Van A', '+84901234567',
                    '123 Nguyen Trai, Ho Chi Minh City',
                    'STANDARD',
                    'COD', ?, ?, ?, ?
                )
                """,
                fixture.orderId(),
                fixture.customerId(),
                TOTAL,
                SUBTOTAL,
                SHIPPING_FEE,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

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
                "COD-LIFECYCLE-SNAPSHOT-"
                        + fixture.productId(),
                "COD Lifecycle Product",
                UNIT_PRICE,
                ORDER_QUANTITY,
                SUBTOTAL,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        return fixture;
    }

    private void insertUser(
            UUID userId,
            String email
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
                email,
                "COD Lifecycle Race User",
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
                "COD-LIFECYCLE-" + productId,
                "COD Lifecycle Product",
                "Product used by COD lifecycle races",
                UNIT_PRICE,
                STOCK_AFTER_CHECKOUT
        );
    }

    private void assertPackingState(Fixture fixture) {
        PersistedOrder order = loadOrder(fixture.orderId());

        assertThat(order.status())
                .isEqualTo(OrderStatus.PACKING.name());
        assertThat(order.inventoryReleasedAt())
                .isNull();

        ProductState product = loadProduct(fixture.productId());

        assertThat(product.stockQuantity())
                .isEqualTo(STOCK_AFTER_CHECKOUT);
        assertThat(product.version()).isZero();

        assertHistory(
                fixture,
                OrderStatus.PACKING,
                PACKING_REASON
        );

        assertNoPaymentSideEffects(fixture);
    }

    private void assertCancelledState(Fixture fixture) {
        PersistedOrder order = loadOrder(fixture.orderId());

        assertThat(order.status())
                .isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(order.inventoryReleasedAt())
                .isNotNull();

        ProductState product = loadProduct(fixture.productId());

        assertThat(product.stockQuantity())
                .isEqualTo(
                        STOCK_AFTER_CHECKOUT + ORDER_QUANTITY
                );
        assertThat(product.version()).isEqualTo(1L);

        assertHistory(
                fixture,
                OrderStatus.CANCELLED,
                CANCELLATION_REASON
        );

        assertNoPaymentSideEffects(fixture);
    }

    private void assertHistory(
            Fixture fixture,
            OrderStatus toStatus,
            String reason
    ) {
        assertThat(historyCount(fixture.orderId()))
                .isEqualTo(1);

        Map<String, Object> history =
                jdbcTemplate.queryForMap(
                        """
                        select from_status, to_status,
                               actor_type, actor_user_id,
                               reason
                        from order_status_history
                        where order_id = ?
                        """,
                        fixture.orderId()
                );

        assertThat(history.get("from_status"))
                .isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(history.get("to_status"))
                .isEqualTo(toStatus.name());
        assertThat(history.get("actor_type"))
                .isEqualTo(
                        OrderStatusChangeActorType.ADMIN.name()
                );
        assertThat(history.get("actor_user_id"))
                .isEqualTo(fixture.adminId());
        assertThat(history.get("reason"))
                .isEqualTo(reason);
    }

    private void assertNoPaymentSideEffects(Fixture fixture) {
        assertThat(count(
                "select count(*) from payments where order_id = ?",
                fixture.orderId()
        )).isZero();

        assertThat(count(
                """
                select count(*)
                from payment_idempotency_records
                where user_id in (?, ?)
                """,
                fixture.customerId(),
                fixture.adminId()
        )).isZero();

        assertThat(count(
                """
                select count(*)
                from outbox_events
                where aggregate_id = ?
                """,
                fixture.orderId()
        )).isZero();
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

    private ProductState loadProduct(UUID productId) {
        return jdbcTemplate.queryForObject(
                """
                select stock_quantity, version
                from products
                where id = ?
                """,
                (resultSet, rowNumber) ->
                        new ProductState(
                                resultSet.getInt(
                                        "stock_quantity"
                                ),
                                resultSet.getLong("version")
                        ),
                productId
        );
    }

    private int historyCount(UUID orderId) {
        return count(
                """
                select count(*)
                from order_status_history
                where order_id = ?
                """,
                orderId
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

    private ChangeOrderStatusCommand packingCommand(
            Fixture fixture
    ) {
        return new ChangeOrderStatusCommand(
                fixture.orderId(),
                fixture.adminId(),
                OrderStatus.PACKING,
                PACKING_REASON
        );
    }

    private ChangeOrderStatusCommand cancellationCommand(
            Fixture fixture
    ) {
        return new ChangeOrderStatusCommand(
                fixture.orderId(),
                fixture.adminId(),
                OrderStatus.CANCELLED,
                CANCELLATION_REASON
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

            return (BusinessException)
                    exception.getCause();
        }

        throw new AssertionError(
                "expected concurrent COD lifecycle operation to fail"
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

    private void shutdown(ExecutorService executor) {
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
            UUID customerId,
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

    private record ProductState(
            int stockQuantity,
            long version
    ) {
    }
}
