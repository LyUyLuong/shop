package com.lul.shop.ordering.infrastructure.persistence.mapper;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderItemJpaEntity;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    default Order toDomain(OrderJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new Order(
                entity.getId(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getTotalAmount(),
                entity.getItems()
                        .stream()
                        .map(this::toDomain)
                        .toList(),
                entity.getExpiresAt(),
                entity.getInventoryReleasedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default OrderItem toDomain(OrderItemJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new OrderItem(
                entity.getId(),
                entity.getProductId(),
                entity.getProductSku(),
                entity.getProductName(),
                entity.getProductImageKey(),
                entity.getUnitPrice(),
                entity.getQuantity(),
                entity.getLineTotal(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default OrderJpaEntity toEntity(Order order) {
        if (order == null) {
            return null;
        }

        OrderJpaEntity entity = new OrderJpaEntity();
        entity.setId(order.getId());
        entity.setUserId(order.getUserId());
        entity.setStatus(order.getStatus());
        entity.setTotalAmount(order.getTotalAmount());
        entity.setExpiresAt(order.getExpiresAt());
        entity.setInventoryReleasedAt(order.getInventoryReleasedAt());
        entity.setCreatedAt(order.getCreatedAt());
        entity.setUpdatedAt(order.getUpdatedAt());

        order.getItems()
                .stream()
                .map(this::toEntity)
                .forEach(entity::attachItem);

        return entity;
    }

    default OrderItemJpaEntity toEntity(OrderItem item) {
        if (item == null) {
            return null;
        }

        OrderItemJpaEntity entity = new OrderItemJpaEntity();
        entity.setId(item.getId());
        entity.setProductId(item.getProductId());
        entity.setProductSku(item.getProductSku());
        entity.setProductName(item.getProductName());
        entity.setProductImageKey(item.getProductImageKey());
        entity.setUnitPrice(item.getUnitPrice());
        entity.setQuantity(item.getQuantity());
        entity.setLineTotal(item.getLineTotal());
        entity.setCreatedAt(item.getCreatedAt());
        entity.setUpdatedAt(item.getUpdatedAt());

        return entity;
    }
}
