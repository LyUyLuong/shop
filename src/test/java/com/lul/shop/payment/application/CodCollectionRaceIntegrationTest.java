package com.lul.shop.payment.application;

import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.payment.application.dto.CollectCodCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.PaymentProviderRequest;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.payment.infrastructure.provider.CodPaymentProvider;
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

class CodCollectionRaceIntegrationTest
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

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private PaymentIdempotencyRepository
            paymentIdempotencyRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    @MockitoSpyBean
    private CodPaymentProvider codPaymentProvider;

    private PaymentIdempotencyRepository
            paymentIdempotencyRepositorySpy;

    private OrderRepository orderRepositorySpy;
    private PaymentRepository paymentRepositorySpy;

    private final List<Fixture> fixtures =
            new ArrayList<>();

    @BeforeEach
    void unwrapRepositorySpies() {
        paymentIdempotencyRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        paymentIdempotencyRepository
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
    void shouldReplayOneCodPaymentForConcurrentMatchingKey()
            throws Exception {
        Fixture fixture = seedShippedCodOrder();
        String key = newKey("cod-same-key");

        RaceGate gate = gateFirstIdempotencyClaim(
                fixture.adminId(),
                key
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<PaymentResult> owner = executor.submit(() ->
                paymentService.collectCod(
                        command(fixture, key)
                )
        );

        try {
            await(
                    gate.firstAcquired(),
                    "first COD idempotency claim"
            );

            Future<PaymentResult> follower =
                    executor.submit(() ->
                            paymentService.collectCod(
                                    command(fixture, key)
                            )
                    );

            await(
                    gate.secondAttempted(),
                    "second COD idempotency claim attempt"
            );

            gate.releaseFirst().countDown();

            PaymentResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);

            PaymentResult followerResult =
                    follower.get(20, TimeUnit.SECONDS);

            assertThat(followerResult.id())
                    .isEqualTo(ownerResult.id());

            assertPaymentResult(fixture, ownerResult);
            assertPaymentResult(fixture, followerResult);
            assertCollectedState(fixture, ownerResult.id());

            assertThat(idempotencyCount(
                    fixture.adminId()
            )).isEqualTo(1);

            assertThat(idempotencyCount(
                    fixture.customerId()
            )).isZero();

            assertThat(loadIdempotency(
                    fixture.adminId(),
                    key
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            verify(paymentIdempotencyRepositorySpy, times(2))
                    .insertIfAbsent(argThat(record ->
                            record != null
                                    && fixture.adminId().equals(
                                    record.userId()
                            )
                                    && key.equals(
                                    record.idempotencyKey()
                            )
                    ));

            verify(paymentIdempotencyRepositorySpy, times(1))
                    .findByUserIdAndKey(
                            fixture.adminId(),
                            key
                    );

            verify(paymentIdempotencyRepositorySpy, times(1))
                    .complete(
                            any(UUID.class),
                            eq(ownerResult.id()),
                            any(Instant.class)
                    );

            verify(orderRepositorySpy, times(1))
                    .findByIdForUpdate(fixture.orderId());

            verify(codPaymentProvider, times(1))
                    .process(any(PaymentProviderRequest.class));

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
    void shouldConvergeDifferentKeysOnOneCodPayment()
            throws Exception {
        Fixture fixture = seedShippedCodOrder();

        String ownerKey =
                newKey("cod-owner");

        String followerKey =
                newKey("cod-follower");

        RaceGate gate =
                gateFirstOrderLock(fixture.orderId());

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<PaymentResult> owner = executor.submit(() ->
                paymentService.collectCod(
                        command(fixture, ownerKey)
                )
        );

        try {
            await(
                    gate.firstAcquired(),
                    "first COD order lock"
            );

            Future<PaymentResult> follower =
                    executor.submit(() ->
                            paymentService.collectCod(
                                    command(fixture, followerKey)
                            )
                    );

            await(
                    gate.secondAttempted(),
                    "second COD order lock attempt"
            );

            gate.releaseFirst().countDown();

            PaymentResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);

            PaymentResult followerResult =
                    follower.get(20, TimeUnit.SECONDS);

            assertThat(followerResult.id())
                    .isEqualTo(ownerResult.id());

            assertPaymentResult(fixture, ownerResult);
            assertPaymentResult(fixture, followerResult);
            assertCollectedState(fixture, ownerResult.id());

            assertThat(idempotencyCount(
                    fixture.adminId()
            )).isEqualTo(2);

            assertThat(idempotencyCount(
                    fixture.customerId()
            )).isZero();

            assertThat(loadIdempotency(
                    fixture.adminId(),
                    ownerKey
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            assertThat(loadIdempotency(
                    fixture.adminId(),
                    followerKey
            )).isEqualTo(new IdempotencyState(
                    "COMPLETED",
                    ownerResult.id()
            ));

            verify(paymentIdempotencyRepositorySpy, times(2))
                    .insertIfAbsent(argThat(record ->
                            record != null
                                    && fixture.adminId().equals(
                                    record.userId()
                            )
                    ));

            verify(paymentIdempotencyRepositorySpy, never())
                    .findByUserIdAndKey(
                            any(UUID.class),
                            any(String.class)
                    );

            verify(paymentIdempotencyRepositorySpy, times(2))
                    .complete(
                            any(UUID.class),
                            eq(ownerResult.id()),
                            any(Instant.class)
                    );

            verify(orderRepositorySpy, times(2))
                    .findByIdForUpdate(fixture.orderId());

            verify(codPaymentProvider, times(1))
                    .process(any(PaymentProviderRequest.class));

            verify(paymentRepositorySpy, times(1))
                    .save(any(Payment.class));

            verify(paymentRepositorySpy, times(1))
                    .findByOrderId(fixture.orderId());

            verify(paymentRepositorySpy, never())
                    .findById(any(UUID.class));
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    private RaceGate gateFirstIdempotencyClaim(
            UUID adminId,
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
                            "first COD claim was not inserted"
                    );
                }

                gate.firstAcquired().countDown();

                await(
                        gate.releaseFirst(),
                        "release first COD claim"
                );
            }

            return inserted;
        }).when(paymentIdempotencyRepositorySpy)
                .insertIfAbsent(argThat(record ->
                        record != null
                                && adminId.equals(record.userId())
                                && key.equals(
                                record.idempotencyKey()
                        )
                ));

        return gate;
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
                        "release first COD order lock"
                );
            }

            return lockedOrder;
        }).when(orderRepositorySpy)
                .findByIdForUpdate(eq(orderId));

        return gate;
    }

    private Fixture seedShippedCodOrder() {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        fixtures.add(fixture);

        insertUser(
                fixture.customerId(),
                "cod-race-customer-"
                        + fixture.customerId()
                        + "@example.com"
        );

        insertUser(
                fixture.adminId(),
                "cod-race-admin-"
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
                    ?, ?, 'SHIPPED', ?,
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
                "COD-RACE-SNAPSHOT-"
                        + fixture.productId(),
                "COD Race Product",
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
                "COD Race User",
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
                "COD-RACE-" + productId,
                "COD Race Product",
                "Product used by COD race tests",
                UNIT_PRICE,
                STOCK_AFTER_CHECKOUT
        );
    }

    private void assertPaymentResult(
            Fixture fixture,
            PaymentResult result
    ) {
        assertThat(result.orderId())
                .isEqualTo(fixture.orderId());
        assertThat(result.userId())
                .isEqualTo(fixture.customerId());
        assertThat(result.method())
                .isEqualTo(PaymentMethod.COD);
        assertThat(result.status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.amount())
                .isEqualByComparingTo(TOTAL);
        assertThat(result.paidAt()).isNotNull();
    }

    private void assertCollectedState(
            Fixture fixture,
            UUID paymentId
    ) {
        Map<String, Object> order = jdbcTemplate.queryForMap(
                """
                select status, inventory_released_at
                from orders
                where id = ?
                """,
                fixture.orderId()
        );

        assertThat(order.get("status"))
                .isEqualTo(OrderStatus.COMPLETED.name());
        assertThat(order.get("inventory_released_at"))
                .isNull();

        ProductState product = loadProduct(fixture.productId());

        assertThat(product.stockQuantity())
                .isEqualTo(STOCK_AFTER_CHECKOUT);
        assertThat(product.version()).isZero();

        Map<String, Object> payment =
                jdbcTemplate.queryForMap(
                        """
                        select id, user_id, method,
                               status, amount
                        from payments
                        where order_id = ?
                        """,
                        fixture.orderId()
                );

        assertThat(payment.get("id"))
                .isEqualTo(paymentId);
        assertThat(payment.get("user_id"))
                .isEqualTo(fixture.customerId());
        assertThat(payment.get("method"))
                .isEqualTo(PaymentMethod.COD.name());
        assertThat(payment.get("status"))
                .isEqualTo(PaymentStatus.SUCCEEDED.name());
        assertThat((BigDecimal) payment.get("amount"))
                .isEqualByComparingTo(TOTAL);

        assertThat(paymentCount(fixture.orderId()))
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
                .isEqualTo(OrderStatus.SHIPPED.name());
        assertThat(history.get("to_status"))
                .isEqualTo(OrderStatus.COMPLETED.name());
        assertThat(history.get("actor_type"))
                .isEqualTo(
                        OrderStatusChangeActorType.ADMIN.name()
                );
        assertThat(history.get("actor_user_id"))
                .isEqualTo(fixture.adminId());
        assertThat(history.get("reason"))
                .isEqualTo("Cash on delivery collected");

        assertThat(historyCount(fixture.orderId()))
                .isEqualTo(1);

        Map<String, Object> outbox =
                jdbcTemplate.queryForMap(
                        """
                        select event_type, aggregate_type,
                               aggregate_id, payload
                        from outbox_events
                        where aggregate_id = ?
                        """,
                        fixture.orderId()
                );

        assertThat(outbox.get("event_type"))
                .isEqualTo("ORDER_PAID");
        assertThat(outbox.get("aggregate_type"))
                .isEqualTo("ORDER");
        assertThat(outbox.get("aggregate_id"))
                .isEqualTo(fixture.orderId());

        String payload = (String) outbox.get("payload");

        assertThat(payload)
                .contains(fixture.orderId().toString())
                .contains(paymentId.toString())
                .contains(fixture.customerId().toString());

        assertThat(outboxCount(fixture.orderId()))
                .isEqualTo(1);
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

    private IdempotencyState loadIdempotency(
            UUID principalId,
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
                principalId,
                key
        );
    }

    private int idempotencyCount(UUID principalId) {
        return count(
                """
                select count(*)
                from payment_idempotency_records
                where user_id = ?
                """,
                principalId
        );
    }

    private int paymentCount(UUID orderId) {
        return count(
                "select count(*) from payments where order_id = ?",
                orderId
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

    private CollectCodCommand command(
            Fixture fixture,
            String key
    ) {
        return new CollectCodCommand(
                fixture.adminId(),
                fixture.orderId(),
                key
        );
    }

    private String newKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
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

    private record IdempotencyState(
            String status,
            UUID paymentId
    ) {
    }

    private record ProductState(
            int stockQuantity,
            long version
    ) {
    }
}
