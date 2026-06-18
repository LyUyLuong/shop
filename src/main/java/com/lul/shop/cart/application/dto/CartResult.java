package com.lul.shop.cart.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResult(
        UUID id,
        UUID userId,
        List<CartItemResult> items,
        Instant createdAt,
        Instant updatedAt
) {
}
