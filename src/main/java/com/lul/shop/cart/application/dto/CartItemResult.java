package com.lul.shop.cart.application.dto;

import java.util.UUID;

public record CartItemResult(
        UUID id,
        UUID productId,
        int quantity
) {
}
