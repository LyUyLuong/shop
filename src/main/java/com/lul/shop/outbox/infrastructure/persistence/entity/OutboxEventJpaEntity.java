package com.lul.shop.outbox.infrastructure.persistence.entity;

import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.domain.OutboxEventType;
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
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class OutboxEventJpaEntity extends UpdatableJpaEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 80)
    private OutboxEventType eventType;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;
}