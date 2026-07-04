package com.lul.shop.catalog.domain;

import java.math.BigDecimal;

public record ProductSearchCriteria(
        String keyword,
        ProductStatus status,
        BigDecimal minPrice,
        BigDecimal maxPrice
) {

    public ProductSearchCriteria {
        keyword = normalizeKeyword(keyword);
        validatePrice("minPrice", minPrice);
        validatePrice("maxPrice", maxPrice);

        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must be <= maxPrice");
        }

    }


    public static ProductSearchCriteria activeOnly(String keyword) {
        return new ProductSearchCriteria(keyword, ProductStatus.ACTIVE, null, null);
    }


    public static ProductSearchCriteria empty() {
        return new ProductSearchCriteria(null, null, null, null);
    }


    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }

        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }


    private static void validatePrice(String fieldName, BigDecimal value) {
        if(value != null && value.signum() <0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
    }

    public static ProductSearchCriteria withStatus(String keyword, ProductStatus status) {
        return new ProductSearchCriteria(keyword, status, null, null);
    }

}
