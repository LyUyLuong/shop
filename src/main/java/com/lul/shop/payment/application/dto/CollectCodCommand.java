package com.lul.shop.payment.application.dto;

import java.util.Objects;
import java.util.UUID;

public record CollectCodCommand(
        UUID adminUserId,
        UUID orderId,
        String idempotencyKey
) {

    public CollectCodCommand {
        adminUserId = Objects.requireNonNull(
                adminUserId,
                "adminUserId must not be null"
        );

        orderId = Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );

        idempotencyKey = Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null"
        );
    }
}