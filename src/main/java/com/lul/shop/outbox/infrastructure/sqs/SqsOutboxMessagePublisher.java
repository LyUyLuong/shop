package com.lul.shop.outbox.infrastructure.sqs;

import com.lul.shop.outbox.application.port.OutboxMessagePublisher;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.infrastructure.publisher.OutboxPublisherProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.Objects;

@Component
public class SqsOutboxMessagePublisher implements OutboxMessagePublisher {

    private final SqsClient sqsClient;
    private final OutboxPublisherProperties properties;

    public SqsOutboxMessagePublisher(SqsClient sqsClient,
                                     OutboxPublisherProperties properties) {
        this.sqsClient = sqsClient;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        if (!properties.hasQueueUrl()) {
            throw new IllegalStateException("SQS queue URL must be configured before publishing outbox events");
        }

        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .messageBody(event.getPayload())
                .messageAttributes(Map.of(
                        "eventId", stringAttribute(event.getId().toString()),
                        "eventType", stringAttribute(event.getEventType().name()),
                        "aggregateType", stringAttribute(event.getAggregateType()),
                        "aggregateId", stringAttribute(event.getAggregateId().toString())
                ))
                .build();

        sqsClient.sendMessage(request);
    }

    private MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }
}