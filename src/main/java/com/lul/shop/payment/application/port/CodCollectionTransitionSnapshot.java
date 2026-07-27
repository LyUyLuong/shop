package com.lul.shop.payment.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record CodCollectionTransitionSnapshot(
        UUID orderId,
        UUID userId,
        BigDecimal totalAmount,
        Outcome outcome
) {
    public CodCollectionTransitionSnapshot {
        orderId = Objects.requireNonNull(orderId);
        userId = Objects.requireNonNull(userId);
        totalAmount = Objects.requireNonNull(totalAmount);
        outcome = Objects.requireNonNull(outcome);

        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException(
                    "totalAmount must be >= 0"
            );
        }
    }

    public enum Outcome {
        NEWLY_COLLECTED,
        ALREADY_COLLECTED
    }
}