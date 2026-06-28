package com.lul.shop.notification.infrastructure.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notification.consumer")
public record NotificationConsumerProperties(
        boolean enabled,
        String queueUrl,
        int maxMessages,
        int waitTimeSeconds,
        int visibilityTimeoutSeconds
) {
    public NotificationConsumerProperties {
        maxMessages = maxMessages <= 0 ? 10 : maxMessages;
        waitTimeSeconds = waitTimeSeconds < 0 ? 10 : waitTimeSeconds;
        visibilityTimeoutSeconds = visibilityTimeoutSeconds <= 0 ? 30 : visibilityTimeoutSeconds;
    }

    public boolean hasQueueUrl() {
        return queueUrl != null && !queueUrl.isBlank();
    }
}