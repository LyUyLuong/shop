package com.lul.shop.shared.exception;

import com.lul.shop.shared.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerContractTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void shouldMapIllegalArgumentToInvalidRequest400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleIllegalArgument(
                        new IllegalArgumentException(
                                "size must be >= 1"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().success())
                .isFalse();

        assertThat(response.getBody().error().code())
                .isEqualTo("COMMON_005");

        assertThat(response.getBody().error().message())
                .isEqualTo("size must be >= 1");
    }

    @Test
    void shouldPreserveBusinessErrorCodeStatusAndDetail() {
        BusinessException exception =
                new BusinessException(
                        TestErrorCode.TEST_BUSINESS,
                        "keyword too long"
                );

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(exception);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(response.getBody().success())
                .isFalse();

        assertThat(response.getBody().error().code())
                .isEqualTo("TEST_001");

        assertThat(response.getBody().error().message())
                .isEqualTo(
                        "Test business failure: keyword too long"
                );
    }

    @Test
    void shouldMapTypeMismatchToInvalidRequest400()
            throws NoSuchMethodException {
        Method handlerMethod =
                GlobalExceptionHandler.class
                        .getDeclaredMethod(
                                "handleTypeMismatch",
                                MethodArgumentTypeMismatchException.class
                        );

        MethodArgumentTypeMismatchException exception =
                new MethodArgumentTypeMismatchException(
                        "abc",
                        Integer.class,
                        "page",
                        new MethodParameter(handlerMethod, 0),
                        null
                );

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleTypeMismatch(exception);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody().error().code())
                .isEqualTo("COMMON_005");

        assertThat(response.getBody().error().message())
                .isEqualTo(
                        "Invalid value for parameter 'page'"
                );
    }

    private enum TestErrorCode implements ErrorCode {

        TEST_BUSINESS("TEST_001", "Test business failure", 422);

        private final String code;
        private final String message;
        private final int httpStatus;

        TestErrorCode(String code, String message, int httpStatus) {
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
}