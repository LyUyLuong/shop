package com.lul.shop.outbox.application;

import com.lul.shop.outbox.application.port.OutboxMessagePublisher;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.domain.OutboxEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class OutboxPublisherServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-06-29T10:00:00Z");

    private final Clock clock = Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC);

    @Test
    void shouldPublishEventAndMarkPublishedWhenPublisherSucceeds() {
        OutboxEvent event = newOrderPaidEvent();

        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        eventRepository.givenPublishableEvents(List.of(event));

        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        int publishedCount = service.publishBatch(10, 5);

        assertThat(publishedCount).isEqualTo(1);

        assertThat(eventRepository.requestedLimit).isEqualTo(10);
        assertThat(eventRepository.requestedMaxRetryCount).isEqualTo(5);

        assertThat(messagePublisher.publishedEvents).containsExactly(event);
        assertThat(eventRepository.savedEvents).containsExactly(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    void shouldKeepEventRetryableWhenPublishFailsBelowMaxRetryCount() {
        OutboxEvent event = newOrderPaidEvent();

        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        eventRepository.givenPublishableEvents(List.of(event));

        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();
        messagePublisher.givenPublishFailsFor(event.getId(), "SQS is unavailable");

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        int publishedCount = service.publishBatch(10, 5);

        assertThat(publishedCount).isZero();

        assertThat(messagePublisher.publishedEvents).containsExactly(event);
        assertThat(eventRepository.savedEvents).containsExactly(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("SQS is unavailable");
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void shouldMarkEventFailedWhenPublishFailsAtMaxRetryCount() {
        OutboxEvent event = newOrderPaidEventWithRetryCount(4);

        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        eventRepository.givenPublishableEvents(List.of(event));

        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();
        messagePublisher.givenPublishFailsFor(event.getId(), "Queue does not exist");

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        int publishedCount = service.publishBatch(10, 5);

        assertThat(publishedCount).isZero();

        assertThat(eventRepository.savedEvents).containsExactly(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getLastError()).isEqualTo("Queue does not exist");
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void shouldTrimLastErrorWhenPublishFailureMessageIsTooLong() {
        OutboxEvent event = newOrderPaidEvent();

        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        eventRepository.givenPublishableEvents(List.of(event));

        String longError = "x".repeat(1200);

        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();
        messagePublisher.givenPublishFailsFor(event.getId(), longError);

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        int publishedCount = service.publishBatch(10, 5);

        assertThat(publishedCount).isZero();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).hasSize(1000);
        assertThat(event.getLastError()).isEqualTo("x".repeat(1000));
    }

    @Test
    void shouldUseExceptionClassNameWhenPublishFailureMessageIsBlank() {
        OutboxEvent event = newOrderPaidEvent();

        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        eventRepository.givenPublishableEvents(List.of(event));

        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();
        messagePublisher.givenPublishFailsWithBlankMessageFor(event.getId());

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        int publishedCount = service.publishBatch(10, 5);

        assertThat(publishedCount).isZero();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("IllegalStateException");
    }

    @Test
    void shouldReturnZeroWhenThereAreNoPublishableEvents() {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        eventRepository.givenPublishableEvents(List.of());

        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        int publishedCount = service.publishBatch(10, 5);

        assertThat(publishedCount).isZero();

        assertThat(messagePublisher.publishedEvents).isEmpty();
        assertThat(eventRepository.savedEvents).isEmpty();
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenBatchSizeIsNotPositive() {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        assertThatThrownBy(() -> service.publishBatch(0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("batchSize must be > 0");

        assertThat(eventRepository.requestedLimit).isNull();
        assertThat(messagePublisher.publishedEvents).isEmpty();
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenMaxRetryCountIsNotPositive() {
        FakeOutboxEventRepository eventRepository = new FakeOutboxEventRepository();
        FakeOutboxMessagePublisher messagePublisher = new FakeOutboxMessagePublisher();

        OutboxPublisherService service = new OutboxPublisherService(
                eventRepository,
                messagePublisher,
                clock
        );

        assertThatThrownBy(() -> service.publishBatch(10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxRetryCount must be > 0");

        assertThat(eventRepository.requestedLimit).isNull();
        assertThat(messagePublisher.publishedEvents).isEmpty();
    }

    private static OutboxEvent newOrderPaidEvent() {
        return newOrderPaidEventWithRetryCount(0);
    }

    private static OutboxEvent newOrderPaidEventWithRetryCount(int retryCount) {
        return new OutboxEvent(
                UUID.randomUUID(),
                OutboxEventType.ORDER_PAID,
                "ORDER",
                ORDER_ID,
                """
                {"eventType":"ORDER_PAID","orderId":"11111111-1111-4111-8111-111111111111"}
                """,
                OutboxEventStatus.NEW,
                retryCount,
                null,
                null,
                null,
                null
        );
    }

    private static class FakeOutboxEventRepository implements OutboxEventRepository {

        private List<OutboxEvent> publishableEvents = List.of();
        private final List<OutboxEvent> savedEvents = new ArrayList<>();
        private Integer requestedLimit;
        private Integer requestedMaxRetryCount;

        private void givenPublishableEvents(List<OutboxEvent> publishableEvents) {
            this.publishableEvents = publishableEvents;
        }

        @Override
        public OutboxEvent save(OutboxEvent event) {
            savedEvents.add(event);
            return event;
        }

        @Override
        public List<OutboxEvent> findPublishableEvents(int limit, int maxRetryCount) {
            requestedLimit = limit;
            requestedMaxRetryCount = maxRetryCount;
            return publishableEvents;
        }
    }

    private static class FakeOutboxMessagePublisher implements OutboxMessagePublisher {

        private final List<OutboxEvent> publishedEvents = new ArrayList<>();
        private final Map<UUID, RuntimeException> failuresByEventId = new HashMap<>();

        private void givenPublishFailsFor(UUID eventId, String message) {
            failuresByEventId.put(eventId, new IllegalStateException(message));
        }

        private void givenPublishFailsWithBlankMessageFor(UUID eventId) {
            failuresByEventId.put(eventId, new IllegalStateException(" "));
        }

        @Override
        public void publish(OutboxEvent event) {
            publishedEvents.add(event);

            RuntimeException failure = failuresByEventId.get(event.getId());

            if (failure != null) {
                throw failure;
            }
        }
    }
}