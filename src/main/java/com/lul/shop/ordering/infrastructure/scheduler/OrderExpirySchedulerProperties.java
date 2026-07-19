package com.lul.shop.ordering.infrastructure.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ordering.expiry")
public record OrderExpirySchedulerProperties(
        Boolean enabled,
        Integer batchSize,
        Long initialDelayMs,
        Long fixedDelayMs
) {

    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final long DEFAULT_INITIAL_DELAY_MS = 60_000L;
    private static final long DEFAULT_FIXED_DELAY_MS = 30_000L;

    public OrderExpirySchedulerProperties {
        enabled = Boolean.TRUE.equals(enabled);
        batchSize = positiveOrDefault(
                batchSize,
                DEFAULT_BATCH_SIZE,
                "batchSize"
        );
        initialDelayMs = nonNegativeOrDefault(
                initialDelayMs,
                DEFAULT_INITIAL_DELAY_MS,
                "initialDelayMs"
        );
        fixedDelayMs = positiveOrDefault(
                fixedDelayMs,
                DEFAULT_FIXED_DELAY_MS,
                "fixedDelayMs"
        );
    }

    private static Integer positiveOrDefault(
            Integer value,
            int defaultValue,
            String fieldName
    ) {
        if (value == null) {
            return defaultValue;
        }

        if (value <= 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be greater than 0"
            );
        }

        return value;
    }

    private static Long positiveOrDefault(
            Long value,
            long defaultValue,
            String fieldName
    ) {
        if (value == null) {
            return defaultValue;
        }

        if (value <= 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be greater than 0"
            );
        }

        return value;
    }

    private static Long nonNegativeOrDefault(
            Long value,
            long defaultValue,
            String fieldName
    ) {
        if (value == null) {
            return defaultValue;
        }

        if (value < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must not be negative"
            );
        }

        return value;
    }
}
