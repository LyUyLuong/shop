package com.lul.shop.payment.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PayableOrderSnapshot(
        UUID orderId,
        UUID userId,
        BigDecimal totalAmount,
        boolean payable
) {
    public PayableOrderSnapshot {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");

        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException("totalAmount must be >= 0");
        }
    }
}