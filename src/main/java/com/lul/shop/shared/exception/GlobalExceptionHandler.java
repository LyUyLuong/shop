package com.lul.shop.shared.exception;

import com.lul.shop.shared.api.ApiResponse;
import com.lul.shop.shared.api.ErrorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();

        log.warn("Business exception: {} - {}", code.getCode(), ex.getMessage());

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorInfo.FieldError> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ErrorInfo.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        CommonErrorCode code = CommonErrorCode.VALIDATION_ERROR;

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage(), details)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        CommonErrorCode code = CommonErrorCode.INVALID_REQUEST;

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), ex.getMessage())));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        CommonErrorCode code = CommonErrorCode.UNAUTHORIZED;

        log.warn("Authentication failed: {}", ex.getMessage());

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage())));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        CommonErrorCode code = CommonErrorCode.FORBIDDEN;

        log.warn("Access denied: {}", ex.getMessage());

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage())));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        CommonErrorCode code = CommonErrorCode.NOT_FOUND;

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        CommonErrorCode code = CommonErrorCode.INTERNAL_ERROR;

        log.error("Unhandled exception", ex);

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        CommonErrorCode code = CommonErrorCode.INVALID_REQUEST;

        String message = "Invalid value for parameter '" + ex.getName()+"'";

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), message)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        CommonErrorCode code = CommonErrorCode.INVALID_REQUEST;

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(
                        code.getCode(),
                        "Uploaded file is too large"
                )));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupport(HttpRequestMethodNotSupportedException ex) {

        CommonErrorCode code = CommonErrorCode.METHOD_NOT_ALLOWED;

        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage())));


    }
}