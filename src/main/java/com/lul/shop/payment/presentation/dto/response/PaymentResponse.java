package com.lul.shop.payment.presentation.dto.response;

import com.lul.shop.payment.application.dto.PaymentResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        String method,
        String status,
        BigDecimal amount,
        Instant paidAt,
        String failureReason
) {

    public static PaymentResponse from(PaymentResult result) {
        return new PaymentResponse(
                result.id(),
                result.orderId(),
                result.method().name(),
                result.status().name(),
                result.amount(),
                result.paidAt(),
                result.failureReason()
        );
    }
}