package com.lul.shop.catalog.application.dto;

public record StoredProductImage(
        String imageKey,
        String imageUrl
) {

    public StoredProductImage {
        imageKey = requireNonBlank(imageKey, "imageKey");
        imageUrl = requireNonBlank(imageUrl, "imageUrl");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }
}