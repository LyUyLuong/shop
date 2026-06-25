package com.lul.shop.ordering.application.port;

import java.util.UUID;

public interface CheckoutCartClient {

    CheckoutCartSnapshot getCartForCheckout(UUID userId);

    void clearCart(UUID userId);
}