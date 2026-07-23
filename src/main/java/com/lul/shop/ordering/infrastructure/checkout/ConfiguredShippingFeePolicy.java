package com.lul.shop.ordering.infrastructure.checkout;

import com.lul.shop.ordering.application.port.ShippingFeePolicy;
import com.lul.shop.ordering.domain.ShippingMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class ConfiguredShippingFeePolicy
        implements ShippingFeePolicy {

    private final OrderingCheckoutProperties properties;

    public ConfiguredShippingFeePolicy(
            OrderingCheckoutProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public BigDecimal shippingFeeFor(
            ShippingMethod shippingMethod
    ) {
        Objects.requireNonNull(
                shippingMethod,
                "shippingMethod must not be null"
        );

        return switch (shippingMethod) {
            case STANDARD ->
                    properties.standardShippingFee();
        };
    }
}