package com.lul.shop.ordering.application.port;

import com.lul.shop.ordering.domain.ShippingMethod;

import java.math.BigDecimal;

public interface ShippingFeePolicy {

    BigDecimal shippingFeeFor(ShippingMethod shippingMethod);
}