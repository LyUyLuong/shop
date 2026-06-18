package com.lul.shop.cart.presentation.dto.response;

import com.lul.shop.cart.application.dto.CartItemResult;

import java.util.UUID;

public record CartItemResponse(
        UUID id,
        UUID productId,
        int quantity
) {

    public static CartItemResponse from(CartItemResult result) {
        return new CartItemResponse(
                result.id(),
                result.productId(),
                result.quantity()
        );
    }
}