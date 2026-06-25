package com.lul.shop.payment.application;

import com.lul.shop.shared.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

    ORDER_NOT_FOUND("PAYMENT_001", "Order was not found", 404),
    ORDER_NOT_PAYABLE("PAYMENT_002", "Order is not payable", 409),
    PAYMENT_ALREADY_EXISTS("PAYMENT_003", "Payment already exists for order", 409),
    PAYMENT_NOT_FOUND("PAYMENT_004", "Payment was not found", 404);

    private final String code;
    private final String message;
    private final int httpStatus;

    PaymentErrorCode(String code, String message, int httpStatus) {
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