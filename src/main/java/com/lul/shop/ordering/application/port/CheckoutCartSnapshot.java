package com.lul.shop.ordering.application.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CheckoutCartSnapshot(
        UUID id,
        UUID userId,
        long version,
        List<CheckoutCartItemSnapshot> items
) {

    public CheckoutCartSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        if (version < 0) {
            throw new IllegalArgumentException(
                    "version must not be negative"
            );
        }

        items = List.copyOf(
                items == null ? List.of() : items
        );
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}