package com.lul.shop.ordering.application.port;

import java.util.List;
import java.util.UUID;

public record CheckoutCartSnapshot(
        UUID id,
        UUID userId,
        List<CheckoutCartItemSnapshot> items
) {

    public CheckoutCartSnapshot {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}