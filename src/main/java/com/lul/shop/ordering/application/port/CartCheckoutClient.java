package com.lul.shop.ordering.application.port;

import java.util.UUID;

public interface CartCheckoutClient {

    CartSnapshot getCart(UUID userId);

    void clearCart(UUID userId);
}