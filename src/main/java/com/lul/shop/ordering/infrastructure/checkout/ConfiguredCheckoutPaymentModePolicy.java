package com.lul.shop.ordering.infrastructure.checkout;

import com.lul.shop.ordering.application.port.CheckoutPaymentModePolicy;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ConfiguredCheckoutPaymentModePolicy
        implements CheckoutPaymentModePolicy {

    private final OrderingCheckoutProperties properties;

    public ConfiguredCheckoutPaymentModePolicy(
            OrderingCheckoutProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled(OrderPaymentMode paymentMode) {
        Objects.requireNonNull(
                paymentMode,
                "paymentMode must not be null"
        );

        return paymentMode == OrderPaymentMode.MOCK
                || properties.codEnabled();
    }
}