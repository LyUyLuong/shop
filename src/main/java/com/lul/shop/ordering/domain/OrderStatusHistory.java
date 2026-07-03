package com.lul.shop.ordering.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class OrderStatusHistory {

    private static final int REASON_MAX_LENGTH = 500;

    private UUID id;
    private UUID orderId;
    private OrderStatus fromStatus;
    private OrderStatus toStatus;
    private OrderStatusChangeActorType actorType;
    private UUID actorUserId;
    private String reason;
    private Instant createdAt;

    public OrderStatusHistory(UUID id,
                              UUID orderId,
                              OrderStatus fromStatus,
                              OrderStatus toStatus,
                              OrderStatusChangeActorType actorType,
                              UUID actorUserId,
                              String reason,
                              Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus must not be null");
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        this.actorType = Objects.requireNonNull(actorType, "actorType must not be null");
        this.actorUserId = actorUserId;
        this.reason = requireReason(reason);
        this.createdAt = createdAt;

        if (fromStatus == toStatus) {
            throw new IllegalArgumentException("fromStatus and toStatus must be different");
        }

        if (actorType == OrderStatusChangeActorType.ADMIN && actorUserId == null) {
            throw new IllegalArgumentException("actorUserId must not be null for admin status changes");
        }
    }

    public static OrderStatusHistory recordAdminChange(UUID orderId,
                                                       UUID adminUserId,
                                                       OrderStatus fromStatus,
                                                       OrderStatus toStatus,
                                                       String reason) {
        return new OrderStatusHistory(
                UUID.randomUUID(),
                orderId,
                fromStatus,
                toStatus,
                OrderStatusChangeActorType.ADMIN,
                adminUserId,
                reason,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public OrderStatusChangeActorType getActorType() {
        return actorType;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
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