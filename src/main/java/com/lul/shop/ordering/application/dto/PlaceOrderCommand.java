package com.lul.shop.ordering.application.dto;

import java.util.Objects;
import java.util.UUID;

public record PlaceOrderCommand(
        UUID userId,
        UUID cartId,
        long cartVersion,
        String idempotencyKey
) {

    public PlaceOrderCommand {
        Objects.requireNonNull(userId, "userId must not be null");

        Objects.requireNonNull(cartId, "cartId must not be null");

        if (cartVersion < 0) {
            throw new IllegalArgumentException(
                    "cartVersion must not be negative"
            );
        }
    }
}