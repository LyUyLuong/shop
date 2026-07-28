package com.lul.shop.ordering.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCodCollectionTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-27T01:00:00Z");

    @Test
    void shouldCompleteShippedCodOrderThroughDedicatedTransition() {
        Order order = shippedOrder(OrderPaymentMode.COD);

        OrderStatus previousStatus =
                order.completeCodCollection();

        assertThat(previousStatus)
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void shouldKeepCodCompletionOutsideGenericTransition() {
        Order order = shippedOrder(OrderPaymentMode.COD);

        assertThat(order.canMoveTo(OrderStatus.COMPLETED))
                .isFalse();

        assertThatThrownBy(() ->
                order.changeStatus(OrderStatus.COMPLETED)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "order status cannot move from SHIPPED to COMPLETED"
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void shouldRejectCodCollectionForMockOrder() {
        Order order = shippedOrder(OrderPaymentMode.MOCK);

        assertThatThrownBy(order::completeCodCollection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "only COD orders can be completed by collection"
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void shouldRejectCodCollectionBeforeShipment() {
        Order order = createOrder(OrderPaymentMode.COD);

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);

        assertThatThrownBy(order::completeCodCollection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "COD collection requires a SHIPPED order"
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void shouldRejectRepeatedCollectionAtDomainBoundary() {
        Order order = shippedOrder(OrderPaymentMode.COD);

        order.completeCodCollection();

        assertThatThrownBy(order::completeCodCollection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "COD collection requires a SHIPPED order"
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    private static Order shippedOrder(
            OrderPaymentMode paymentMode
    ) {
        Order order = createOrder(paymentMode);

        if (paymentMode == OrderPaymentMode.MOCK) {
            order.markPaid();
        }

        order.changeStatus(OrderStatus.PACKING);
        order.changeStatus(OrderStatus.SHIPPED);

        return order;
    }

    private static Order createOrder(
            OrderPaymentMode paymentMode
    ) {
        return Order.create(
                USER_ID,
                List.of(OrderItem.create(
                        PRODUCT_ID,
                        "COD-SKU-001",
                        "COD Product",
                        null,
                        new BigDecimal("100000.00"),
                        2
                )),
                fulfillment(),
                paymentMode,
                OrderAmounts.calculate(
                        new BigDecimal("200000.00"),
                        new BigDecimal("30000.00")
                ),
                NOW
        );
    }
}