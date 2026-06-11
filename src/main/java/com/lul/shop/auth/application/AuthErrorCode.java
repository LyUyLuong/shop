package com.lul.shop.auth.application;

import com.lul.shop.shared.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXISTS("AUTH_001", "Email already exists", 409),
    INVALID_CREDENTIALS("AUTH_002", "Invalid email or password", 401),
    USER_DISABLED("AUTH_003", "User account is disabled", 403);

    private final String code;
    private final String message;
    private final int httpStatus;

    AuthErrorCode(String code, String message, int httpStatus) {
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