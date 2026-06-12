package com.lul.shop.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.shared.api.ApiResponse;
import com.lul.shop.shared.api.ErrorInfo;
import com.lul.shop.shared.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        CommonErrorCode code = CommonErrorCode.UNAUTHORIZED;

        response.setStatus(code.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(ErrorInfo.of(code.getCode(), code.getMessage()))
        );
    }
}