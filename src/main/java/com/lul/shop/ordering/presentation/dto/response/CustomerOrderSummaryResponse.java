package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.CustomerOrderSummaryResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerOrderSummaryResponse(
        UUID id,
        String status,
        String paymentMode,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomerOrderSummaryResponse from(
            CustomerOrderSummaryResult result
    ) {
        return new CustomerOrderSummaryResponse(
                result.id(),
                result.status().name(),
                result.paymentMode().name(),
                result.totalAmount(),
                result.itemCount(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}