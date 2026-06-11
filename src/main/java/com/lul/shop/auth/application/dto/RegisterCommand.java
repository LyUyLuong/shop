package com.lul.shop.auth.application.dto;

public record RegisterCommand(
        String email,
        String name,
        String password
) {
}