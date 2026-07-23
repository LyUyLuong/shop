package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;


import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static com.lul.shop.ordering.support.OrderingTestFixtures.createMockOrder;
import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;

@Transactional
class OrderRepositoryImplTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SECOND_PRODUCT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindOrderWithSnapshotItems() {
        insertUser(USER_ID, "order-user@example.com");
        insertProduct(PRODUCT_ID, "LIVE-SKU-001", "Live Product Name", "199000.00", 20);
        insertProduct(SECOND_PRODUCT_ID, "LIVE-SKU-002", "Second Live Product", "50000.00", 15);
        Instant orderPlacedAt = Instant.now()
                .truncatedTo(ChronoUnit.MICROS);

        Order order = createMockOrder(
                USER_ID,
                List.of(
                        OrderItem.create(
                                PRODUCT_ID,
                                "SNAPSHOT-SKU-001",
                                "Snapshot Hoodie",
                                "products/33333333-3333-4333-8333-333333333333/hoodie.jpg",
                                new BigDecimal("199000.00"),
                                2
                        ),
                        OrderItem.create(
                                SECOND_PRODUCT_ID,
                                "SNAPSHOT-SKU-002",
                                "Snapshot Sticker",
                                null,
                                new BigDecimal("50000.00"),
                                1
                        )
                ),
                orderPlacedAt
        );

        Order saved = orderRepository.save(order);

        flushAndClear();

