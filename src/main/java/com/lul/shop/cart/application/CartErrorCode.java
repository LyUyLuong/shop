package com.lul.shop.cart.application;

import com.lul.shop.shared.exception.ErrorCode;

public enum CartErrorCode implements ErrorCode {

    CART_ITEM_NOT_FOUND("CART_001", "Cart item was not found", 404),
    PRODUCT_NOT_AVAILABLE("CART_002", "Product is not available for cart", 409),
    CART_NOT_FOUND("CART_003", "Cart was not found", 404),
    INSUFFICIENT_STOCK("CART_004", "Product stock is not enough", 409);


    private final String code;
    private final String message;
    private final int httpStatus;

    CartErrorCode(String code, String message, int httpStatus) {
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