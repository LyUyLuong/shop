package com.lul.shop.catalog.application.dto;

import java.io.InputStream;
import java.util.Objects;

public record ProductImageContent(
        InputStream content,
        String contentType,
        long contentLength
) {

    public ProductImageContent {
        Objects.requireNonNull(content, "content must not be null");

        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        } else {
            contentType = contentType.trim();
        }
    }
}