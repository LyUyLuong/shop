package com.lul.shop.payment.application.port;

import java.time.Instant;
import java.util.Objects;

public record PaymentProviderResult(
        Outcome outcome,
        Instant processedAt,
        String failureReason
) {

    public PaymentProviderResult {
        outcome = Objects.requireNonNull(outcome);

        failureReason = failureReason == null
                ? null
                : failureReason.trim();

        if (failureReason != null && failureReason.isEmpty()) {
            failureReason = null;
        }

        if (outcome == Outcome.SUCCEEDED
                && (processedAt == null || failureReason != null)) {
            throw new IllegalArgumentException(
                    "succeeded result requires processedAt only"
            );
        }

        if (outcome == Outcome.REJECTED
                && (processedAt != null || failureReason == null)) {
            throw new IllegalArgumentException(
                    "rejected result requires failureReason only"
            );
        }
    }

    public static PaymentProviderResult succeeded(
            Instant processedAt
    ) {
        return new PaymentProviderResult(
                Outcome.SUCCEEDED,
                Objects.requireNonNull(processedAt),
                null
        );
    }

    public static PaymentProviderResult rejected(
            String failureReason
    ) {
        return new PaymentProviderResult(
                Outcome.REJECTED,
                null,
                failureReason
        );
    }

    public boolean isSucceeded() {
        return outcome == Outcome.SUCCEEDED;
    }

    public enum Outcome {
        SUCCEEDED,
        REJECTED
    }
}