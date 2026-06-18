package com.lul.shop.cart.presentation.dto.response;

import com.lul.shop.cart.application.dto.CartResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        UUID userId,
        List<CartItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {

    public static CartResponse from(CartResult result) {
        return new CartResponse(
                result.id(),
                result.userId(),
                result.items()
                        .stream()
                        .map(CartItemResponse::from)
                        .toList(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}