package com.lul.shop.ordering.application;

import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

class OrderPlacementRaceIntegrationTest
        extends PostgresIntegrationTest {

    private static final long CART_VERSION = 0L;
    private static final BigDecimal LOW_PRODUCT_PRICE =
            new BigDecimal("100.00");
    private static final BigDecimal HIGH_PRODUCT_PRICE =
            new BigDecimal("250.00");

    @Autowired
    private OrderingService orderingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OrderIdempotencyRepository idempotencyRepository;

    @MockitoSpyBean
    private CartRepository cartRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    private OrderIdempotencyRepository idempotencyRepositorySpy;
    private CartRepository cartRepositorySpy;
    private ProductRepository productRepositorySpy;

    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach
    void unwrapRepositorySpies() {
        idempotencyRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        idempotencyRepository
                );
        cartRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        cartRepository
                );
        productRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        productRepository
                );
    }

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "delete from order_idempotency_records "
                            + "where user_id in (?, ?)",
                    fixture.firstUserId(),
                    fixture.secondUserId()
            );
            jdbcTemplate.update(
                    """
                    delete from order_items
                    where order_id in (
                        select id from orders
                        where user_id in (?, ?)
                    )
                    """,
                    fixture.firstUserId(),
                    fixture.secondUserId()
            );
            jdbcTemplate.update(
                    "delete from orders where user_id in (?, ?)",
                    fixture.firstUserId(),
                    fixture.secondUserId()
            );
            jdbcTemplate.update(
                    "delete from carts where id in (?, ?)",
                    fixture.firstCartId(),
                    fixture.secondCartId()
            );
            jdbcTemplate.update(
                    "delete from products where id in (?, ?)",
                    fixture.lowProductId(),
                    fixture.highProductId()
            );
            jdbcTemplate.update(
                    "delete from users where id in (?, ?)",
                    fixture.firstUserId(),
                    fixture.secondUserId()
            );
        }
    }

    @Test
    void shouldReturnOneOrderForConcurrentMatchingRequests()
            throws Exception {
        Fixture fixture = seedFixture(10);
        String key = newKey("same-request");
        RaceGate gate = gateFirstIdempotencyClaim(key);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<OrderResult> owner = executor.submit(() ->
                orderingService.placeOrder(command(
                        fixture.firstUserId(),
                        fixture.firstCartId(),
                        CART_VERSION,
                        key
                ))
        );

        try {
            await(gate.firstAcquired(), "first idempotency claim");

            Future<OrderResult> follower = executor.submit(() ->
                    orderingService.placeOrder(command(
                            fixture.firstUserId(),
                            fixture.firstCartId(),
                            CART_VERSION,
                            key
                    ))
            );

            await(gate.secondAttempted(), "second idempotency claim");
            gate.releaseFirst().countDown();

            OrderResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);
            OrderResult followerResult =
                    follower.get(20, TimeUnit.SECONDS);

            assertThat(followerResult.id())
                    .isEqualTo(ownerResult.id());
            assertFirstCartWon(
                    fixture,
                    ownerResult,
                    key
            );
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldRejectConcurrentKeyReuseWithAnotherFingerprint()
            throws Exception {
        Fixture fixture = seedFixture(10);
        String key = newKey("reused-key");
        RaceGate gate = gateFirstIdempotencyClaim(key);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<OrderResult> owner = executor.submit(() ->
                orderingService.placeOrder(command(
                        fixture.firstUserId(),
                        fixture.firstCartId(),
                        CART_VERSION,
                        key
                ))
        );

        try {
            await(gate.firstAcquired(), "first idempotency claim");

            Future<OrderResult> follower = executor.submit(() ->
                    orderingService.placeOrder(command(
                            fixture.firstUserId(),
                            fixture.firstCartId(),
                            CART_VERSION + 1,
                            key
                    ))
            );

            await(gate.secondAttempted(), "conflicting claim attempt");
            gate.releaseFirst().countDown();

            OrderResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);
            BusinessException failure =
                    awaitBusinessFailure(follower);

            assertThat(failure.getErrorCode())
                    .isEqualTo(
                            OrderingErrorCode.IDEMPOTENCY_KEY_REUSED
                    );
            assertFirstCartWon(
                    fixture,
                    ownerResult,
                    key
            );
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldAllowOnlyOneCheckoutForDifferentKeysOnSameCart()
            throws Exception {
        Fixture fixture = seedFixture(10);
        String ownerKey = newKey("cart-owner");
        String contenderKey = newKey("cart-contender");
        RaceGate gate = gateFirstCartLock(
                fixture.firstCartId(),
                fixture.firstUserId()
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<OrderResult> owner = executor.submit(() ->
                orderingService.placeOrder(command(
                        fixture.firstUserId(),
                        fixture.firstCartId(),
                        CART_VERSION,
                        ownerKey
                ))
        );

        try {
            await(gate.firstAcquired(), "first cart lock");

            Future<OrderResult> contender = executor.submit(() ->
                    orderingService.placeOrder(command(
                            fixture.firstUserId(),
                            fixture.firstCartId(),
                            CART_VERSION,
                            contenderKey
                    ))
            );

            await(gate.secondAttempted(), "second cart lock attempt");
            gate.releaseFirst().countDown();

            OrderResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);
            BusinessException failure =
                    awaitBusinessFailure(contender);

            assertThat(failure.getErrorCode())
                    .isEqualTo(
                            OrderingErrorCode.CART_CHECKOUT_CONFLICT
                    );
            assertThat(idempotencyCount(
                    fixture.firstUserId(),
                    contenderKey
            )).isZero();
            assertFirstCartWon(
                    fixture,
                    ownerResult,
                    ownerKey
            );
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    @Test
    void shouldPreventOversellAndDeadlockForOverlappingProducts()
            throws Exception {
        Fixture fixture = seedFixture(1);
        String ownerKey = newKey("stock-owner");
        String contenderKey = newKey("stock-contender");
        RaceGate gate = gateFirstStockUpdate(
                fixture.lowProductId()
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<OrderResult> owner = executor.submit(() ->
                orderingService.placeOrder(command(
                        fixture.firstUserId(),
                        fixture.firstCartId(),
                        CART_VERSION,
                        ownerKey
                ))
        );

        try {
            await(gate.firstAcquired(), "first shared stock update");

            Future<OrderResult> contender = executor.submit(() ->
                    orderingService.placeOrder(command(
                            fixture.secondUserId(),
                            fixture.secondCartId(),
                            CART_VERSION,
                            contenderKey
                    ))
            );

            await(gate.secondAttempted(), "second stock update attempt");
            gate.releaseFirst().countDown();

            OrderResult ownerResult =
                    owner.get(20, TimeUnit.SECONDS);
            BusinessException failure =
                    awaitBusinessFailure(contender);

            assertThat(failure.getErrorCode())
                    .isEqualTo(
                            OrderingErrorCode.INSUFFICIENT_STOCK
                    );
            assertThat(idempotencyCount(
                    fixture.secondUserId(),
                    contenderKey
            )).isZero();
            assertFirstCartWon(
                    fixture,
                    ownerResult,
                    ownerKey
            );
        } finally {
            gate.releaseFirst().countDown();
            shutdown(executor);
        }
    }

    private RaceGate gateFirstIdempotencyClaim(String key) {
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
                            "first idempotency claim was not inserted"
                    );
                }

                gate.firstAcquired().countDown();
                await(
                        gate.releaseFirst(),
                        "release first idempotency claim"
                );
            }

            return inserted;
        }).when(idempotencyRepositorySpy).insertIfAbsent(
                argThat(record ->
                        record != null
                                && key.equals(
                                record.idempotencyKey()
                        )
                )
        );

        return gate;
    }

    private RaceGate gateFirstCartLock(
            UUID cartId,
            UUID userId
    ) {
        RaceGate gate = RaceGate.create();
        AtomicInteger attempts = new AtomicInteger();

        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();

            if (attempt == 2) {
                gate.secondAttempted().countDown();
            }

            Object lockedCart = invocation.callRealMethod();

            if (attempt == 1) {
                gate.firstAcquired().countDown();
                await(
                        gate.releaseFirst(),
                        "release first cart lock"
                );
            }

            return lockedCart;
        }).when(cartRepositorySpy).findByIdAndUserIdForUpdate(
                eq(cartId),
                eq(userId)
        );

        return gate;
    }

    private RaceGate gateFirstStockUpdate(UUID productId) {
        RaceGate gate = RaceGate.create();
        AtomicInteger attempts = new AtomicInteger();

        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();

            if (attempt == 2) {
                gate.secondAttempted().countDown();
            }

            boolean updated =
                    (boolean) invocation.callRealMethod();

            if (attempt == 1) {
                if (!updated) {
                    throw new IllegalStateException(
                            "first shared stock update failed"
                    );
                }

                gate.firstAcquired().countDown();
                await(
                        gate.releaseFirst(),
                        "release first stock update"
                );
            }

            return updated;
        }).when(productRepositorySpy).decreaseStockIfEnough(
                eq(productId),
                eq(1)
        );

        return gate;
    }

    private Fixture seedFixture(int initialStock) {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderedProductId(0x1000000000000000L),
                orderedProductId(0x2000000000000000L),
                initialStock
        );

        fixtures.add(fixture);

        insertUser(fixture.firstUserId(), "first");
        insertUser(fixture.secondUserId(), "second");
        insertProduct(
                fixture.lowProductId(),
                "LOW",
                LOW_PRODUCT_PRICE,
                initialStock
        );
        insertProduct(
                fixture.highProductId(),
                "HIGH",
                HIGH_PRODUCT_PRICE,
                initialStock
        );
        insertCart(
                fixture.firstCartId(),
                fixture.firstUserId()
        );
        insertCartItem(
                fixture.firstCartId(),
                fixture.highProductId()
        );
        insertCartItem(
                fixture.firstCartId(),
                fixture.lowProductId()
        );
        insertCart(
                fixture.secondCartId(),
                fixture.secondUserId()
        );
        insertCartItem(
                fixture.secondCartId(),
                fixture.lowProductId()
        );
        insertCartItem(
                fixture.secondCartId(),
                fixture.highProductId()
        );

        return fixture;
    }

    private UUID orderedProductId(long mostSignificantBits) {
        return new UUID(
                mostSignificantBits,
                UUID.randomUUID().getLeastSignificantBits()
        );
    }

    private void insertUser(UUID userId, String label) {
        jdbcTemplate.update(
                """
                insert into users (
                    id, email, name, password_hash,
                    enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                "order-race-" + label + "-" + userId
                        + "@example.com",
                "Order Race Test User",
                "password-hash"
        );
    }

    private void insertProduct(
            UUID productId,
            String label,
            BigDecimal price,
            int stock
    ) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, version, sku, name, description,
                    price, stock_quantity, status,
                    created_at, updated_at
                )
                values (?, 0, ?, ?, ?, ?, ?, 'ACTIVE', now(), now())
                """,
                productId,
                "ORDER-RACE-" + label + "-" + productId,
                "Order Race " + label,
                "Product used by order placement race tests",
                price,
                stock
        );
    }

    private void insertCart(UUID cartId, UUID userId) {
        jdbcTemplate.update(
                """
                insert into carts (
                    id, user_id, version, created_at, updated_at
                )
                values (?, ?, ?, now(), now())
                """,
                cartId,
                userId,
                CART_VERSION
        );
    }

    private void insertCartItem(UUID cartId, UUID productId) {
        jdbcTemplate.update(
                """
                insert into cart_items (
                    id, cart_id, product_id,
                    quantity, created_at, updated_at
                )
                values (?, ?, ?, 1, now(), now())
                """,
                UUID.randomUUID(),
                cartId,
                productId
        );
    }

    private PlaceOrderCommand command(
            UUID userId,
            UUID cartId,
            long cartVersion,
            String key
    ) {
        return new PlaceOrderCommand(
                userId,
                cartId,
                cartVersion,
                key
        );
    }

    private String newKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void assertFirstCartWon(
            Fixture fixture,
            OrderResult result,
            String idempotencyKey
    ) {
        assertThat(result.userId())
                .isEqualTo(fixture.firstUserId());
        assertThat(result.totalAmount())
                .isEqualByComparingTo("350.00");
        assertThat(result.items())
                .extracting(item -> item.productId())
                .containsExactly(
                        fixture.lowProductId(),
                        fixture.highProductId()
                );

        assertThat(orderCount(fixture.firstUserId()))
                .isEqualTo(1);
        assertThat(orderCount(fixture.secondUserId()))
                .isZero();
        assertThat(orderItemCount(result.id()))
                .isEqualTo(2);
        assertThat(idempotencyCount(fixture))
                .isEqualTo(1);
        assertThat(loadIdempotency(
                fixture.firstUserId(),
                idempotencyKey
        )).isEqualTo(new IdempotencyState(
                "COMPLETED",
                result.id()
        ));

        assertCartState(fixture.firstCartId(), 1L, 0);
        assertCartState(fixture.secondCartId(), CART_VERSION, 2);
        assertProductState(
                fixture.lowProductId(),
                fixture.initialStock() - 1,
                1L
        );
        assertProductState(
                fixture.highProductId(),
                fixture.initialStock() - 1,
                1L
        );
    }

    private void assertCartState(
            UUID cartId,
            long expectedVersion,
            int expectedItemCount
    ) {
        Long version = jdbcTemplate.queryForObject(
                "select version from carts where id = ?",
                Long.class,
                cartId
        );
        Integer itemCount = jdbcTemplate.queryForObject(
                "select count(*) from cart_items where cart_id = ?",
                Integer.class,
                cartId
        );

        assertThat(Objects.requireNonNull(version))
                .isEqualTo(expectedVersion);
        assertThat(Objects.requireNonNull(itemCount))
                .isEqualTo(expectedItemCount);
    }

    private void assertProductState(
            UUID productId,
            int expectedStock,
            long expectedVersion
    ) {
        ProductState state = jdbcTemplate.queryForObject(
                """
                select stock_quantity, version
                from products
                where id = ?
                """,
                (resultSet, rowNumber) -> new ProductState(
                        resultSet.getInt("stock_quantity"),
                        resultSet.getLong("version")
                ),
                productId
        );

        assertThat(state).isEqualTo(new ProductState(
                expectedStock,
                expectedVersion
        ));
    }

    private IdempotencyState loadIdempotency(
            UUID userId,
            String key
    ) {
        return jdbcTemplate.queryForObject(
                """
                select status, order_id
                from order_idempotency_records
                where user_id = ?
                  and idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new IdempotencyState(
                        resultSet.getString("status"),
                        resultSet.getObject("order_id", UUID.class)
                ),
                userId,
                key
        );
    }

    private int orderCount(UUID userId) {
        return count(
                "select count(*) from orders where user_id = ?",
                userId
        );
    }

    private int orderItemCount(UUID orderId) {
        return count(
                "select count(*) from order_items where order_id = ?",
                orderId
        );
    }

    private int idempotencyCount(Fixture fixture) {
        return count(
                """
                select count(*)
                from order_idempotency_records
                where user_id in (?, ?)
                """,
                fixture.firstUserId(),
                fixture.secondUserId()
        );
    }

    private int idempotencyCount(UUID userId, String key) {
        return count(
                """
                select count(*)
                from order_idempotency_records
                where user_id = ?
                  and idempotency_key = ?
                """,
                userId,
                key
        );
    }

    private int count(String sql, Object... arguments) {
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
                    .isInstanceOf(BusinessException.class);

            return (BusinessException) exception.getCause();
        }

        throw new AssertionError(
                "expected concurrent checkout to fail"
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
            UUID firstUserId,
            UUID secondUserId,
            UUID firstCartId,
            UUID secondCartId,
            UUID lowProductId,
            UUID highProductId,
            int initialStock
    ) {
    }

    private record ProductState(
            int stock,
            long version
    ) {
    }

    private record IdempotencyState(
            String status,
            UUID orderId
    ) {
    }
}
