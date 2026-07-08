package com.lul.shop.ordering.application.dto;

import java.io.InputStream;
import java.util.Objects;

public record OrderItemImageContent(
        InputStream content,
        String contentType,
        long contentLength
) {

    public OrderItemImageContent {
        Objects.requireNonNull(content, "content must not be null");

        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        } else {
            contentType = contentType.trim();
        }
    }
}