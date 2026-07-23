package com.lul.shop.ordering.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAmountsTest {

    @Test
    void shouldCalculateGrandTotalOnServer() {
        OrderAmounts amounts = OrderAmounts.calculate(
                new BigDecimal("100000.00"),
                new BigDecimal("30000.00")
        );

        assertThat(amounts.subtotalAmount())
                .isEqualByComparingTo("100000.00");

        assertThat(amounts.shippingFee())
                .isEqualByComparingTo("30000.00");

        assertThat(amounts.totalAmount())
                .isEqualByComparingTo("130000.00");
    }

    @Test
    void shouldNormalizeCompatibleMoneyScale() {
        OrderAmounts amounts = new OrderAmounts(
                new BigDecimal("100000"),
                new BigDecimal("30000.0"),
                new BigDecimal("130000.00")
        );

        assertThat(amounts.subtotalAmount().scale())
                .isEqualTo(2);

        assertThat(amounts.shippingFee().scale())
                .isEqualTo(2);

        assertThat(amounts.totalAmount().scale())
                .isEqualTo(2);
    }

    @Test
    void shouldRejectInconsistentTotal() {
        assertThatThrownBy(() ->
                new OrderAmounts(
                        new BigDecimal("100000.00"),
                        new BigDecimal("30000.00"),
                        new BigDecimal("100000.00")
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "totalAmount must equal "
                                + "subtotalAmount + shippingFee"
                );
    }

    @Test
    void shouldRejectNegativeMoney() {
        assertThatThrownBy(() ->
                OrderAmounts.calculate(
                        new BigDecimal("100000.00"),
                        new BigDecimal("-1.00")
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage("shippingFee must be >= 0");
    }

    @Test
    void shouldRejectUnsupportedScaleAndPrecision() {
        assertThatThrownBy(() ->
                OrderAmounts.calculate(
                        new BigDecimal("100000.001"),
                        BigDecimal.ZERO
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "subtotalAmount must have "
                                + "at most 2 decimal places"
                );

        assertThatThrownBy(() ->
                OrderAmounts.calculate(
                        new BigDecimal(
                                "100000000000000000.00"
                        ),
                        BigDecimal.ZERO
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "subtotalAmount exceeds "
                                + "NUMERIC(19, 2)"
                );
    }
}