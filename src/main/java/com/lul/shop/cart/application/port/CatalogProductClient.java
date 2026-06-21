package com.lul.shop.cart.application.port;

import java.util.Optional;
import java.util.UUID;

public interface CatalogProductClient {

    Optional<CartProductSnapshot> findActiveProduct(UUID productId);
}