package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.OrderFulfillmentResult;

public record OrderFulfillmentResponse(
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        String shippingMethod
) {
    public static OrderFulfillmentResponse from(
            OrderFulfillmentResult result
    ) {
        if (result == null) {
            return null;
        }

        return new OrderFulfillmentResponse(
                result.recipientName(),
                result.recipientPhone(),
                result.shippingAddress(),
                result.shippingMethod().name()
        );
    }
}