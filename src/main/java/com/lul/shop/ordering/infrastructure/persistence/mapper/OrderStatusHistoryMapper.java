package com.lul.shop.ordering.infrastructure.persistence.mapper;

import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderStatusHistoryJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderStatusHistoryMapper {

    default OrderStatusHistory toDomain(
            OrderStatusHistoryJpaEntity entity
    ) {
        if (entity == null) {
            return null;
        }

        return OrderStatusHistory.reconstitute(
                entity.getId(),
                entity.getOrderId(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getActorType(),
                entity.getActorUserId(),
                entity.getReason(),
                entity.getCreatedAt()
        );
    }

    default OrderStatusHistoryJpaEntity toEntity(
            OrderStatusHistory history
    ) {
        if (history == null) {
            return null;
        }

        OrderStatusHistoryJpaEntity entity =
                new OrderStatusHistoryJpaEntity();

        entity.setId(history.getId());
        entity.setOrderId(history.getOrderId());
        entity.setFromStatus(history.getFromStatus());
        entity.setToStatus(history.getToStatus());
        entity.setActorType(history.getActorType());
        entity.setActorUserId(history.getActorUserId());
        entity.setReason(history.getReason());
        entity.setCreatedAt(history.getCreatedAt());

        return entity;
    }
}