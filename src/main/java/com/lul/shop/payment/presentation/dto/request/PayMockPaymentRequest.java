package com.lul.shop.payment.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PayMockPaymentRequest(
        @NotNull(message = "orderId must not be null")
        UUID orderId
) {
}