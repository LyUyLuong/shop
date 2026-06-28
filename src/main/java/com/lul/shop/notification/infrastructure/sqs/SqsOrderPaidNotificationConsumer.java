package com.lul.shop.notification.infrastructure.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.notification.application.OrderConfirmationNotificationService;
import com.lul.shop.notification.application.dto.OrderPaidNotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.UUID;

@Component
public class SqsOrderPaidNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsOrderPaidNotificationConsumer.class);
    private static final String ORDER_PAID = "ORDER_PAID";

    private final SqsClient sqsClient;
    private final NotificationConsumerProperties properties;
    private final ObjectMapper objectMapper;
    private final OrderConfirmationNotificationService notificationService;

    public SqsOrderPaidNotificationConsumer(SqsClient sqsClient,
                                            NotificationConsumerProperties properties,
                                            ObjectMapper objectMapper,
                                            OrderConfirmationNotificationService notificationService) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${app.notification.consumer.fixed-delay-ms:10000}")
    public void pollOrderPaidMessages() {
        if (!properties.enabled()) {
            return;
        }

        if (!properties.hasQueueUrl()) {
            log.warn("Notification consumer is enabled but app.notification.consumer.queue-url is empty");
            return;
        }

        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .maxNumberOfMessages(properties.maxMessages())
                .waitTimeSeconds(properties.waitTimeSeconds())
                .visibilityTimeout(properties.visibilityTimeoutSeconds())
                .messageAttributeNames("All")
                .build();

        sqsClient.receiveMessage(request)
                .messages()
                .forEach(this::processMessage);
    }

    private void processMessage(Message message) {
        try {
            OrderPaidNotificationMessage notificationMessage = toNotificationMessage(message);
            notificationService.handleOrderPaid(notificationMessage);
            deleteMessage(message);
        } catch (RuntimeException ex) {
            log.warn("Failed to process SQS notification message: messageId={}", message.messageId(), ex);
        }
    }

    private OrderPaidNotificationMessage toNotificationMessage(Message message) {
        UUID eventId = UUID.fromString(requiredAttribute(message, "eventId"));
        String eventType = requiredAttribute(message, "eventType");
        String aggregateType = requiredAttribute(message, "aggregateType");
        UUID aggregateId = UUID.fromString(requiredAttribute(message, "aggregateId"));

        if (!ORDER_PAID.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported notification event type: " + eventType);
        }

        JsonNode payload = readPayload(message.body());
        String payloadEventType = requiredText(payload, "eventType");

        if (!eventType.equals(payloadEventType)) {
            throw new IllegalArgumentException("SQS eventType does not match payload eventType");
        }

        UUID orderId = requiredUuid(payload, "orderId");
        UUID paymentId = requiredUuid(payload, "paymentId");
        UUID userId = requiredUuid(payload, "userId");

        if (!aggregateId.equals(orderId)) {
            throw new IllegalArgumentException("SQS aggregateId must match payload orderId");
        }

        return new OrderPaidNotificationMessage(
                eventId,
                eventType,
                aggregateType,
                aggregateId,
                orderId,
                paymentId,
                userId,
                message.body()
        );
    }

    private JsonNode readPayload(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid ORDER_PAID payload", ex);
        }
    }

    private String requiredAttribute(Message message, String name) {
        MessageAttributeValue value = message.messageAttributes().get(name);

        if (value == null || value.stringValue() == null || value.stringValue().isBlank()) {
            throw new IllegalArgumentException("Missing SQS message attribute: " + name);
        }

        return value.stringValue();
    }

    private UUID requiredUuid(JsonNode payload, String fieldName) {
        return UUID.fromString(requiredText(payload, fieldName));
    }

    private String requiredText(JsonNode payload, String fieldName) {
        JsonNode value = payload.get(fieldName);

        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing payload field: " + fieldName);
        }

        return value.asText();
    }

    private void deleteMessage(Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build();

        sqsClient.deleteMessage(request);
    }
}