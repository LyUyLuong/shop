package com.lul.shop.notification.application.dto;

import java.util.Objects;
import java.util.UUID;

public record OrderConfirmationEmail(
        UUID userId,
        UUID orderId,
        UUID paymentId
) {
    public OrderConfirmationEmail {
        userId = Objects.requireNonNull(userId, "userId must not be null");
        orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
    }
}