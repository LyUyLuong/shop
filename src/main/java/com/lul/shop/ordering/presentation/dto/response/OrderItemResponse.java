package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.OrderItemResult;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {

    public static OrderItemResponse from(OrderItemResult result) {
        return new OrderItemResponse(
                result.id(),
                result.productId(),
                result.productSku(),
                result.productName(),
                result.unitPrice(),
                result.quantity(),
                result.lineTotal()
        );
    }
}