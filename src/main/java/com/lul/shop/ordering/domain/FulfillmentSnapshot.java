package com.lul.shop.ordering.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record FulfillmentSnapshot(
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        ShippingMethod shippingMethod
) {

    private static final Pattern WHITESPACE =
            Pattern.compile(
                    "\\s+",
                    Pattern.UNICODE_CHARACTER_CLASS
            );

    private static final Pattern PHONE_SEPARATORS =
            Pattern.compile(
                    "[\\s().-]+",
                    Pattern.UNICODE_CHARACTER_CLASS
            );

    private static final Pattern CANONICAL_PHONE =
            Pattern.compile("\\+?[0-9]{8,15}");

    public FulfillmentSnapshot {
        recipientName = canonicalizeText(
                recipientName,
                "recipientName",
                2,
                100
        );

        recipientPhone =
                canonicalizePhone(recipientPhone);

        shippingAddress = canonicalizeText(
                shippingAddress,
                "shippingAddress",
                10,
                500
        );

        shippingMethod = Objects.requireNonNull(
                shippingMethod,
                "shippingMethod must not be null"
        );
    }

    private static String canonicalizeText(
            String value,
            String fieldName,
            int minimumLength,
            int maximumLength
    ) {
        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        String canonical = WHITESPACE
                .matcher(value.strip())
                .replaceAll(" ");

        if (canonical.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        if (
                canonical.length() < minimumLength
                        || canonical.length() > maximumLength
        ) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must contain between "
                            + minimumLength
                            + " and "
                            + maximumLength
                            + " characters"
            );
        }

        return canonical;
    }

    private static String canonicalizePhone(String value) {
        Objects.requireNonNull(
                value,
                "recipientPhone must not be null"
        );

        String canonical = PHONE_SEPARATORS
                .matcher(value.strip())
                .replaceAll("");

        if (!CANONICAL_PHONE.matcher(canonical).matches()) {
            throw new IllegalArgumentException(
                    "recipientPhone is invalid"
            );
        }

        return canonical;
    }
}