package com.lul.shop.ordering.presentation;

import com.lul.shop.shared.config.WebConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderItemImageUrlResolver {

    private final String publicApiUrl;

    public OrderItemImageUrlResolver(@Value("${app.public-api-url}") String publicApiUrl) {
        this.publicApiUrl = stripTrailingSlash(publicApiUrl);
    }

    public String customerImageUrl(UUID orderId, UUID orderItemId, String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            return null;
        }

        return publicApiUrl
                + WebConfig.API_V1
                + "/orders/"
                + orderId
                + "/items/"
                + orderItemId
                + "/image";
    }

    public String adminImageUrl(UUID orderId, UUID orderItemId, String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            return null;
        }

        return publicApiUrl
                + WebConfig.API_V1
                + "/admin/orders/"
                + orderId
                + "/items/"
                + orderItemId
                + "/image";
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}