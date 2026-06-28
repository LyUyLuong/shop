package com.lul.shop.notification.infrastructure.persistence.mapper;

import com.lul.shop.notification.domain.NotificationEventLog;
import com.lul.shop.notification.infrastructure.persistence.entity.NotificationEventLogJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NotificationEventLogMapper {

    default NotificationEventLog toDomain(NotificationEventLogJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new NotificationEventLog(
                entity.getId(),
                entity.getEventId(),
                entity.getEventType(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getProcessedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default NotificationEventLogJpaEntity toEntity(NotificationEventLog eventLog) {
        if (eventLog == null) {
            return null;
        }

        NotificationEventLogJpaEntity entity = new NotificationEventLogJpaEntity();
        entity.setId(eventLog.getId());
        entity.setEventId(eventLog.getEventId());
        entity.setEventType(eventLog.getEventType());
        entity.setAggregateType(eventLog.getAggregateType());
        entity.setAggregateId(eventLog.getAggregateId());
        entity.setPayload(eventLog.getPayload());
        entity.setStatus(eventLog.getStatus());
        entity.setProcessedAt(eventLog.getProcessedAt());
        entity.setCreatedAt(eventLog.getCreatedAt());
        entity.setUpdatedAt(eventLog.getUpdatedAt());

        return entity;
    }
}