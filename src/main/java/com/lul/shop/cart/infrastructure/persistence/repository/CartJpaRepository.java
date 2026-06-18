package com.lul.shop.cart.infrastructure.persistence.repository;

import com.lul.shop.cart.infrastructure.persistence.entity.CartJpaEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CartJpaRepository extends JpaRepository<CartJpaEntity, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<CartJpaEntity> findByUserId(UUID userId);
}