package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AdminOrderSummaryResult(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
    public AdminOrderSummaryResult {
        id = Objects.requireNonNull(id, "id must not be null");
        userId = Objects.requireNonNull(userId, "userId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        totalAmount = Objects.requireNonNull(totalAmount, "totalAmount must not be null");
    }
}