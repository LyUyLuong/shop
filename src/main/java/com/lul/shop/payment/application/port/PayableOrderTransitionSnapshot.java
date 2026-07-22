package com.lul.shop.payment.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PayableOrderTransitionSnapshot(
        UUID orderId,
        UUID userId,
        BigDecimal totalAmount,
        Outcome outcome
) {

    public PayableOrderTransitionSnapshot {
        orderId = Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );
        userId = Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        totalAmount = Objects.requireNonNull(
                totalAmount,
                "totalAmount must not be null"
        );
        outcome = Objects.requireNonNull(
                outcome,
                "outcome must not be null"
        );

        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException(
                    "totalAmount must be >= 0"
            );
        }
    }

    public enum Outcome {
        NEWLY_PAID,
        ALREADY_PAID
    }
}