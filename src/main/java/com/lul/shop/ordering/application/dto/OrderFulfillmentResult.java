package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.ShippingMethod;

public record OrderFulfillmentResult(
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        ShippingMethod shippingMethod
) {
}