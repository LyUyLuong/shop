package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.infrastructure.persistence.entity.OrderStatusHistoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderStatusHistoryJpaRepository extends JpaRepository<OrderStatusHistoryJpaEntity, UUID> {

    List<OrderStatusHistoryJpaEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}