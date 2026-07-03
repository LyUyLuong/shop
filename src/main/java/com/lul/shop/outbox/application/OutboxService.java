package com.lul.shop.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OutboxService {

    private static final String ORDER_AGGREGATE_TYPE = "ORDER";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

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

        OutboxEvent savedEvent = outboxEventRepository.save(event);

        log.info(
                "action=outbox.recorded eventId={} eventType={} aggregateType={} aggregateId={} orderId={} paymentId={} userId={}",
                savedEvent.getId(),
                savedEvent.getEventType(),
                savedEvent.getAggregateType(),
                savedEvent.getAggregateId(),
                orderId,
                paymentId,
                userId
        );
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