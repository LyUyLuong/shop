package com.lul.shop.payment.application.port;

import java.util.UUID;

public interface PayableOrderClient {

    PayableOrderTransitionSnapshot transitionToPaid(
            UUID userId,
            UUID orderId
    );
}