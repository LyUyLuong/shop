package com.lul.shop.auth.presentation.dto.response;

import com.lul.shop.auth.application.dto.AuthResult;
import com.lul.shop.auth.domain.UserRole;

import java.util.Set;
import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String email,
        String name,
        Set<UserRole> roles,
        String accessToken,
        String tokenType
) {

    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(
                result.userId(),
                result.email(),
                result.name(),
                result.roles(),
                result.accessToken(),
                "Bearer"
        );
    }
}