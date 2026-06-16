package com.lul.shop.catalog.application.dto;

import java.io.InputStream;

public record UploadProductImageCommand(
        String originalFilename,
        String contentType,
        long size,
        InputStream content
) {
}