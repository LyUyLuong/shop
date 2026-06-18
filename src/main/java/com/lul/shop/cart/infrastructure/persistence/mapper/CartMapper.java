package com.lul.shop.cart.infrastructure.persistence.mapper;

import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartItem;
import com.lul.shop.cart.infrastructure.persistence.entity.CartItemJpaEntity;
import com.lul.shop.cart.infrastructure.persistence.entity.CartJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CartMapper {

    default Cart toDomain(CartJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new Cart(
                entity.getId(),
                entity.getUserId(),
                entity.getItems()
                        .stream()
                        .map(this::toDomain)
                        .toList(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default CartItem toDomain(CartItemJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new CartItem(
                entity.getId(),
                entity.getProductId(),
                entity.getQuantity(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default CartJpaEntity toEntity(Cart cart) {
        if (cart == null) {
            return null;
        }

        CartJpaEntity entity = new CartJpaEntity();
        entity.setId(cart.getId());
        entity.setUserId(cart.getUserId());
        entity.setCreatedAt(cart.getCreatedAt());
        entity.setUpdatedAt(cart.getUpdatedAt());

        cart.getItems()
                .stream()
                .map(this::toEntity)
                .forEach(entity::attachItem);

        return entity;
    }

    default CartItemJpaEntity toEntity(CartItem item) {
        if (item == null) {
            return null;
        }

        CartItemJpaEntity entity = new CartItemJpaEntity();
        entity.setId(item.getId());
        entity.setProductId(item.getProductId());
        entity.setQuantity(item.getQuantity());
        entity.setCreatedAt(item.getCreatedAt());
        entity.setUpdatedAt(item.getUpdatedAt());

        return entity;
    }
}