package com.lul.shop.ordering.application.dto;

import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OrderStatusHistoryResult(
        UUID id,
        UUID orderId,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        OrderStatusChangeActorType actorType,
        UUID actorUserId,
        String reason,
        Instant createdAt
) {
    public OrderStatusHistoryResult {
        id = Objects.requireNonNull(id, "id must not be null");
        orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        fromStatus = Objects.requireNonNull(fromStatus, "fromStatus must not be null");
        toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        actorType = Objects.requireNonNull(actorType, "actorType must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }
}