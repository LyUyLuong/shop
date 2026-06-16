package com.lul.shop.catalog.application.dto;

import java.math.BigDecimal;

public record CreateProductCommand(
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity
) {
}