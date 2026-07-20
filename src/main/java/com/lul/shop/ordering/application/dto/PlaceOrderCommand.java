package com.lul.shop.ordering.application.dto;

import java.util.Objects;
import java.util.UUID;

public record PlaceOrderCommand(
        UUID userId,
        UUID cartId,
        Long cartVersion,
        String idempotencyKey
) {

    public PlaceOrderCommand {
        Objects.requireNonNull(userId, "userId must not be null");
    }

    public PlaceOrderCommand(UUID userId) {
        this(userId, null, null, null);
    }
}
