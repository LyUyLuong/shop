package com.lul.shop.ordering.infrastructure.persistence.entity;

import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.shared.persistence.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "order_status_history")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class OrderStatusHistoryJpaEntity extends BaseJpaEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30)
    private OrderStatusChangeActorType actorType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;
}