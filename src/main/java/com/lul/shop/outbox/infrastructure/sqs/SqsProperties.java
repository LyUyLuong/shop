package com.lul.shop.outbox.infrastructure.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sqs")
public record SqsProperties(
        String region,
        String accessKey,
        String secretKey
) {
}