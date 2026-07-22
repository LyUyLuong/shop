package com.lul.shop.ordering.application.port;

import java.util.UUID;

public interface CheckoutCartClient {

    CheckoutCartSnapshot claimForCheckout(
            UUID userId,
            UUID cartId,
            long expectedVersion
    );
}