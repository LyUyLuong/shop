package com.lul.shop.notification.application.dto;

import java.util.Objects;
import java.util.UUID;

public record OrderPaidNotificationMessage(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        UUID orderId,
        UUID paymentId,
        UUID userId,
        String payload
) {
    public OrderPaidNotificationMessage {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        eventType = requireText(eventType, "eventType");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        userId = Objects.requireNonNull(userId, "userId must not be null");
        payload = normalizePayload(payload);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizePayload(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}