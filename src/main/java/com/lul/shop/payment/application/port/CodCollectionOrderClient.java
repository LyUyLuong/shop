package com.lul.shop.payment.application.port;

import java.util.UUID;

public interface CodCollectionOrderClient {

    CodCollectionTransitionSnapshot collect(
            UUID adminUserId,
            UUID orderId
    );
}