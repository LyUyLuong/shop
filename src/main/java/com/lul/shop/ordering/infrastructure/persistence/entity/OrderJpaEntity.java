package com.lul.shop.ordering.infrastructure.persistence.entity;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.ShippingMethod;
import com.lul.shop.shared.persistence.UpdatableJpaEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class OrderJpaEntity extends UpdatableJpaEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "inventory_released_at")
    private Instant inventoryReleasedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "shipping_address", length = 500)
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", length = 30)
    private ShippingMethod shippingMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", length = 30)
    private OrderPaymentMode paymentMode;

    @Column(name = "subtotal_amount", precision = 19, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(name = "shipping_fee", precision = 19, scale = 2)
    private BigDecimal shippingFee;

    public void attachItem(OrderItemJpaEntity item) {
        item.setOrder(this);
        items.add(item);
    }
}
