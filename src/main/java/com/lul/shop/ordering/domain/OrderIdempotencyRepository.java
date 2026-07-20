package com.lul.shop.ordering.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderIdempotencyRepository {

    boolean insertIfAbsent(OrderIdempotencyRecord record);

    Optional<OrderIdempotencyRecord> findByUserIdAndKey(
            UUID userId,
            String idempotencyKey
    );

    boolean complete(
            UUID recordId,
            UUID orderId,
            Instant completedAt
    );
}