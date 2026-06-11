package com.lul.shop.auth.application.dto;

public record LoginCommand(
        String email,
        String password
) {
}