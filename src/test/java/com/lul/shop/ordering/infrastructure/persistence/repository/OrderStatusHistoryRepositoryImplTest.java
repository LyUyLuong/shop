package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class OrderStatusHistoryRepositoryImplTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_ORDER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindTimelineByOrderIdOrderedByCreatedAtAscending() {
        insertUser(USER_ID, "history-user@example.com");
        insertUser(ADMIN_ID, "history-admin@example.com");
        insertOrder(ORDER_ID, USER_ID);
        insertOrder(OTHER_ORDER_ID, USER_ID);

        OrderStatusHistory second = orderStatusHistoryRepository.save(OrderStatusHistory.recordAdminChange(
                ORDER_ID,
                ADMIN_ID,
                OrderStatus.PACKING,
                OrderStatus.SHIPPED,
                "Package handed over to delivery provider"
        ));

        OrderStatusHistory first = orderStatusHistoryRepository.save(OrderStatusHistory.recordAdminChange(
                ORDER_ID,
                ADMIN_ID,
                OrderStatus.PAID,
                OrderStatus.PACKING,
                "Start packing"
        ));

        OrderStatusHistory otherOrderHistory = orderStatusHistoryRepository.save(OrderStatusHistory.recordAdminChange(
                OTHER_ORDER_ID,
                ADMIN_ID,
                OrderStatus.PAID,
                OrderStatus.PACKING,
                "Other order history"
        ));

        flushAndClear();

        updateHistoryCreatedAt(first.getId(), Instant.parse("2026-07-01T00:00:00Z"));
        updateHistoryCreatedAt(second.getId(), Instant.parse("2026-07-01T01:00:00Z"));
        updateHistoryCreatedAt(otherOrderHistory.getId(), Instant.parse("2026-07-01T02:00:00Z"));

        flushAndClear();

        List<OrderStatusHistory> timeline = orderStatusHistoryRepository.findTimelineByOrderId(ORDER_ID);

        assertThat(timeline)
                .extracting(OrderStatusHistory::getId)
                .containsExactly(first.getId(), second.getId());

        assertThat(timeline.get(0).getOrderId()).isEqualTo(ORDER_ID);
        assertThat(timeline.get(0).getFromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(timeline.get(0).getToStatus()).isEqualTo(OrderStatus.PACKING);
        assertThat(timeline.get(0).getActorType()).isEqualTo(OrderStatusChangeActorType.ADMIN);
        assertThat(timeline.get(0).getActorUserId()).isEqualTo(ADMIN_ID);
        assertThat(timeline.get(0).getReason()).isEqualTo("Start packing");
        assertThat(timeline.get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));


        assertThat(timeline.get(1).getOrderId()).isEqualTo(ORDER_ID);
        assertThat(timeline.get(1).getFromStatus()).isEqualTo(OrderStatus.PACKING);
        assertThat(timeline.get(1).getToStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(timeline.get(1).getActorType()).isEqualTo(OrderStatusChangeActorType.ADMIN);
        assertThat(timeline.get(1).getActorUserId()).isEqualTo(ADMIN_ID);
        assertThat(timeline.get(1).getReason()).isEqualTo("Package handed over to delivery provider");
        assertThat(timeline.get(1).getCreatedAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));

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

    private void insertOrder(UUID orderId, UUID userId) {
        jdbcTemplate.update(
                """
                insert into orders (id, user_id, status, total_amount, created_at, updated_at)
                values (?, ?, 'PAID', 100000.00, now(), now())
                """,
                orderId,
                userId
        );
    }

    private void updateHistoryCreatedAt(UUID historyId, Instant createdAt) {
        jdbcTemplate.update(
                "update order_status_history set created_at = ? where id = ?",
                Timestamp.from(createdAt),
                historyId
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}