package com.lul.shop.cart.domain;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository {

    Optional<Cart> findByUserId(UUID userId);

    void lockCreationByUserId(UUID userId);

    Optional<Cart> findByIdAndUserIdForUpdate(
            UUID cartId,
            UUID userId
    );

    Cart save(Cart cart);
}
