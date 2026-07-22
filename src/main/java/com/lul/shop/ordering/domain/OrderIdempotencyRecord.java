package com.lul.shop.ordering.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrderIdempotencyRecord(
        UUID id,
        UUID userId,
        String idempotencyKey,
        String requestFingerprint,
        Status status,
        UUID orderId,
        Instant createdAt,
        Instant updatedAt
) {
    private static final Pattern KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{8,100}");
    private static final Pattern FINGERPRINT_PATTERN =
            Pattern.compile("[0-9a-f]{64}");

    public enum Status {
        PROCESSING,
        COMPLETED
    }

    public OrderIdempotencyRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        if (!FINGERPRINT_PATTERN.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("requestFingerprint is invalid");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if ((status == Status.PROCESSING) != (orderId == null)) {
            throw new IllegalArgumentException("status and orderId are inconsistent");
        }
    }

    public static OrderIdempotencyRecord processing(
            UUID userId, String key, String fingerprint, Instant now
    ) {
        return new OrderIdempotencyRecord(
                UUID.randomUUID(), userId, key, fingerprint,
                Status.PROCESSING, null, now, now
        );
    }

    public boolean matchesFingerprint(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }
}