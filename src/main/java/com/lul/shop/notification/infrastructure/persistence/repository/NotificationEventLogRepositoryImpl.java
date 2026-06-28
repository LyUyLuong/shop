package com.lul.shop.notification.infrastructure.persistence.repository;

import com.lul.shop.notification.domain.NotificationEventLog;
import com.lul.shop.notification.domain.NotificationEventLogRepository;
import com.lul.shop.notification.infrastructure.persistence.entity.NotificationEventLogJpaEntity;
import com.lul.shop.notification.infrastructure.persistence.mapper.NotificationEventLogMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class NotificationEventLogRepositoryImpl implements NotificationEventLogRepository {

    private final NotificationEventLogJpaRepository notificationEventLogJpaRepository;
    private final NotificationEventLogMapper notificationEventLogMapper;

    public NotificationEventLogRepositoryImpl(NotificationEventLogJpaRepository notificationEventLogJpaRepository,
                                              NotificationEventLogMapper notificationEventLogMapper) {
        this.notificationEventLogJpaRepository = notificationEventLogJpaRepository;
        this.notificationEventLogMapper = notificationEventLogMapper;
    }

    @Override
    @Transactional
    public NotificationEventLog save(NotificationEventLog eventLog) {
        Objects.requireNonNull(eventLog, "eventLog must not be null");

        NotificationEventLogJpaEntity entity = notificationEventLogMapper.toEntity(eventLog);
        NotificationEventLogJpaEntity savedEntity = notificationEventLogJpaRepository.save(entity);

        return notificationEventLogMapper.toDomain(savedEntity);
    }

    @Override
    public boolean existsByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");

        return notificationEventLogJpaRepository.existsByEventId(eventId);
    }

    @Override
    public Optional<NotificationEventLog> findByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");

        return notificationEventLogJpaRepository.findByEventId(eventId)
                .map(notificationEventLogMapper::toDomain);
    }
}