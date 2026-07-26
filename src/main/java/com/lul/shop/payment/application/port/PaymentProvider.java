package com.lul.shop.payment.application.port;

import com.lul.shop.payment.domain.PaymentMethod;

public interface PaymentProvider {

    PaymentMethod method();

    PaymentProviderResult process(
            PaymentProviderRequest request
    );
}