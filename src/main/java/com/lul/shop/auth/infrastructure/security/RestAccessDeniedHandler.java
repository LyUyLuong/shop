package com.lul.shop.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.shared.api.ApiResponse;
import com.lul.shop.shared.api.ErrorInfo;
import com.lul.shop.shared.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        CommonErrorCode code = CommonErrorCode.FORBIDDEN;

        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage()))
        );
    }
}