package com.lul.shop.payment.infrastructure.persistence.repository;

import com.lul.shop.payment.domain.PaymentIdempotencyRecord;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentIdempotencyRepositoryImpl
        implements PaymentIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public PaymentIdempotencyRepositoryImpl(
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Override
    public boolean insertIfAbsent(
            PaymentIdempotencyRecord record
    ) {
        Objects.requireNonNull(record, "record must not be null");

        if (record.status()
                != PaymentIdempotencyRecord.Status.PROCESSING) {
            throw new IllegalArgumentException(
                    "only PROCESSING records can be claimed"
            );
        }

        int affectedRows = jdbcTemplate.update(
                """
                insert into payment_idempotency_records (
                    id,
                    user_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    payment_id,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, 'PROCESSING', null, ?, ?)
                on conflict (user_id, idempotency_key)
                do nothing
                """,
                record.id(),
                record.userId(),
                record.idempotencyKey(),
                record.requestFingerprint(),
                Timestamp.from(record.createdAt()),
                Timestamp.from(record.updatedAt())
        );

        return affectedRows == 1;
    }

    @Override
    public Optional<PaymentIdempotencyRecord> findByUserIdAndKey(
            UUID userId,
            String idempotencyKey
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(
                idempotencyKey,
                "idempotencyKey must not be null"
        );

        return jdbcTemplate.query(
                """
                select
                    id,
                    user_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    payment_id,
                    created_at,
                    updated_at
                from payment_idempotency_records
                where user_id = ?
                  and idempotency_key = ?
                """,
                (resultSet, rowNumber) ->
                        new PaymentIdempotencyRecord(
                                resultSet.getObject(
                                        "id",
                                        UUID.class
                                ),
                                resultSet.getObject(
                                        "user_id",
                                        UUID.class
                                ),
                                resultSet.getString(
                                        "idempotency_key"
                                ),
                                resultSet.getString(
                                        "request_fingerprint"
                                ),
                                PaymentIdempotencyRecord
                                        .Status
                                        .valueOf(
                                                resultSet.getString(
                                                        "status"
                                                )
                                        ),
                                resultSet.getObject(
                                        "payment_id",
                                        UUID.class
                                ),
                                resultSet.getTimestamp(
                                        "created_at"
                                ).toInstant(),
                                resultSet.getTimestamp(
                                        "updated_at"
                                ).toInstant()
                        ),
                userId,
                idempotencyKey
        ).stream().findFirst();
    }

    @Override
    public boolean complete(
            UUID recordId,
            UUID paymentId,
            Instant completedAt
    ) {
        Objects.requireNonNull(recordId, "recordId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );

        entityManager.flush();

        int affectedRows = jdbcTemplate.update(
                """
                update payment_idempotency_records
                set status = 'COMPLETED',
                    payment_id = ?,
                    updated_at = greatest(updated_at, ?)
                where id = ?
                  and status = 'PROCESSING'
                  and payment_id is null
                """,
                paymentId,
                Timestamp.from(completedAt),
                recordId
        );

        return affectedRows == 1;
    }
}
