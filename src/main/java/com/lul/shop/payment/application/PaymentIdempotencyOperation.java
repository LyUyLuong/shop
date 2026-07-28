package com.lul.shop.payment.application;

import java.util.Objects;
import java.util.UUID;

public enum PaymentIdempotencyOperation {

    MOCK_PAYMENT("PAYMENT_MOCK"),
    COD_COLLECTION("PAYMENT_COD_COLLECTION");

    private final String fingerprintNamespace;

    PaymentIdempotencyOperation(
            String fingerprintNamespace
    ) {
        this.fingerprintNamespace =
                Objects.requireNonNull(
                        fingerprintNamespace
                );
    }

    String canonicalRequest(UUID orderId) {
        Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );

        return fingerprintNamespace
                + "\norderId="
                + orderId;
    }
}