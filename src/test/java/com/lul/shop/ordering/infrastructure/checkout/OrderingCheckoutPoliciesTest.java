package com.lul.shop.ordering.infrastructure.checkout;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.ShippingMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderingCheckoutPoliciesTest {

    @Test
    void shouldReturnConfiguredServerOwnedStandardShippingFee() {
        OrderingCheckoutProperties properties =
                new OrderingCheckoutProperties(
                        new BigDecimal("30000"),
                        false
                );

        ConfiguredShippingFeePolicy policy =
                new ConfiguredShippingFeePolicy(properties);

        assertThat(policy.shippingFeeFor(
                ShippingMethod.STANDARD
        )).isEqualByComparingTo("30000.00");
    }

    @Test
    void shouldAlwaysAllowMockAndGateCodByConfiguration() {
        ConfiguredCheckoutPaymentModePolicy disabledPolicy =
                new ConfiguredCheckoutPaymentModePolicy(
                        new OrderingCheckoutProperties(
                                BigDecimal.ZERO,
                                false
                        )
                );

        ConfiguredCheckoutPaymentModePolicy enabledPolicy =
                new ConfiguredCheckoutPaymentModePolicy(
                        new OrderingCheckoutProperties(
                                BigDecimal.ZERO,
                                true
                        )
                );

        assertThat(disabledPolicy.isEnabled(
                OrderPaymentMode.MOCK
        )).isTrue();
        assertThat(disabledPolicy.isEnabled(
                OrderPaymentMode.COD
        )).isFalse();
        assertThat(enabledPolicy.isEnabled(
                OrderPaymentMode.COD
        )).isTrue();
    }

    @Test
    void shouldRejectInvalidConfiguredShippingFee() {
        assertThatThrownBy(() ->
                new OrderingCheckoutProperties(
                        new BigDecimal("-0.01"),
                        false
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("shippingFee must be >= 0");
    }
}
