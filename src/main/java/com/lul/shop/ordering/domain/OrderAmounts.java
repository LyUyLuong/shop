package com.lul.shop.ordering.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record OrderAmounts(
        BigDecimal subtotalAmount,
        BigDecimal shippingFee,
        BigDecimal totalAmount
) {

    private static final int MONEY_PRECISION = 19;
    private static final int MONEY_SCALE = 2;

    public OrderAmounts {
        subtotalAmount = requireMoney(
                subtotalAmount,
                "subtotalAmount"
        );

        shippingFee = requireMoney(
                shippingFee,
                "shippingFee"
        );

        totalAmount = requireMoney(
                totalAmount,
                "totalAmount"
        );

        BigDecimal expectedTotal =
                subtotalAmount.add(shippingFee);

        if (totalAmount.compareTo(expectedTotal) != 0) {
            throw new IllegalArgumentException(
                    "totalAmount must equal "
                            + "subtotalAmount + shippingFee"
            );
        }
    }

    public static OrderAmounts calculate(
            BigDecimal subtotalAmount,
            BigDecimal shippingFee
    ) {
        BigDecimal validSubtotal = requireMoney(
                subtotalAmount,
                "subtotalAmount"
        );

        BigDecimal validShippingFee = requireMoney(
                shippingFee,
                "shippingFee"
        );

        return new OrderAmounts(
                validSubtotal,
                validShippingFee,
                validSubtotal.add(validShippingFee)
        );
    }

    private static BigDecimal requireMoney(
            BigDecimal value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be >= 0"
            );
        }

        BigDecimal normalized;

        try {
            normalized = value.setScale(
                    MONEY_SCALE,
                    RoundingMode.UNNECESSARY
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must have at most "
                            + MONEY_SCALE
                            + " decimal places",
                    exception
            );
        }

        if (normalized.precision() > MONEY_PRECISION) {
            throw new IllegalArgumentException(
                    fieldName
                            + " exceeds NUMERIC(19, 2)"
            );
        }

        return normalized;
    }
}