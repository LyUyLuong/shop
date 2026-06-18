package com.lul.shop.cart.application.dto;

import java.util.Objects;
import java.util.UUID;

public record AddCartItemCommand(
        UUID userId,
        UUID productId,
        int quantity
) {

    public AddCartItemCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
    }
}