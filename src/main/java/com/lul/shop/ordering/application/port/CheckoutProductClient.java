package com.lul.shop.ordering.application.port;

import java.util.UUID;

public interface CheckoutProductClient {

    CheckoutProductSnapshot getProductForCheckout(UUID productId);

    boolean decreaseStockIfEnough(UUID productId, int quantity);
}