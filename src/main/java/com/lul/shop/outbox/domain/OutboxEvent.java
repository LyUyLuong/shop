package com.lul.shop.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class OutboxEvent {

    private UUID id;
    private OutboxEventType eventType;
    private String aggregateType;
    private UUID aggregateId;
    private String payload;
    private OutboxEventStatus status;
    private int retryCount;
    private String lastError;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public OutboxEvent(UUID id,
                       OutboxEventType eventType,
                       String aggregateType,
                       UUID aggregateId,
                       String payload,
                       OutboxEventStatus status,
                       int retryCount,
                       String lastError,
                       Instant publishedAt,
                       Instant createdAt,
                       Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.payload = requireText(payload, "payload");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.retryCount = requireNonNegative(retryCount, "retryCount");
        this.lastError = normalizeOptionalText(lastError);
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;

        validateStatus();
    }

    public static OutboxEvent create(OutboxEventType eventType,
                                     String aggregateType,
                                     UUID aggregateId,
                                     String payload) {
        return new OutboxEvent(
                UUID.randomUUID(),
                eventType,
                aggregateType,
                aggregateId,
                payload,
                OutboxEventStatus.NEW,
                0,
                null,
                null,
                null,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public OutboxEventType getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxEventStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markPublished(Instant publishedAt) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        this.lastError = null;
    }

    public void markPublishFailed(String lastError, int maxRetryCount) {
        requirePositive(maxRetryCount, "maxRetryCount");

        this.retryCount = this.retryCount + 1;
        this.lastError = requireText(lastError, "lastError");
        this.publishedAt = null;

        if (this.retryCount >= maxRetryCount) {
            this.status = OutboxEventStatus.FAILED;
        } else {
            this.status = OutboxEventStatus.NEW;
        }
    }

    private void validateStatus() {
        if (status == OutboxEventStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("publishedAt is required for published event");
        }

        if (status != OutboxEventStatus.PUBLISHED && publishedAt != null) {
            throw new IllegalArgumentException("publishedAt must be null unless event is published");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }

        return value;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }

        return value;
    }
}