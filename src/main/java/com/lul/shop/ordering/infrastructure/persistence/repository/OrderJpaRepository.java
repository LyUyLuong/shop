package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<OrderJpaEntity> findById(UUID id);

    @EntityGraph(attributePaths = "items")
    Optional<OrderJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}