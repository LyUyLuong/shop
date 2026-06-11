package com.lul.shop.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorInfo error,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<T>(true,data,null,Instant.now());
    }

    public static ApiResponse<Void> ok(){
        return new ApiResponse<>(true, null, null, Instant.now());
    }


    public static ApiResponse<Void> error(ErrorInfo error) {
        return new ApiResponse<>(false, null, error, Instant.now());
    }

}
