package com.lul.shop.ordering.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCheckoutModeTest {

    private static final UUID ORDER_ID = UUID.fromString(
            "81111111-1111-4111-8111-111111111111"
    );

    private static final UUID USER_ID = UUID.fromString(
            "82222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "83333333-3333-4333-8333-333333333333"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-23T00:00:00Z");

    private static final BigDecimal SUBTOTAL =
            new BigDecimal("200000.00");

    private static final BigDecimal SHIPPING_FEE =
            new BigDecimal("30000.00");

    @Test
    void shouldCreateMockOrderWithPaymentDeadline() {
        Order order = createOrder(OrderPaymentMode.MOCK);

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getPaymentMode())
                .isEqualTo(OrderPaymentMode.MOCK);
        assertThat(order.getFulfillment())
                .isEqualTo(fulfillment());
        assertThat(order.getSubtotalAmount())
                .isEqualByComparingTo(SUBTOTAL);
        assertThat(order.getShippingFee())
                .isEqualByComparingTo(SHIPPING_FEE);
        assertThat(order.getTotalAmount())
                .isEqualByComparingTo("230000.00");
        assertThat(order.getExpiresAt())
                .isEqualTo(NOW.plusSeconds(30 * 60));
    }

    @Test
    void shouldCreateCodOrderConfirmedWithoutPaymentDeadline() {
        Order order = createOrder(OrderPaymentMode.COD);

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentMode())
                .isEqualTo(OrderPaymentMode.COD);
        assertThat(order.getExpiresAt()).isNull();
        assertThat(order.isPayableAt(NOW)).isFalse();
        assertThat(order.isExpiredAt(NOW.plusSeconds(3600)))
                .isFalse();
    }

    @Test
    void shouldKeepCodOutsideMockPaymentExpiryAndCompletionPaths() {
        Order order = createOrder(OrderPaymentMode.COD);

        assertThatThrownBy(order::markPaid)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "order status cannot move from CONFIRMED to PAID"
                );

        assertThatThrownBy(() -> order.expire(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order cannot expire from CONFIRMED");

        order.changeStatus(OrderStatus.PACKING);
        order.changeStatus(OrderStatus.SHIPPED);

        assertThat(order.canMoveTo(OrderStatus.COMPLETED))
                .isFalse();
        assertThatThrownBy(() ->
                order.changeStatus(OrderStatus.COMPLETED)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "order status cannot move from SHIPPED to COMPLETED"
                );
    }

    @Test
    void shouldRestoreRollbackCompatibleLegacyMockOrder() {
        Order order = Order.restore(
                ORDER_ID,
                USER_ID,
                OrderStatus.PAID,
                null,
                OrderPaymentMode.MOCK,
                OrderAmounts.calculate(
                        SUBTOTAL,
                        BigDecimal.ZERO
                ),
                List.of(orderItem()),
                NOW.plusSeconds(30 * 60),
                null,
                NOW,
                NOW
        );

        assertThat(order.getFulfillment()).isNull();
        assertThat(order.getPaymentMode())
                .isEqualTo(OrderPaymentMode.MOCK);
        assertThat(order.getShippingFee())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void shouldRejectMissingFulfillmentForNonLegacyShape() {
        assertThatThrownBy(() -> Order.restore(
                ORDER_ID,
                USER_ID,
                OrderStatus.CONFIRMED,
                null,
                OrderPaymentMode.COD,
                OrderAmounts.calculate(
                        SUBTOTAL,
                        SHIPPING_FEE
                ),
                List.of(orderItem()),
                null,
                null,
                NOW,
                NOW
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "only legacy MOCK orders may omit fulfillment"
                );
    }

    private Order createOrder(OrderPaymentMode paymentMode) {
        return Order.create(
                USER_ID,
                List.of(orderItem()),
                fulfillment(),
                paymentMode,
                OrderAmounts.calculate(
                        SUBTOTAL,
                        SHIPPING_FEE
                ),
                NOW
        );
    }

    private OrderItem orderItem() {
        return OrderItem.create(
                PRODUCT_ID,
                "CHECKOUT-SKU-001",
                "Checkout Product",
                null,
                new BigDecimal("100000.00"),
                2
        );
    }
}
