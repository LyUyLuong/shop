package com.lul.shop.payment.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentProviderRequest(
        UUID paymentId,
        UUID orderId,
        UUID userId,
        BigDecimal amount,
        Instant requestedAt
) {

    public PaymentProviderRequest {
        paymentId = Objects.requireNonNull(paymentId);
        orderId = Objects.requireNonNull(orderId);
        userId = Objects.requireNonNull(userId);
        amount = Objects.requireNonNull(amount);
        requestedAt = Objects.requireNonNull(requestedAt);

        if (amount.signum() < 0) {
            throw new IllegalArgumentException(
                    "amount must be >= 0"
            );
        }
    }
}