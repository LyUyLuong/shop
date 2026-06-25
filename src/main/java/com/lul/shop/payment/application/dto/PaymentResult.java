package com.lul.shop.payment.application.dto;

import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResult(
        UUID id,
        UUID orderId,
        UUID userId,
        PaymentMethod method,
        PaymentStatus status,
        BigDecimal amount,
        Instant paidAt,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}