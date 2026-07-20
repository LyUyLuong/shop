package com.lul.shop.ordering.presentation.dto.request;

import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record PlaceOrderRequest(
        UUID cartId,

        @PositiveOrZero(message = "cartVersion must be >= 0")
        Long cartVersion
) {
}
