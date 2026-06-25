package com.lul.shop.ordering.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record CheckoutProductSnapshot(
        UUID id,
        String sku,
        String name,
        BigDecimal price
) {

    public CheckoutProductSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(price, "price must not be null");

        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must be >= 0");
        }

        sku = sku.trim();
        name = name.trim();
    }
}