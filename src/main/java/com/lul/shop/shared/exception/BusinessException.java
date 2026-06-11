package com.lul.shop.shared.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(buildMessage(errorCode, detail));
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static String buildMessage(ErrorCode errorCode, String detail) {
        if (detail == null || detail.isBlank()) {
            return errorCode.getMessage();
        }
        return errorCode.getMessage() + ": " + detail;
    }
}