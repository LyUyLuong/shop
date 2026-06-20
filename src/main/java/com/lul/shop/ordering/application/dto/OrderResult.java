package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResult(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResult> items,
        Instant createdAt,
        Instant updatedAt
) {
}