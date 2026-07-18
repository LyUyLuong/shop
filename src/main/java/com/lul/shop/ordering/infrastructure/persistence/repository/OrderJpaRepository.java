package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderJpaRepository
        extends JpaRepository<OrderJpaEntity, UUID> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<OrderJpaEntity> findById(UUID id);

    @EntityGraph(attributePaths = "items")
    Optional<OrderJpaEntity> findByIdAndUserId(
            UUID id,
            UUID userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select orderEntity
            from OrderJpaEntity orderEntity
            where orderEntity.id = :orderId
            """)
    Optional<OrderJpaEntity> lockById(
            @Param("orderId") UUID orderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select orderEntity
            from OrderJpaEntity orderEntity
            where orderEntity.id = :orderId
              and orderEntity.userId = :userId
            """)
    Optional<OrderJpaEntity> lockByIdAndUserId(
            @Param("orderId") UUID orderId,
            @Param("userId") UUID userId
    );

    @EntityGraph(attributePaths = "items")
    @Query("""
            select orderEntity
            from OrderJpaEntity orderEntity
            where orderEntity.id = :orderId
            """)
    Optional<OrderJpaEntity> findAggregateById(
            @Param("orderId") UUID orderId
    );

    @EntityGraph(attributePaths = "items")
    @Query("""
            select orderEntity
            from OrderJpaEntity orderEntity
            where orderEntity.id = :orderId
              and orderEntity.userId = :userId
            """)
    Optional<OrderJpaEntity> findAggregateByIdAndUserId(
            @Param("orderId") UUID orderId,
            @Param("userId") UUID userId
    );

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findByUserIdOrderByCreatedAtDesc(
            UUID userId
    );

    @Query(
            value = """
                select order_row.id
                from orders order_row
                where order_row.status = 'PENDING_PAYMENT'
                  and order_row.expires_at <= :cutoff
                order by order_row.expires_at asc, order_row.id asc
                limit :limit
                for update of order_row skip locked
                """,
            nativeQuery = true
    )
    List<UUID> claimExpiredOrderIdsForUpdate(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit
    );
}