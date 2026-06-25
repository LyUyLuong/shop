package com.lul.shop.cart.application.port;

import java.util.UUID;

public record ProductAvailabilitySnapshot(
        UUID productId,
        int stockQuantity
) {
}