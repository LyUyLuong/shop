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
                        false,
                        false
                );

        ConfiguredShippingFeePolicy policy =
                new ConfiguredShippingFeePolicy(properties);

        assertThat(policy.shippingFeeFor(
                ShippingMethod.STANDARD
        )).isEqualByComparingTo("30000.00");
    }

    @Test
    void shouldGateMockAndCodIndependentlyByConfiguration() {
        ConfiguredCheckoutPaymentModePolicy disabledPolicy =
                paymentModePolicy(false, false);

        ConfiguredCheckoutPaymentModePolicy mockOnlyPolicy =
                paymentModePolicy(true, false);

        ConfiguredCheckoutPaymentModePolicy codOnlyPolicy =
                paymentModePolicy(false, true);

        ConfiguredCheckoutPaymentModePolicy enabledPolicy =
                paymentModePolicy(true, true);

        assertThat(disabledPolicy.isEnabled(
                OrderPaymentMode.MOCK
        )).isFalse();

        assertThat(disabledPolicy.isEnabled(
                OrderPaymentMode.COD
        )).isFalse();

        assertThat(mockOnlyPolicy.isEnabled(
                OrderPaymentMode.MOCK
        )).isTrue();

        assertThat(mockOnlyPolicy.isEnabled(
                OrderPaymentMode.COD
        )).isFalse();

        assertThat(codOnlyPolicy.isEnabled(
                OrderPaymentMode.MOCK
        )).isFalse();

        assertThat(codOnlyPolicy.isEnabled(
                OrderPaymentMode.COD
        )).isTrue();

        assertThat(enabledPolicy.isEnabled(
                OrderPaymentMode.MOCK
        )).isTrue();

        assertThat(enabledPolicy.isEnabled(
                OrderPaymentMode.COD
        )).isTrue();
    }

    @Test
    void shouldRejectInvalidConfiguredShippingFee() {
        assertThatThrownBy(() ->
                new OrderingCheckoutProperties(
                        new BigDecimal("-0.01"),
                        false,
                        false
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("shippingFee must be >= 0");
    }

    private ConfiguredCheckoutPaymentModePolicy paymentModePolicy(
            boolean mockEnabled,
            boolean codEnabled
    ) {
        OrderingCheckoutProperties properties =
                new OrderingCheckoutProperties(
                        BigDecimal.ZERO,
                        mockEnabled,
                        codEnabled
                );

        return new ConfiguredCheckoutPaymentModePolicy(
                properties
        );
    }
}