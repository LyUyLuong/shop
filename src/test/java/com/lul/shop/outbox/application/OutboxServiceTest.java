package com.lul.shop.outbox.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.domain.OutboxEventType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class OutboxServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRecordOrderPaidOutboxEvent() throws Exception {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();

        OutboxService service = new OutboxService(eventRepository, objectMapper);

        service.recordOrderPaid(ORDER_ID, PAYMENT_ID, USER_ID);

        assertThat(eventRepository.savedEvents).hasSize(1);

        OutboxEvent event = eventRepository.savedEvents.get(0);

        assertThat(event.getEventType()).isEqualTo(OutboxEventType.ORDER_PAID);
        assertThat(event.getAggregateType()).isEqualTo("ORDER");
        assertThat(event.getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getPublishedAt()).isNull();

        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(payload.get("eventType").asText()).isEqualTo("ORDER_PAID");
        assertThat(payload.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(payload.get("paymentId").asText()).isEqualTo(PAYMENT_ID.toString());
        assertThat(payload.get("userId").asText()).isEqualTo(USER_ID.toString());
    }

    @Test
    void shouldThrowNullPointerExceptionWhenOrderIdIsNull() {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();

        OutboxService service = new OutboxService(eventRepository, objectMapper);

        assertThatThrownBy(() -> service.recordOrderPaid(null, PAYMENT_ID, USER_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("orderId must not be null");

        assertThat(eventRepository.savedEvents).isEmpty();
    }

    @Test
    void shouldThrowNullPointerExceptionWhenPaymentIdIsNull() {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();

        OutboxService service = new OutboxService(eventRepository, objectMapper);

        assertThatThrownBy(() -> service.recordOrderPaid(ORDER_ID, null, USER_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("paymentId must not be null");

        assertThat(eventRepository.savedEvents).isEmpty();
    }

    @Test
    void shouldThrowNullPointerExceptionWhenUserIdIsNull() {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();

        OutboxService service = new OutboxService(eventRepository, objectMapper);

        assertThatThrownBy(() -> service.recordOrderPaid(ORDER_ID, PAYMENT_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userId must not be null");

        assertThat(eventRepository.savedEvents).isEmpty();
    }

    private static class FakeOutboxEventRepository implements OutboxEventRepository {

        private final List<OutboxEvent> savedEvents = new ArrayList<>();

        @Override
        public OutboxEvent save(OutboxEvent event) {
            savedEvents.add(event);
            return event;
        }

        @Override
        public List<OutboxEvent> findPublishableEvents(int limit, int maxRetryCount) {
            throw new UnsupportedOperationException("findPublishableEvents is not used in OutboxServiceTest");
        }
    }
}