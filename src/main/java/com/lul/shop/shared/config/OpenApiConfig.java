package com.lul.shop.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Value("${app.public-api-url:http://localhost:8081}")
    private String publicApiUrl;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Shop Order Processing API")
                        .description("Backend API for a cloud-based mini e-commerce order processing system")
                        .version("v1"))
                .servers(List.of(
                        new Server()
                                .url(trimTrailingSlashes(publicApiUrl))
                                .description("Configured API origin")
                ))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Bearer token from POST /api/v1/auth/login")));
    }

    private String trimTrailingSlashes(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}