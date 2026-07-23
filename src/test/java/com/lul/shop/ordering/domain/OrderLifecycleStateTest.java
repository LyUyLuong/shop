package com.lul.shop.ordering.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.lul.shop.ordering.support.OrderingTestFixtures.createMockOrder;
import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;

class OrderLifecycleStateTest {

    private static final UUID ORDER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final UUID PRODUCT_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final Instant NOW =
            Instant.parse("2026-07-16T10:00:00Z");

    private static final Instant DEADLINE =
            Instant.parse("2026-07-16T10:30:00Z");

    @Test
    void shouldCreateOrderWithThirtyMinutePaymentWindow() {
        Order order = pendingOrder();

        assertThat(order.getExpiresAt()).isEqualTo(DEADLINE);
        assertThat(order.getInventoryReleasedAt()).isNull();
        assertThat(order.isInventoryReleased()).isFalse();
    }

    @Test
    void shouldTreatExactDeadlineAsExpired() {
        Order order = pendingOrder();

        assertThat(order.isPayableAt(DEADLINE.minusNanos(1))).isTrue();
        assertThat(order.isExpiredAt(DEADLINE.minusNanos(1))).isFalse();

        assertThat(order.isPayableAt(DEADLINE)).isFalse();
        assertThat(order.isExpiredAt(DEADLINE)).isTrue();
    }

    @Test
    void shouldExpireOnlyAtOrAfterDeadline() {
        Order order = pendingOrder();

        assertThatThrownBy(() -> order.expire(DEADLINE.minusNanos(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order cannot expire before its payment deadline");

        OrderStatus previousStatus = order.expire(DEADLINE);

        assertThat(previousStatus).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void shouldKeepGenericExpiredTransitionUnavailable() {
        Order order = pendingOrder();

        assertThat(order.canMoveTo(OrderStatus.EXPIRED)).isFalse();
        assertThatThrownBy(() -> order.changeStatus(OrderStatus.EXPIRED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldMarkInventoryReleasedOnlyOnceForCancelledOrder() {
        Order order = pendingOrder();
        order.changeStatus(OrderStatus.CANCELLED);

        Instant releasedAt = NOW.plusSeconds(60);
        order.markInventoryReleased(releasedAt);

        assertThat(order.isInventoryReleased()).isTrue();
        assertThat(order.getInventoryReleasedAt()).isEqualTo(releasedAt);

        assertThatThrownBy(() -> order.markInventoryReleased(releasedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("inventory was already released");
    }

    @Test
    void shouldRejectInventoryReleaseForPendingOrder() {
        Order order = pendingOrder();

        assertThatThrownBy(() -> order.markInventoryReleased(NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "inventory can only be released for CANCELLED or EXPIRED orders"
                );
    }

    @Test
    void shouldRejectInvalidReconstructedReleaseMarker() {
        assertThatThrownBy(() -> Order.restore(
                ORDER_ID,
                USER_ID,
                OrderStatus.PAID,
                fulfillment(),
                OrderPaymentMode.MOCK,
                OrderAmounts.calculate(
                        new BigDecimal("100000.00"),
                        BigDecimal.ZERO
                ),
                List.of(orderItem()),
                DEADLINE,
                NOW.plusSeconds(60),
                NOW,
                NOW.plusSeconds(60)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "inventory can only be released for CANCELLED or EXPIRED orders"
                );
    }

    private static Order pendingOrder() {
        return createMockOrder(
                USER_ID,
                List.of(orderItem()),
                NOW
        );
    }

    private static OrderItem orderItem() {
        return OrderItem.create(
                PRODUCT_ID,
                "SKU-001",
                "Lifecycle Product",
                null,
                new BigDecimal("100000.00"),
                1
        );
    }
}
