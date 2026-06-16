package com.lul.shop.catalog.application.dto;

import com.lul.shop.catalog.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResult(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        ProductStatus status,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}