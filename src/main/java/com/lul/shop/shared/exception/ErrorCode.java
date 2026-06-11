package com.lul.shop.shared.exception;

public interface ErrorCode {

    String getCode();

    String getMessage();

    int getHttpStatus();
}