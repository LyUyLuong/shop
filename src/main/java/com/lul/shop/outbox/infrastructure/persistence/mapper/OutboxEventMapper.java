package com.lul.shop.outbox.infrastructure.persistence.mapper;

import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.infrastructure.persistence.entity.OutboxEventJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OutboxEventMapper {

    default OutboxEvent toDomain(OutboxEventJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new OutboxEvent(
                entity.getId(),
                entity.getEventType(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getRetryCount(),
                entity.getLastError(),
                entity.getPublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default OutboxEventJpaEntity toEntity(OutboxEvent event) {
        if (event == null) {
            return null;
        }

        OutboxEventJpaEntity entity = new OutboxEventJpaEntity();
        entity.setId(event.getId());
        entity.setEventType(event.getEventType());
        entity.setAggregateType(event.getAggregateType());
        entity.setAggregateId(event.getAggregateId());
        entity.setPayload(event.getPayload());
        entity.setStatus(event.getStatus());
        entity.setRetryCount(event.getRetryCount());
        entity.setLastError(event.getLastError());
        entity.setPublishedAt(event.getPublishedAt());
        entity.setCreatedAt(event.getCreatedAt());
        entity.setUpdatedAt(event.getUpdatedAt());

        return entity;
    }
}