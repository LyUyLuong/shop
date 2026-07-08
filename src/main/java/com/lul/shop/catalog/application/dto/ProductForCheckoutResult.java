package com.lul.shop.catalog.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductForCheckoutResult(
        UUID id,
        String sku,
        String name,
        BigDecimal price,
        String imageKey
) {
}