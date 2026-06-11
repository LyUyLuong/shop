package com.lul.shop.auth.application.dto;

import com.lul.shop.auth.domain.UserRole;

import java.util.Set;
import java.util.UUID;

public record AuthResult(
        UUID userId,
        String email,
        String name,
        Set<UserRole> roles,
        String accessToken
) {
}