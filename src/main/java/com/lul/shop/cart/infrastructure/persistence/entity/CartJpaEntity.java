package com.lul.shop.cart.infrastructure.persistence.entity;

import com.lul.shop.shared.persistence.UpdatableJpaEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "carts")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class CartJpaEntity extends UpdatableJpaEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItemJpaEntity> items = new ArrayList<>();

    public void attachItem(CartItemJpaEntity item) {
        item.setCart(this);
        items.add(item);
    }
}