package com.lul.shop.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerOrderSummary(
        UUID id,
        OrderStatus status,
        OrderPaymentMode paymentMode,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
    public CustomerOrderSummary {
        id = Objects.requireNonNull(id, "id must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        paymentMode = Objects.requireNonNull(
                paymentMode,
                "paymentMode must not be null"
        );
        totalAmount = Objects.requireNonNull(
                totalAmount,
                "totalAmount must not be null"
        );
        createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        updatedAt = Objects.requireNonNull(
                updatedAt,
                "updatedAt must not be null"
        );

        if (itemCount < 0) {
            throw new IllegalArgumentException(
                    "itemCount must be >= 0"
            );
        }
    }
}