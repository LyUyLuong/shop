package com.lul.shop.outbox.infrastructure.sqs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(SqsProperties properties) {
        var builder = SqsClient.builder()
                .region(Region.of(properties.region()));

        if (hasText(properties.accessKey()) && hasText(properties.secretKey())) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
                    )
            );
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}