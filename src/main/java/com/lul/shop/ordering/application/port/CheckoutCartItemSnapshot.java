package com.lul.shop.ordering.application.port;

import java.util.Objects;
import java.util.UUID;

public record CheckoutCartItemSnapshot(
        UUID productId,
        int quantity
) {

    public CheckoutCartItemSnapshot {
        Objects.requireNonNull(productId, "productId must not be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
    }
}