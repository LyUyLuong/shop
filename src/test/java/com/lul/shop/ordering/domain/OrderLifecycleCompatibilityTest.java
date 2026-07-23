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

class OrderLifecycleCompatibilityTest {

    private static final UUID ORDER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final UUID PRODUCT_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-16T00:00:00Z");

    @Test
    void shouldTreatReconstructedExpiredOrderAsTerminal() {
        Order order = reconstructedExpiredOrder();

        assertThat(order.canMoveTo(OrderStatus.PAID)).isFalse();

        assertThatThrownBy(() -> order.changeStatus(OrderStatus.PAID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order status cannot move from EXPIRED to PAID");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void shouldKeepGenericPendingToExpiredTransitionDisabled() {
        Order order = createMockOrder(
                USER_ID,
                List.of(orderItem()),
                CREATED_AT
        );

        assertThat(order.canMoveTo(OrderStatus.EXPIRED)).isFalse();

        assertThatThrownBy(() -> order.changeStatus(OrderStatus.EXPIRED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "order status cannot move from PENDING_PAYMENT to EXPIRED"
                );

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void shouldExposePaymentActorForFutureHistoryCompatibility() {
        assertThat(OrderStatusChangeActorType.valueOf("PAYMENT"))
                .isEqualTo(OrderStatusChangeActorType.PAYMENT);
    }

    private static Order reconstructedExpiredOrder() {
        return Order.restore(
                ORDER_ID,
                USER_ID,
                OrderStatus.EXPIRED,
                fulfillment(),
                OrderPaymentMode.MOCK,
                OrderAmounts.calculate(
                        new BigDecimal("100000.00"),
                        BigDecimal.ZERO
                ),
                List.of(orderItem()),
                CREATED_AT.plusSeconds(30 * 60),
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(30 * 60)
        );
    }

    private static OrderItem orderItem() {
        return OrderItem.create(
                PRODUCT_ID,
                "SKU-001",
                "Compatibility Product",
                null,
                new BigDecimal("100000.00"),
                1
        );
    }
}
