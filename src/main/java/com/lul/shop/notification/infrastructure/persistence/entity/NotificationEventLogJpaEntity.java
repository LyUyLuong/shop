package com.lul.shop.notification.infrastructure.persistence.entity;

import com.lul.shop.notification.domain.NotificationEventLogStatus;
import com.lul.shop.shared.persistence.UpdatableJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "notification_event_logs")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class NotificationEventLogJpaEntity extends UpdatableJpaEntity {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private NotificationEventLogStatus status;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}