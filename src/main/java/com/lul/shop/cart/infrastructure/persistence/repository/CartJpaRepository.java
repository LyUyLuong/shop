package com.lul.shop.cart.infrastructure.persistence.repository;

import com.lul.shop.cart.infrastructure.persistence.entity.CartJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartJpaRepository
        extends JpaRepository<CartJpaEntity, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<CartJpaEntity> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select cartEntity
            from CartJpaEntity cartEntity
            where cartEntity.id = :cartId
              and cartEntity.userId = :userId
            """)
    Optional<CartJpaEntity> lockByIdAndUserId(
            @Param("cartId") UUID cartId,
            @Param("userId") UUID userId
    );

    @EntityGraph(attributePaths = "items")
    @Query("""
            select cartEntity
            from CartJpaEntity cartEntity
            where cartEntity.id = :cartId
              and cartEntity.userId = :userId
            """)
    Optional<CartJpaEntity> findAggregateByIdAndUserId(
            @Param("cartId") UUID cartId,
            @Param("userId") UUID userId
    );
}