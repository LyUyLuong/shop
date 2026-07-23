package com.lul.shop.ordering.infrastructure.checkout;

import com.lul.shop.ordering.domain.OrderAmounts;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Objects;

@ConfigurationProperties(prefix = "app.ordering.checkout")
public record OrderingCheckoutProperties(
        BigDecimal standardShippingFee,
        boolean codEnabled
) {
    public OrderingCheckoutProperties {
        Objects.requireNonNull(
                standardShippingFee,
                "standardShippingFee must not be null"
        );

        standardShippingFee = OrderAmounts.calculate(
                BigDecimal.ZERO,
                standardShippingFee
        ).shippingFee();
    }
}