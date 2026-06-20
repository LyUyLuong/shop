package com.lul.shop.cart.application.port;

import java.util.UUID;

public interface CatalogProductClient {

    boolean existsActiveProduct(UUID productId);
}