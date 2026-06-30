package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

        Order order = Order.create(
                USER_ID,
                List.of(
                        OrderItem.create(
                                PRODUCT_ID,
                                "SNAPSHOT-SKU-001",
                                "Snapshot Hoodie",
                                new BigDecimal("199000.00"),
                                2
                        ),
                        OrderItem.create(
                                SECOND_PRODUCT_ID,
                                "SNAPSHOT-SKU-002",
                                "Snapshot Sticker",
                                new BigDecimal("50000.00"),
                                1
                        )
                )
        );

        Order saved = orderRepository.save(order);

        flushAndClear();

        Order found = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getUserId()).isEqualTo(USER_ID);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(found.getTotalAmount()).isEqualByComparingTo("448000.00");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();

        assertThat(found.getItems()).hasSize(2);
        assertThat(found.getItems())
                .extracting(OrderItem::getProductSku)
                .containsExactlyInAnyOrder("SNAPSHOT-SKU-001", "SNAPSHOT-SKU-002");

        OrderItem hoodieItem = findItemBySku(found, "SNAPSHOT-SKU-001");

        assertThat(hoodieItem.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(hoodieItem.getProductName()).isEqualTo("Snapshot Hoodie");
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

    private Order singleItemOrder(UUID userId, UUID productId, String productSku) {
        return Order.create(
                userId,
                List.of(OrderItem.create(
                        productId,
                        productSku,
                        "Snapshot Product",
                        new BigDecimal("100000.00"),
                        1
                ))
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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}