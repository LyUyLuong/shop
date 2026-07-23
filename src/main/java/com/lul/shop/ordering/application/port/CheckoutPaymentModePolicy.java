package com.lul.shop.ordering.application.port;

import com.lul.shop.ordering.domain.OrderPaymentMode;

public interface CheckoutPaymentModePolicy {

    boolean isEnabled(OrderPaymentMode paymentMode);
}