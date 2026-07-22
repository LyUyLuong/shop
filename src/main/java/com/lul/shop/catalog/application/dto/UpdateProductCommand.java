package com.lul.shop.catalog.application.dto;

import java.math.BigDecimal;

public record UpdateProductCommand(
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        long expectedVersion
) {

    public UpdateProductCommand {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }
    }
}
