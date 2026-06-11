package com.lul.shop.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorInfo(
        String code,
        String message,
        List<FieldError> details
) {

    public static ErrorInfo of(String code, String message) {
        return new ErrorInfo(code, message, null);
    }

    public static ErrorInfo of(String code, String message, List<FieldError> details) {
        return new ErrorInfo(code, message, details);
    }




    public record FieldError(
            String field,
            String message
    ) {
    }

}
