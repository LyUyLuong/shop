package com.lul.shop.cart.application.port;

import java.util.UUID;

public interface CartProductCatalog {

    boolean existsActiveProduct(UUID productId);
}