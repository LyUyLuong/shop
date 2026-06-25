package com.lul.shop.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OutboxService {

    private static final String ORDER_AGGREGATE_TYPE = "ORDER";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository,
                         ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordOrderPaid(UUID orderId, UUID paymentId, UUID userId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        OutboxEvent event = OutboxEvent.create(
                OutboxEventType.ORDER_PAID,
                ORDER_AGGREGATE_TYPE,
                orderId,
                toJson(new OrderPaidPayload(
                        OutboxEventType.ORDER_PAID.name(),
                        orderId,
                        paymentId,
                        userId
                ))
        );

        outboxEventRepository.save(event);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("outbox payload cannot be serialized", ex);
        }
    }

    private record OrderPaidPayload(
            String eventType,
            UUID orderId,
            UUID paymentId,
            UUID userId
    ) {
    }
}