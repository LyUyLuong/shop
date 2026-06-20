package com.lul.shop.ordering.application.port;

import java.util.List;
import java.util.UUID;

public record CartSnapshot(
        UUID id,
        UUID userId,
        List<CartItemSnapshot> items
) {

    public CartSnapshot {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}