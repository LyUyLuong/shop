package com.lul.shop.notification.infrastructure.persistence.repository;

import com.lul.shop.notification.infrastructure.persistence.entity.NotificationEventLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationEventLogJpaRepository extends JpaRepository<NotificationEventLogJpaEntity, UUID> {

    boolean existsByEventId(UUID eventId);

    Optional<NotificationEventLogJpaEntity> findByEventId(UUID eventId);
}