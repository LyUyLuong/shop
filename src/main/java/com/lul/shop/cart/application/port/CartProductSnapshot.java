package com.lul.shop.cart.application.port;

import java.util.UUID;

public record CartProductSnapshot(
        UUID productId,
        int stockQuantity
) {
}