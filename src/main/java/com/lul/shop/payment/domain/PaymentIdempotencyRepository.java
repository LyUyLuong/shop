package com.lul.shop.payment.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIdempotencyRepository {

    boolean insertIfAbsent(
            PaymentIdempotencyRecord record
    );

    Optional<PaymentIdempotencyRecord> findByUserIdAndKey(
            UUID userId,
            String idempotencyKey
    );

    boolean complete(
            UUID recordId,
            UUID paymentId,
            Instant completedAt
    );
}