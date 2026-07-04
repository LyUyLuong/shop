package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.AdminOrderSummaryResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminOrderSummaryResponse(
        UUID id,
        UUID userId,
        String status,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static AdminOrderSummaryResponse from(AdminOrderSummaryResult result) {
        return new AdminOrderSummaryResponse(
                result.id(),
                result.userId(),
                result.status().name(),
                result.totalAmount(),
                result.itemCount(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}