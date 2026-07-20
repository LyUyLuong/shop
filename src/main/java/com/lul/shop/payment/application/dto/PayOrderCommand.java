package com.lul.shop.payment.application.dto;

import java.util.Objects;
import java.util.UUID;

public record PayOrderCommand(
        UUID userId,
        UUID orderId,
        String idempotencyKey
) {

    public PayOrderCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
    }

    public PayOrderCommand(UUID userId, UUID orderId) {
        this(userId, orderId, null);
    }
}
