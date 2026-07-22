package com.lul.shop.catalog.application;

import com.lul.shop.shared.exception.ErrorCode;

public enum CatalogErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND("CATALOG_001", "Product was not found", 404),
    PRODUCT_SKU_ALREADY_EXISTS("CATALOG_002", "Product SKU already exists", 409),
    PRODUCT_NOT_ACTIVE("CATALOG_003", "Product is not active", 409),
    INVALID_PRODUCT_IMAGE("CATALOG_004", "Invalid product image", 400),
    PRODUCT_IMAGE_UPLOAD_FAILED("CATALOG_005", "Product image upload failed", 502),
    PRODUCT_IMAGE_NOT_FOUND("CATALOG_006", "Product image was not found", 404),
    PRODUCT_IMAGE_READ_FAILED("CATALOG_007", "Product image could not be loaded", 502),
    PRODUCT_VERSION_CONFLICT("CATALOG_008", "Product was modified by another operation", 409);

    private final String code;
    private final String message;
    private final int httpStatus;

    CatalogErrorCode(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public int getHttpStatus() {
        return httpStatus;
    }
}
