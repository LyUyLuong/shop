package com.lul.shop.auth.presentation;

import com.lul.shop.auth.application.AuthService;
import com.lul.shop.auth.application.dto.AuthResult;
import com.lul.shop.auth.application.dto.LoginCommand;
import com.lul.shop.auth.application.dto.RegisterCommand;
import com.lul.shop.auth.presentation.dto.request.LoginRequest;
import com.lul.shop.auth.presentation.dto.request.RegisterRequest;
import com.lul.shop.auth.presentation.dto.response.AuthResponse;
import com.lul.shop.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterCommand command = new RegisterCommand(
                request.email(),
                request.name(),
                request.password()
        );

        AuthResult result = authService.register(command);

        return ApiResponse.ok(AuthResponse.from(result));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginCommand command = new LoginCommand(
                request.email(),
                request.password()
        );

        AuthResult result = authService.login(command);

        return ApiResponse.ok(AuthResponse.from(result));
    }
}