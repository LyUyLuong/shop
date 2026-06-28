package com.lul.shop.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class NotificationEventLog {

    private UUID id;
    private UUID eventId;
    private String eventType;
    private String aggregateType;
    private UUID aggregateId;
    private String payload;
    private NotificationEventLogStatus status;
    private Instant processedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public NotificationEventLog(UUID id,
                                UUID eventId,
                                String eventType,
                                String aggregateType,
                                UUID aggregateId,
                                String payload,
                                NotificationEventLogStatus status,
                                Instant processedAt,
                                Instant createdAt,
                                Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = requireText(eventType, "eventType");
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.payload = normalizeOptionalText(payload);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt must not be null");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static NotificationEventLog recordProcessedEvent(UUID eventId,
                                                            String eventType,
                                                            String aggregateType,
                                                            UUID aggregateId,
                                                            String payload,
                                                            Instant processedAt) {
        return new NotificationEventLog(
                UUID.randomUUID(),
                eventId,
                eventType,
                aggregateType,
                aggregateId,
                payload,
                NotificationEventLogStatus.PROCESSED,
                processedAt,
                null,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
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

    public NotificationEventLogStatus getStatus() {
        return status;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
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
}