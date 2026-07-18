package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.application.OrderExpiryBatchProcessor;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExpiryClaimConcurrencyTest
        extends PostgresIntegrationTest {

    private static final Instant CUTOFF =
            Instant.parse("2026-07-17T04:00:00Z");

    private static final BigDecimal ORDER_TOTAL =
            new BigDecimal("100000.00");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderExpiryBatchProcessor expiryBatchProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> orderIds = new ArrayList<>();
    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanDatabase() {
        for (UUID orderId : orderIds) {

            jdbcTemplate.update(
                    "delete from order_status_history where order_id = ?",
                    orderId
            );

            jdbcTemplate.update(
                    "delete from order_items where order_id = ?",
                    orderId
            );
            jdbcTemplate.update(
                    "delete from orders where id = ?",
                    orderId
            );
        }

        for (UUID productId : productIds) {
            jdbcTemplate.update(
                    "delete from products where id = ?",
                    productId
            );
        }

        for (UUID userId : userIds) {
            jdbcTemplate.update(
                    "delete from users where id = ?",
                    userId
            );
        }
    }

    @Test
    void shouldSkipOrderLockedByAnotherClaimTransaction()
            throws Exception {
        Fixture fixture = seedTwoExpiredOrders();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch firstClaimAcquired =
                new CountDownLatch(1);

        CountDownLatch releaseFirstClaim =
                new CountDownLatch(1);

        Future<UUID> firstWorker = executor.submit(
                () -> executeInTransaction(() -> {
                    UUID claimedOrderId = claimOneOrder();

                    firstClaimAcquired.countDown();
                    awaitRelease(releaseFirstClaim);

                    return claimedOrderId;
                })
        );

        try {
            assertThat(
                    firstClaimAcquired.await(
                            10,
                            TimeUnit.SECONDS
                    )
            )
                    .as("first worker should acquire a row lock")
                    .isTrue();

            Future<UUID> secondWorker = executor.submit(
                    () -> executeInTransaction(
                            this::claimOneOrder
                    )
            );

            UUID secondClaimedOrderId =
                    secondWorker.get(
                            10,
                            TimeUnit.SECONDS
                    );

            releaseFirstClaim.countDown();

            UUID firstClaimedOrderId =
                    firstWorker.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertThat(firstClaimedOrderId)
                    .isEqualTo(fixture.oldestOrderId());

            assertThat(secondClaimedOrderId)
                    .isEqualTo(fixture.nextOrderId());

            assertThat(secondClaimedOrderId)
                    .isNotEqualTo(firstClaimedOrderId);
        } finally {
            releaseFirstClaim.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    void shouldExpireEachOrderExactlyOnceAcrossConcurrentProcessors()
            throws Exception {
        Fixture fixture = seedTwoExpiredOrders();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        Future<Integer> firstWorker = executor.submit(
                () -> runExpiryWorker(startBarrier)
        );
        Future<Integer> secondWorker = executor.submit(
                () -> runExpiryWorker(startBarrier)
        );

        try {
            int processedCount =
                    firstWorker.get(20, TimeUnit.SECONDS)
                            + secondWorker.get(20, TimeUnit.SECONDS);

            assertThat(processedCount).isEqualTo(2);
            assertPersistedExactlyOnce(fixture);

            assertThat(
                    expiryBatchProcessor.expireNextBatch(10)
            ).isZero();

            assertPersistedExactlyOnce(fixture);
        } finally {
            shutdownExecutor(executor);
        }
    }

    private int runExpiryWorker(CyclicBarrier startBarrier)
            throws Exception {
        startBarrier.await(10, TimeUnit.SECONDS);

        return expiryBatchProcessor.expireNextBatch(1);
    }

    private void assertPersistedExactlyOnce(Fixture fixture) {
        for (UUID orderId : List.of(
                fixture.oldestOrderId(),
                fixture.nextOrderId()
        )) {
            Integer expiredOrderCount = jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from orders
                    where id = ?
                      and status = 'EXPIRED'
                      and inventory_released_at is not null
                    """,
                    Integer.class,
                    orderId
            );

            Integer historyCount = jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from order_status_history
                    where order_id = ?
                      and from_status = 'PENDING_PAYMENT'
                      and to_status = 'EXPIRED'
                      and actor_type = 'SYSTEM'
                    """,
                    Integer.class,
                    orderId
            );

            assertThat(expiredOrderCount).isEqualTo(1);
            assertThat(historyCount).isEqualTo(1);
        }

        Integer stock = jdbcTemplate.queryForObject(
                "select stock_quantity from products where id = ?",
                Integer.class,
                fixture.productId()
        );

        assertThat(stock).isEqualTo(12);
    }

    private Fixture seedTwoExpiredOrders() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        userIds.add(userId);
        productIds.add(productId);

        insertUser(userId);
        insertProduct(productId);

        UUID oldestOrderId = insertOrder(
                userId,
                productId,
                CUTOFF.minusSeconds(120)
        );

        UUID nextOrderId = insertOrder(
                userId,
                productId,
                CUTOFF.minusSeconds(60)
        );

        return new Fixture(
                oldestOrderId,
                nextOrderId,
                productId
        );
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
                "expiry-worker-" + userId + "@example.com",
                "Expiry Worker Test",
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
                    10, 'ACTIVE',
                    null, null,
                    now(), now()
                )
                """,
                productId,
                "EXPIRY-" + productId,
                "Expiry Test Product",
                "Product used by expiry claim test",
                ORDER_TOTAL
        );
    }

    private UUID insertOrder(
            UUID userId,
            UUID productId,
            Instant expiresAt
    ) {
        UUID orderId = UUID.randomUUID();
        Instant createdAt = expiresAt.minusSeconds(1800);

        orderIds.add(orderId);

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

        jdbcTemplate.update(
                """
                insert into order_items (
                    id, order_id, product_id,
                    product_sku, product_name,
                    product_image_key,
                    unit_price, quantity, line_total,
                    created_at, updated_at
                )
                values (
                    ?, ?, ?,
                    ?, ?,
                    null,
                    ?, 1, ?,
                    ?, ?
                )
                """,
                UUID.randomUUID(),
                orderId,
                productId,
                "EXPIRY-SNAPSHOT-" + productId,
                "Expiry Snapshot Product",
                ORDER_TOTAL,
                ORDER_TOTAL,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        return orderId;
    }

    private UUID claimOneOrder() {
        List<Order> claimedOrders =
                orderRepository.claimExpiredForUpdate(
                        CUTOFF,
                        1
                );

        if (claimedOrders.size() != 1) {
            throw new IllegalStateException(
                    "expected one claimed order but got "
                            + claimedOrders.size()
            );
        }

        return claimedOrders.get(0).getId();
    }

    private <T> T executeInTransaction(
            Supplier<T> callback
    ) {
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        T result = template.execute(
                status -> callback.get()
        );

        return Objects.requireNonNull(
                result,
                "transaction result must not be null"
        );
    }

    private void awaitRelease(CountDownLatch releaseLatch) {
        try {
            boolean released = releaseLatch.await(
                    30,
                    TimeUnit.SECONDS
            );

            if (!released) {
                throw new IllegalStateException(
                        "first claim was not released in time"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "claim worker was interrupted",
                    exception
            );
        }
    }

    private void shutdownExecutor(
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

    private record Fixture(
            UUID oldestOrderId,
            UUID nextOrderId,
            UUID productId
    ) {
    }
}