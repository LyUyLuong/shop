package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.OrderStatus;

import java.util.Objects;
import java.util.UUID;

public record ChangeOrderStatusCommand(
        UUID orderId,
        UUID adminUserId,
        OrderStatus targetStatus,
        String reason
) {
    private static final int REASON_MAX_LENGTH = 500;

    public ChangeOrderStatusCommand {
        orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        adminUserId = Objects.requireNonNull(adminUserId, "adminUserId must not be null");
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus must not be null");
        reason = requireReason(reason);
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }

        String trimmed = value.trim();

        if (trimmed.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("reason must not exceed 500 characters");
        }

        return trimmed;
    }
}