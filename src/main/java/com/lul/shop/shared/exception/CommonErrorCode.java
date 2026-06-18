package com.lul.shop.shared.exception;

public enum CommonErrorCode implements ErrorCode {

    VALIDATION_ERROR("COMMON_001", "Invalid request data", 400),
    UNAUTHORIZED("COMMON_002", "Authentication is required", 401),
    FORBIDDEN("COMMON_003", "Access is denied", 403),
    NOT_FOUND("COMMON_004", "Resource was not found", 404),
    INVALID_REQUEST("COMMON_005", "Invalid request", 400),
    METHOD_NOT_ALLOWED("COMMON_006", "Request method is not supported", 405),

    INTERNAL_ERROR("COMMON_999", "Internal server error", 500);

    private final String code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(String code, String message, int httpStatus) {
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