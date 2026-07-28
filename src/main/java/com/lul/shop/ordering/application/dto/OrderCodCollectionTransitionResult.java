package com.lul.shop.ordering.application.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record OrderCodCollectionTransitionResult(
        UUID orderId,
        UUID userId,
        BigDecimal totalAmount,
        Outcome outcome
) {
    public OrderCodCollectionTransitionResult {
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