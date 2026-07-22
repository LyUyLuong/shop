package com.lul.shop.payment.infrastructure.persistence.repository;

import com.lul.shop.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    @Query("""
            select p
            from PaymentJpaEntity p
            where p.id = :paymentId
              and p.userId = :userId
            """)
    Optional<PaymentJpaEntity> findByIdAndUserId(@Param("paymentId") UUID paymentId,
                                                 @Param("userId") UUID userId);

    @Query("""
            select p
            from PaymentJpaEntity p
            where p.orderId = :orderId
            """)
    Optional<PaymentJpaEntity> findByOrderId(@Param("orderId") UUID orderId);

}