package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
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
public class OrderIdempotencyRepositoryImpl
        implements OrderIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public OrderIdempotencyRepositoryImpl(
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Override
    public boolean insertIfAbsent(OrderIdempotencyRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        if (record.status()
                != OrderIdempotencyRecord.Status.PROCESSING) {
            throw new IllegalArgumentException(
                    "only PROCESSING records can be claimed"
            );
        }

        int affectedRows = jdbcTemplate.update(
                """
                insert into order_idempotency_records (
                    id,
                    user_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    order_id,
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
    public Optional<OrderIdempotencyRecord> findByUserIdAndKey(
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
                    order_id,
                    created_at,
                    updated_at
                from order_idempotency_records
                where user_id = ?
                  and idempotency_key = ?
                """,
                (resultSet, rowNumber) ->
                        new OrderIdempotencyRecord(
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
                                OrderIdempotencyRecord.Status.valueOf(
                                        resultSet.getString("status")
                                ),
                                resultSet.getObject(
                                        "order_id",
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
            UUID orderId,
            Instant completedAt
    ) {
        Objects.requireNonNull(recordId, "recordId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );

        entityManager.flush();

        int affectedRows = jdbcTemplate.update(
                """
                update order_idempotency_records
                set status = 'COMPLETED',
                    order_id = ?,
                    updated_at = greatest(updated_at, ?)
                where id = ?
                  and status = 'PROCESSING'
                  and order_id is null
                """,
                orderId,
                Timestamp.from(completedAt),
                recordId
        );

        return affectedRows == 1;
    }
}