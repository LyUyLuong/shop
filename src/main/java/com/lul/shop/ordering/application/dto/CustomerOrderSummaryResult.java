package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerOrderSummaryResult(
        UUID id,
        OrderStatus status,
        OrderPaymentMode paymentMode,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
}