package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.OrderStatusHistoryResult;

import java.time.Instant;
import java.util.UUID;

public record OrderStatusHistoryResponse(
        UUID id,
        UUID orderId,
        String fromStatus,
        String toStatus,
        String actorType,
        UUID actorUserId,
        String reason,
        Instant createdAt
) {

    public static OrderStatusHistoryResponse from(OrderStatusHistoryResult result) {
        return new OrderStatusHistoryResponse(
                result.id(),
                result.orderId(),
                result.fromStatus().name(),
                result.toStatus().name(),
                result.actorType().name(),
                result.actorUserId(),
                result.reason(),
                result.createdAt()
        );
    }
}