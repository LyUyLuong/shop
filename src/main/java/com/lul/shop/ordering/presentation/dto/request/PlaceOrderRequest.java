package com.lul.shop.ordering.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record PlaceOrderRequest(
        @NotNull(message = "cartId is required")
        UUID cartId,

        @NotNull(message = "cartVersion is required")
        @PositiveOrZero(message = "cartVersion must be >= 0")
        Long cartVersion
) {
}