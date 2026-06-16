package com.lul.shop.catalog.infrastructure.storage.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String bucket,
        String region,
        String accessKey,
        String secretKey,
        Duration presignedUrlExpiry,
        String productImagePrefix,
        String publicBaseUrl
) {
}