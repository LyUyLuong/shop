package com.lul.shop.ordering.application;

import com.lul.shop.shared.exception.ErrorCode;

public enum OrderingErrorCode implements ErrorCode {

    CART_EMPTY("ORDERING_001", "Cart is empty", 409),
    PRODUCT_NOT_AVAILABLE("ORDERING_002", "Product is not available for ordering", 409),
    INSUFFICIENT_STOCK("ORDERING_003", "Product stock is not enough", 409),
    ORDER_NOT_FOUND("ORDERING_004", "Order was not found", 404);

    private final String code;
    private final String message;
    private final int httpStatus;

    OrderingErrorCode(String code, String message, int httpStatus) {
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