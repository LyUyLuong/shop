package com.lul.shop.catalog.application.dto;

import java.math.BigDecimal;

public record UpdateProductCommand(
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        Long expectedVersion
) {

    public UpdateProductCommand(
            String sku,
            String name,
            String description,
            BigDecimal price,
            int stockQuantity
    ) {
        this(
                sku,
                name,
                description,
                price,
                stockQuantity,
                null
        );
    }
}
