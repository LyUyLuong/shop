package com.lul.shop.outbox.infrastructure.persistence.repository;

import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import com.lul.shop.outbox.domain.OutboxEventStatus;
import com.lul.shop.outbox.domain.OutboxEventType;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class OutboxEventRepositoryImplTest extends PostgresIntegrationTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-06-30T08:00:00Z");

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindPublishableNewEvent() {
        OutboxEvent saved = outboxEventRepository.save(newEvent("single-event"));

        flushAndClear();

        List<OutboxEvent> events = outboxEventRepository.findPublishableEvents(10, 5);

        assertThat(events)
                .extracting(OutboxEvent::getId)
                .contains(saved.getId());

        OutboxEvent found = findById(events, saved.getId());

        assertThat(found.getEventType()).isEqualTo(OutboxEventType.ORDER_PAID);
        assertThat(found.getAggregateType()).isEqualTo("ORDER");
        assertThat(found.getAggregateId()).isEqualTo(saved.getAggregateId());
        assertThat(found.getPayload()).contains("single-event");
        assertThat(found.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(found.getRetryCount()).isZero();
        assertThat(found.getLastError()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldReturnOnlyNewEventsBelowMaxRetryCount() {
        OutboxEvent newEvent = outboxEventRepository.save(newEvent("new-event"));
        OutboxEvent retryableEvent = outboxEventRepository.save(newEventWithRetryCount("retryable-event", 4));
        OutboxEvent maxRetryEvent = outboxEventRepository.save(newEventWithRetryCount("max-retry-event", 5));
        OutboxEvent publishedEvent = outboxEventRepository.save(publishedEvent("published-event"));
        OutboxEvent failedEvent = outboxEventRepository.save(failedEvent("failed-event"));

        flushAndClear();

        updateCreatedAt(newEvent.getId(), Instant.parse("2026-01-01T00:00:00Z"));
        updateCreatedAt(retryableEvent.getId(), Instant.parse("2026-01-02T00:00:00Z"));
        updateCreatedAt(maxRetryEvent.getId(), Instant.parse("2025-01-01T00:00:00Z"));
        updateCreatedAt(publishedEvent.getId(), Instant.parse("2025-01-02T00:00:00Z"));
        updateCreatedAt(failedEvent.getId(), Instant.parse("2025-01-03T00:00:00Z"));

        flushAndClear();

        List<OutboxEvent> events = outboxEventRepository.findPublishableEvents(10, 5);

        assertThat(events)
                .extracting(OutboxEvent::getId)
                .containsExactly(newEvent.getId(), retryableEvent.getId());
    }

    @Test
    void shouldRespectLimitAndOrderByCreatedAtAscending() {
        OutboxEvent first = outboxEventRepository.save(newEvent("first-event"));
        OutboxEvent second = outboxEventRepository.save(newEvent("second-event"));
        OutboxEvent third = outboxEventRepository.save(newEvent("third-event"));

        flushAndClear();

        updateCreatedAt(third.getId(), Instant.parse("2026-01-03T00:00:00Z"));
        updateCreatedAt(first.getId(), Instant.parse("2026-01-01T00:00:00Z"));
        updateCreatedAt(second.getId(), Instant.parse("2026-01-02T00:00:00Z"));

        flushAndClear();

        List<OutboxEvent> events = outboxEventRepository.findPublishableEvents(2, 5);

        assertThat(events)
                .extracting(OutboxEvent::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void shouldRejectNonPositiveLimit() {
        assertThatThrownBy(() -> outboxEventRepository.findPublishableEvents(0, 5))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("limit must be > 0")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveMaxRetryCount() {
        assertThatThrownBy(() -> outboxEventRepository.findPublishableEvents(10, 0))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("maxRetryCount must be > 0")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private OutboxEvent newEvent(String name) {
        return OutboxEvent.create(
                OutboxEventType.ORDER_PAID,
                "ORDER",
                UUID.randomUUID(),
                payload(name)
        );
    }

    private OutboxEvent newEventWithRetryCount(String name, int retryCount) {
        return new OutboxEvent(
                UUID.randomUUID(),
                OutboxEventType.ORDER_PAID,
                "ORDER",
                UUID.randomUUID(),
                payload(name),
                OutboxEventStatus.NEW,
                retryCount,
                retryCount == 0 ? null : "previous publish failure",
                null,
                null,
                null
        );
    }

    private OutboxEvent publishedEvent(String name) {
        return new OutboxEvent(
                UUID.randomUUID(),
                OutboxEventType.ORDER_PAID,
                "ORDER",
                UUID.randomUUID(),
                payload(name),
                OutboxEventStatus.PUBLISHED,
                0,
                null,
                PUBLISHED_AT,
                null,
                null
        );
    }

    private OutboxEvent failedEvent(String name) {
        return new OutboxEvent(
                UUID.randomUUID(),
                OutboxEventType.ORDER_PAID,
                "ORDER",
                UUID.randomUUID(),
                payload(name),
                OutboxEventStatus.FAILED,
                5,
                "max retry reached",
                null,
                null,
                null
        );
    }

    private String payload(String name) {
        return """
                {"eventType":"ORDER_PAID","name":"%s"}
                """.formatted(name).trim();
    }

    private OutboxEvent findById(List<OutboxEvent> events, UUID eventId) {
        return events.stream()
                .filter(event -> event.getId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private void updateCreatedAt(UUID eventId, Instant createdAt) {
        jdbcTemplate.update(
                "update outbox_events set created_at = ? where id = ?",
                Timestamp.from(createdAt),
                eventId
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}