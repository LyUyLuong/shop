package com.lul.shop.payment.application.dto;

import java.util.Objects;
import java.util.UUID;

public record PayOrderCommand(
        UUID userId,
        UUID orderId,
        String idempotencyKey
) {

    public PayOrderCommand {
        userId = Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        orderId = Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );
        idempotencyKey = Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null"
        );
    }
}