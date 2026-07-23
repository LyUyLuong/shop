package com.lul.shop.ordering.presentation.dto.request;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.ShippingMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PlaceOrderRequest(
        @NotNull UUID cartId,
        @NotNull @PositiveOrZero Long cartVersion,
        @NotBlank @Size(min = 2, max = 100)
        String recipientName,
        @NotBlank @Size(max = 30)
        String recipientPhone,
        @NotBlank @Size(min = 10, max = 500)
        String shippingAddress,
        @NotNull ShippingMethod shippingMethod,
        @NotNull OrderPaymentMode paymentMode
) {
}