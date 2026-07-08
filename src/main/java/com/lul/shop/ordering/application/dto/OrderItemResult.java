package com.lul.shop.ordering.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResult(
        UUID id,
        UUID productId,
        String productSku,
        String productName,
        String productImageKey,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {
}