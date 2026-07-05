package com.lul.shop.catalog.application;

import com.lul.shop.shared.config.WebConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProductImageUrlResolver {

    private final String publicApiUrl;

    public ProductImageUrlResolver(@Value("${app.public-api-url}") String publicApiUrl) {
        this.publicApiUrl = removeTrailingSlash(publicApiUrl);
    }

    public String resolve(UUID productId, String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            return null;
        }

        return publicApiUrl + WebConfig.API_V1 + "/products/" + productId + "/image";
    }

    private String removeTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();

        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }
}