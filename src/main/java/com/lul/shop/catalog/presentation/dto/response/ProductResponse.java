package com.lul.shop.catalog.presentation.dto.response;

import com.lul.shop.catalog.application.dto.ProductResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        long version,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        String status,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProductResponse from(ProductResult result) {
        return new ProductResponse(
                result.id(),
                result.version(),
                result.sku(),
                result.name(),
                result.description(),
                result.price(),
                result.stockQuantity(),
                result.status().name(),
                result.imageUrl(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}