        Order found = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getUserId()).isEqualTo(USER_ID);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(found.getPaymentMode())
                .isEqualTo(com.lul.shop.ordering.domain.OrderPaymentMode.MOCK);
        assertThat(found.getFulfillment()).isEqualTo(fulfillment());
        assertThat(found.getSubtotalAmount())
                .isEqualByComparingTo("448000.00");
        assertThat(found.getShippingFee())
                .isEqualByComparingTo("0.00");
        assertThat(found.getTotalAmount()).isEqualByComparingTo("448000.00");
        assertThat(found.getExpiresAt())
                .isEqualTo(orderPlacedAt.plusSeconds(30 * 60));
        assertThat(found.getInventoryReleasedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();

        assertThat(found.getItems()).hasSize(2);
        assertThat(found.getItems())
                .extracting(OrderItem::getProductSku)
                .containsExactlyInAnyOrder("SNAPSHOT-SKU-001", "SNAPSHOT-SKU-002");

        OrderItem hoodieItem = findItemBySku(found, "SNAPSHOT-SKU-001");

        assertThat(hoodieItem.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(hoodieItem.getProductName()).isEqualTo("Snapshot Hoodie");
        assertThat(hoodieItem.getProductImageKey())
                .isEqualTo("products/33333333-3333-4333-8333-333333333333/hoodie.jpg");
        assertThat(hoodieItem.getUnitPrice()).isEqualByComparingTo("199000.00");
        assertThat(hoodieItem.getQuantity()).isEqualTo(2);
        assertThat(hoodieItem.getLineTotal()).isEqualByComparingTo("398000.00");
        assertThat(hoodieItem.getCreatedAt()).isNotNull();
        assertThat(hoodieItem.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldFindOrderOnlyWhenItBelongsToUser() {
        insertUser(USER_ID, "owner@example.com");
        insertUser(OTHER_USER_ID, "other@example.com");
        insertProduct(PRODUCT_ID, "OWNER-SKU-001", "Owner Product", "100000.00", 10);

        Order saved = orderRepository.save(singleItemOrder(USER_ID, PRODUCT_ID, "OWNER-SNAPSHOT-001"));

        flushAndClear();

        assertThat(orderRepository.findByIdAndUserId(saved.getId(), USER_ID)).isPresent();
        assertThat(orderRepository.findByIdAndUserId(saved.getId(), OTHER_USER_ID)).isEmpty();
    }

    @Test
    void shouldFindOrdersByUserOrderedByCreatedAtDescending() {
        insertUser(USER_ID, "history-user@example.com");
        insertUser(OTHER_USER_ID, "history-other@example.com");
        insertProduct(PRODUCT_ID, "HISTORY-SKU-001", "History Product", "100000.00", 10);

        Order olderOrder = orderRepository.save(singleItemOrder(USER_ID, PRODUCT_ID, "OLDER-SNAPSHOT"));
        Order newerOrder = orderRepository.save(singleItemOrder(USER_ID, PRODUCT_ID, "NEWER-SNAPSHOT"));
        Order otherUserOrder = orderRepository.save(singleItemOrder(OTHER_USER_ID, PRODUCT_ID, "OTHER-SNAPSHOT"));

        flushAndClear();

        updateOrderCreatedAt(olderOrder.getId(), Instant.parse("2026-01-01T00:00:00Z"));
        updateOrderCreatedAt(newerOrder.getId(), Instant.parse("2026-01-02T00:00:00Z"));
        updateOrderCreatedAt(otherUserOrder.getId(), Instant.parse("2026-01-03T00:00:00Z"));

        flushAndClear();

        List<Order> orders = orderRepository.findByUserId(USER_ID);

        assertThat(orders)
                .extracting(Order::getId)
                .containsExactly(newerOrder.getId(), olderOrder.getId());
    }

    @Test
    void shouldUpdateOrderStatusWhenSavedAgain() {
        insertUser(USER_ID, "paid-user@example.com");
        insertProduct(PRODUCT_ID, "PAY-SKU-001", "Payment Product", "100000.00", 10);

        Order saved = orderRepository.save(singleItemOrder(USER_ID, PRODUCT_ID, "PAY-SNAPSHOT"));

        flushAndClear();

        Order order = orderRepository.findById(saved.getId()).orElseThrow();

        order.markPaid();
        orderRepository.save(order);

        flushAndClear();

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("100000.00");
        assertThat(reloaded.getItems()).hasSize(1);
    }

    @Test
    void shouldSearchOrderSummariesByStatusAndCreatedAtDescending() {
        insertUser(USER_ID, "summary-user@example.com");
        insertProduct(PRODUCT_ID, "SUMMARY-SKU-001", "Summary Product", "100000.00", 10);

        Order olderPaidOrder = orderRepository.save(paidSingleItemOrder(USER_ID, PRODUCT_ID, "OLDER-PAID"));
        Order newerPaidOrder = orderRepository.save(paidSingleItemOrder(USER_ID, PRODUCT_ID, "NEWER-PAID"));
        Order pendingOrder = orderRepository.save(singleItemOrder(USER_ID, PRODUCT_ID, "PENDING-ORDER"));

        flushAndClear();

        updateOrderCreatedAt(olderPaidOrder.getId(), Instant.parse("2026-07-01T00:00:00Z"));
        updateOrderCreatedAt(newerPaidOrder.getId(), Instant.parse("2026-07-02T00:00:00Z"));
        updateOrderCreatedAt(pendingOrder.getId(), Instant.parse("2026-07-03T00:00:00Z"));

        flushAndClear();

        PageResult<OrderSummary> result = orderRepository.searchSummaries(
                new OrderSearchCriteria(
                        OrderStatus.PAID,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-07-02T23:59:59Z")
                ),
                new PageQuery(0, 10)
        );

        assertThat(result.content())
                .extracting(OrderSummary::id)
                .containsExactly(newerPaidOrder.getId(), olderPaidOrder.getId());

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();

        assertThat(result.content())
                .extracting(OrderSummary::status)
                .containsOnly(OrderStatus.PAID);

        assertThat(result.content())
                .extracting(OrderSummary::itemCount)
                .containsExactly(1, 1);
    }

    @Test
    void shouldLoadLockedOrderAggregateWithItems() {
        insertUser(USER_ID, "locked-order@example.com");
        insertProduct(
                PRODUCT_ID,
                "LOCKED-SKU-001",
                "Locked Product",
                "100000.00",
                10
        );

        Order saved = orderRepository.save(
                singleItemOrder(
                        USER_ID,
                        PRODUCT_ID,
                        "LOCKED-SNAPSHOT"
                )
        );

        flushAndClear();

        Order lockedOrder = orderRepository
                .findByIdForUpdate(saved.getId())
                .orElseThrow();

        assertThat(lockedOrder.getId()).isEqualTo(saved.getId());
        assertThat(lockedOrder.getUserId()).isEqualTo(USER_ID);
        assertThat(lockedOrder.getItems()).hasSize(1);
        assertThat(lockedOrder.getItems().get(0).getProductId())
                .isEqualTo(PRODUCT_ID);
    }

    @Test
    void shouldLoadLockedOrderOnlyForItsOwner() {
        insertUser(USER_ID, "locked-owner@example.com");
        insertUser(OTHER_USER_ID, "locked-other@example.com");
        insertProduct(
                PRODUCT_ID,
                "LOCKED-OWNER-SKU",
                "Locked Owner Product",
                "100000.00",
                10
        );

        Order saved = orderRepository.save(
                singleItemOrder(
                        USER_ID,
                        PRODUCT_ID,
                        "LOCKED-OWNER-SNAPSHOT"
                )
        );

        flushAndClear();

        Order lockedOrder = orderRepository
                .findByIdAndUserIdForUpdate(
                        saved.getId(),
                        USER_ID
                )
                .orElseThrow();

        assertThat(lockedOrder.getItems()).hasSize(1);
        assertThat(lockedOrder.getItems().get(0).getProductId())
                .isEqualTo(PRODUCT_ID);

        assertThat(
                orderRepository.findByIdAndUserIdForUpdate(
                        saved.getId(),
                        OTHER_USER_ID
                )
        ).isEmpty();
    }

    @Test
    void shouldClaimOnlyExpiredPendingOrdersInDeadlineOrder() {
        insertUser(USER_ID, "expiry-claim@example.com");
        insertProduct(
                PRODUCT_ID,
                "EXPIRY-CLAIM-SKU",
                "Expiry Claim Product",
                "100000.00",
                10
        );

        Instant cutoff =
                Instant.parse("2026-07-17T03:00:00Z");

        Order oldest = orderRepository.save(
                singleItemOrder(
                        USER_ID,
                        PRODUCT_ID,
                        "OLDEST-EXPIRED"
                )
        );

        Order exactDeadline = orderRepository.save(
                singleItemOrder(
                        USER_ID,
                        PRODUCT_ID,
                        "EXACT-DEADLINE"
                )
        );

        Order future = orderRepository.save(
                singleItemOrder(
                        USER_ID,
                        PRODUCT_ID,
                        "FUTURE-DEADLINE"
                )
        );

        Order paid = orderRepository.save(
                paidSingleItemOrder(
                        USER_ID,
                        PRODUCT_ID,
                        "PAID-EXPIRED"
                )
        );

        flushAndClear();

        Instant createdAt = cutoff.minusSeconds(3600);

        updateOrderLifecycle(
                oldest.getId(),
                OrderStatus.PENDING_PAYMENT,
                createdAt,
                cutoff.minusSeconds(300)
        );
        updateOrderLifecycle(
                exactDeadline.getId(),
                OrderStatus.PENDING_PAYMENT,
                createdAt,
                cutoff
        );
        updateOrderLifecycle(
                future.getId(),
                OrderStatus.PENDING_PAYMENT,
                createdAt,
                cutoff.plusSeconds(60)
        );
        updateOrderLifecycle(
                paid.getId(),
                OrderStatus.PAID,
                createdAt,
                cutoff.minusSeconds(600)
        );

        flushAndClear();

        List<Order> limited =
                orderRepository.claimExpiredForUpdate(
                        cutoff,
                        1
                );

        assertThat(limited)
                .extracting(Order::getId)
                .containsExactly(oldest.getId());

        List<Order> claimed =
                orderRepository.claimExpiredForUpdate(
                        cutoff,
                        10
                );

        assertThat(claimed)
                .extracting(Order::getId)
                .containsExactly(
                        oldest.getId(),
                        exactDeadline.getId()
                );

        assertThat(claimed)
                .allSatisfy(order ->
                        assertThat(order.getItems()).hasSize(1)
                );
    }

    @Test
    void shouldRejectNonPositiveExpiryClaimLimit() {
        assertThatThrownBy(
                () -> orderRepository.claimExpiredForUpdate(
                        Instant.now(),
                        0
                )
        )
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(
                        IllegalArgumentException.class
                )
                .hasRootCauseMessage(
                        "limit must be greater than 0"
                );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRequireExistingTransactionForLockedAccess() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(
                () -> orderRepository.findByIdForUpdate(orderId)
        ).isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(
                () -> orderRepository.findByIdAndUserIdForUpdate(
                        orderId,
                        USER_ID
                )
        ).isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(
                () -> orderRepository.claimExpiredForUpdate(
                        Instant.now(),
                        10
                )
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );
    }

    private void updateOrderLifecycle(
            UUID orderId,
            OrderStatus status,
            Instant createdAt,
            Instant expiresAt
    ) {
        jdbcTemplate.update(
                """
                update orders
                set status = ?,
                    created_at = ?,
                    updated_at = ?,
                    expires_at = ?,
                    inventory_released_at = null
                where id = ?
                """,
                status.name(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(expiresAt),
                orderId
        );
    }

    private Order singleItemOrder(UUID userId, UUID productId, String productSku) {
        return createMockOrder(
                userId,
                List.of(OrderItem.create(
                        productId,
                        productSku,
                        "Snapshot Product",
                        null,
                        new BigDecimal("100000.00"),
                        1
                )),
                Instant.now()
        );
    }

    private OrderItem findItemBySku(Order order, String productSku) {
        return order.getItems()
                .stream()
                .filter(item -> item.getProductSku().equals(productSku))
                .findFirst()
                .orElseThrow();
    }

    private void insertUser(UUID userId, String email) {
        jdbcTemplate.update(
                """
                insert into users (id, email, name, password_hash, enabled, created_at, updated_at)
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                email,
                "Test User",
                "password-hash"
        );
    }

    private void insertProduct(UUID productId,
                               String sku,
                               String name,
                               String price,
                               int stockQuantity) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, sku, name, description, price, stock_quantity, status,
                    image_key, image_url, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', null, null, now(), now())
                """,
                productId,
                sku,
                name,
                "Product used by order repository integration test",
                new BigDecimal(price),
                stockQuantity
        );
    }

    private void updateOrderCreatedAt(UUID orderId, Instant createdAt) {
        jdbcTemplate.update(
                "update orders set created_at = ? where id = ?",
                Timestamp.from(createdAt),
                orderId
        );
    }

    private Order paidSingleItemOrder(UUID userId, UUID productId, String productSku) {
        Order order = singleItemOrder(userId, productId, productSku);
        order.markPaid();
        return order;
    }


    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
