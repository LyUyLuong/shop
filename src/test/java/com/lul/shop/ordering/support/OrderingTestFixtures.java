package com.lul.shop.ordering.support;

import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.domain.FulfillmentSnapshot;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderAmounts;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.ShippingMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderingTestFixtures {

    private OrderingTestFixtures() {
    }

    public static FulfillmentSnapshot fulfillment() {
        return new FulfillmentSnapshot(
                "Nguyen Van A",
                "+84901234567",
                "123 Nguyen Trai, Ho Chi Minh City",
                ShippingMethod.STANDARD
        );
    }

    public static Order createMockOrder(
            UUID userId,
            List<OrderItem> items,
            Instant now
    ) {
        BigDecimal subtotal = items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );

        return Order.create(
                userId,
                items,
                fulfillment(),
                OrderPaymentMode.MOCK,
                OrderAmounts.calculate(
                        subtotal,
                        BigDecimal.ZERO
                ),
                now
        );
    }

    public static PlaceOrderCommand mockPlaceOrderCommand(
            UUID userId,
            UUID cartId,
            long cartVersion,
            String idempotencyKey
    ) {
        return new PlaceOrderCommand(
                userId,
                cartId,
                cartVersion,
                idempotencyKey,
                fulfillment(),
                OrderPaymentMode.MOCK
        );
    }
}