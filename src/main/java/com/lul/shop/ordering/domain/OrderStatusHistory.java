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

    private OrderStatusHistory(
            UUID id,
            UUID orderId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusChangeActorType actorType,
            UUID actorUserId,
            String reason,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(
                id,
                "id must not be null"
        );
        this.orderId = Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );
        this.fromStatus = Objects.requireNonNull(
                fromStatus,
                "fromStatus must not be null"
        );
        this.toStatus = Objects.requireNonNull(
                toStatus,
                "toStatus must not be null"
        );
        this.actorType = Objects.requireNonNull(
                actorType,
                "actorType must not be null"
        );

        if (fromStatus == toStatus) {
            throw new IllegalArgumentException(
                    "fromStatus and toStatus must be different"
            );
        }

        validateActorIdentity(actorType, actorUserId);
        validateActorTransition(
                actorType,
                fromStatus,
                toStatus
        );

        this.actorUserId = actorUserId;
        this.reason = requireReason(reason);
        this.createdAt = createdAt;
    }

    public static OrderStatusHistory recordAdminChange(
            UUID orderId,
            UUID adminUserId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String reason
    ) {
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

    public static OrderStatusHistory recordPaymentChange(
            UUID orderId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String reason
    ) {
        return new OrderStatusHistory(
                UUID.randomUUID(),
                orderId,
                fromStatus,
                toStatus,
                OrderStatusChangeActorType.PAYMENT,
                null,
                reason,
                null
        );
    }

    public static OrderStatusHistory recordSystemChange(
            UUID orderId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String reason
    ) {
        return new OrderStatusHistory(
                UUID.randomUUID(),
                orderId,
                fromStatus,
                toStatus,
                OrderStatusChangeActorType.SYSTEM,
                null,
                reason,
                null
        );
    }

    public static OrderStatusHistory reconstitute(
            UUID id,
            UUID orderId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusChangeActorType actorType,
            UUID actorUserId,
            String reason,
            Instant createdAt
    ) {
        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        return new OrderStatusHistory(
                id,
                orderId,
                fromStatus,
                toStatus,
                actorType,
                actorUserId,
                reason,
                createdAt
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

    private static void validateActorIdentity(
            OrderStatusChangeActorType actorType,
            UUID actorUserId
    ) {
        if (actorType == OrderStatusChangeActorType.ADMIN
                && actorUserId == null) {
            throw new IllegalArgumentException(
                    "actorUserId must not be null for admin status changes"
            );
        }

        if (actorType != OrderStatusChangeActorType.ADMIN
                && actorUserId != null) {
            throw new IllegalArgumentException(
                    "actorUserId must be null for non-admin status changes"
            );
        }
    }

    private static void validateActorTransition(
            OrderStatusChangeActorType actorType,
            OrderStatus fromStatus,
            OrderStatus toStatus
    ) {
        switch (actorType) {
            case PAYMENT -> requireExactTransition(
                    fromStatus,
                    OrderStatus.PENDING_PAYMENT,
                    toStatus,
                    OrderStatus.PAID,
                    "payment"
            );
            case SYSTEM -> requireExactTransition(
                    fromStatus,
                    OrderStatus.PENDING_PAYMENT,
                    toStatus,
                    OrderStatus.EXPIRED,
                    "system expiry"
            );
            case ADMIN, MARKETPLACE -> {
            }
        }
    }

    private static void requireExactTransition(
            OrderStatus actualFrom,
            OrderStatus expectedFrom,
            OrderStatus actualTo,
            OrderStatus expectedTo,
            String transitionOwner
    ) {
        if (actualFrom != expectedFrom || actualTo != expectedTo) {
            throw new IllegalArgumentException(
                    transitionOwner
                            + " history must record "
                            + expectedFrom
                            + " -> "
                            + expectedTo
            );
        }
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "reason must not be blank"
            );
        }

        String trimmed = value.trim();

        if (trimmed.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "reason must not exceed 500 characters"
            );
        }

        return trimmed;
    }
}