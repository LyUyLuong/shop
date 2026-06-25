package com.lul.shop.payment.application.port;

import java.util.UUID;

public interface PayableOrderClient {

    PayableOrderSnapshot getPayableOrder(UUID userId, UUID orderId);

    void markOrderAsPaid(UUID userId, UUID orderId);
}