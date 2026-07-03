package com.lul.shop.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OrderSummary(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
    public OrderSummary {
        id = Objects.requireNonNull(id, "id must not be null");
        userId = Objects.requireNonNull(userId, "userId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        totalAmount = Objects.requireNonNull(totalAmount, "totalAmount must not be null");

        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must be >= 0");
        }
    }
}