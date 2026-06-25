package com.lul.shop.outbox.infrastructure.persistence.repository;

import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.infrastructure.persistence.entity.OutboxEventJpaEntity;
import com.lul.shop.outbox.infrastructure.persistence.mapper.OutboxEventMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Repository
@Transactional(readOnly = true)
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final OutboxEventMapper outboxEventMapper;

    public OutboxEventRepositoryImpl(OutboxEventJpaRepository outboxEventJpaRepository,
                                     OutboxEventMapper outboxEventMapper) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Override
    @Transactional
    public OutboxEvent save(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        OutboxEventJpaEntity entity = outboxEventMapper.toEntity(event);
        OutboxEventJpaEntity savedEntity = outboxEventJpaRepository.save(entity);

        return outboxEventMapper.toDomain(savedEntity);
    }

    @Override
    @Transactional
    public List<OutboxEvent> findPublishableEvents(int limit, int maxRetryCount) {
        requirePositive(limit, "limit");
        requirePositive(maxRetryCount, "maxRetryCount");

        return outboxEventJpaRepository.findPublishableEvents(
                        OutboxEventStatus.NEW,
                        maxRetryCount,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(outboxEventMapper::toDomain)
                .toList();
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }

        return value;
    }
}