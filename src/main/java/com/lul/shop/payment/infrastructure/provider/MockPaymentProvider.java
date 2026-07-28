package com.lul.shop.payment.infrastructure.provider;

import com.lul.shop.payment.application.port.PaymentProvider;
import com.lul.shop.payment.application.port.PaymentProviderRequest;
import com.lul.shop.payment.application.port.PaymentProviderResult;
import com.lul.shop.payment.domain.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.Objects;
import org.springframework.context.annotation.Profile;

@Profile({"dev", "test"})
@Component
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.MOCK;
    }

    @Override
    public PaymentProviderResult process(
            PaymentProviderRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return PaymentProviderResult.succeeded(
                request.requestedAt()
        );
    }
}