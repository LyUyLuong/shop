package com.lul.shop.cart.application.dto;

import java.util.Objects;
import java.util.UUID;

public record UpdateCartItemCommand(
        UUID userId,
        UUID itemId,
        int quantity
) {

    public UpdateCartItemCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
    }
}