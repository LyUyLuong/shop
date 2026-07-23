package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdminOrderDetailResult(
        UUID id,
        UUID userId,
        OrderStatus status,
        OrderPaymentMode paymentMode,
        BigDecimal subtotalAmount,
        BigDecimal shippingFee,
        BigDecimal totalAmount,
        OrderFulfillmentResult fulfillment,
        List<OrderItemResult> items,
        Instant createdAt,
        Instant updatedAt
) {
    public AdminOrderDetailResult {
        id = Objects.requireNonNull(
                id,
                "id must not be null"
        );
        userId = Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        status = Objects.requireNonNull(
                status,
                "status must not be null"
        );
        paymentMode = Objects.requireNonNull(
                paymentMode,
                "paymentMode must not be null"
        );
        subtotalAmount = Objects.requireNonNull(
                subtotalAmount,
                "subtotalAmount must not be null"
        );
        shippingFee = Objects.requireNonNull(
                shippingFee,
                "shippingFee must not be null"
        );
        totalAmount = Objects.requireNonNull(
                totalAmount,
                "totalAmount must not be null"
        );
        items = List.copyOf(
                Objects.requireNonNull(
                        items,
                        "items must not be null"
                )
        );
    }
}