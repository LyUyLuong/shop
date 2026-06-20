package com.lul.shop.ordering.application.port;

import java.util.UUID;

public interface ProductCheckoutClient {

    ProductSnapshot getActiveProduct(UUID productId);

    boolean decreaseStockIfEnough(UUID productId, int quantity);
}