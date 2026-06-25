package com.lul.shop.payment.infrastructure.persistence.entity;

import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.persistence.UpdatableJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PaymentJpaEntity extends UpdatableJpaEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 30)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}