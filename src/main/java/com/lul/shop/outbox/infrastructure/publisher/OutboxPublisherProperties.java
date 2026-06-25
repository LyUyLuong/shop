package com.lul.shop.outbox.infrastructure.publisher;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.outbox.publisher")
public record OutboxPublisherProperties(
        Boolean enabled,
        String queueUrl,
        Integer maxBatchSize,
        Integer maxRetryCount,
        Long fixedDelayMs
) {

    public OutboxPublisherProperties {
        enabled = enabled != null && enabled;
        queueUrl = queueUrl == null ? "" : queueUrl.trim();
        maxBatchSize = positiveOrDefault(maxBatchSize, 10, "maxBatchSize");
        maxRetryCount = positiveOrDefault(maxRetryCount, 5, "maxRetryCount");
        fixedDelayMs = positiveOrDefault(fixedDelayMs, 10_000L, "fixedDelayMs");
    }

    public boolean hasQueueUrl() {
        return queueUrl != null && !queueUrl.isBlank();
    }

    private static Integer positiveOrDefault(Integer value, int defaultValue, String fieldName) {
        if (value == null) {
            return defaultValue;
        }

        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }

        return value;
    }

    private static Long positiveOrDefault(Long value, long defaultValue, String fieldName) {
        if (value == null) {
            return defaultValue;
        }

        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }

        return value;
    }
}