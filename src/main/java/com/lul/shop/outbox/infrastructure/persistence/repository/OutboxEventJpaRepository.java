package com.lul.shop.outbox.infrastructure.persistence.repository;

import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.infrastructure.persistence.entity.OutboxEventJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event
            from OutboxEventJpaEntity event
            where event.status = :status
              and event.retryCount < :maxRetryCount
            order by event.createdAt asc
            """)
    List<OutboxEventJpaEntity> findPublishableEvents(@Param("status") OutboxEventStatus status,
                                                     @Param("maxRetryCount") int maxRetryCount,
                                                     Pageable pageable);
}