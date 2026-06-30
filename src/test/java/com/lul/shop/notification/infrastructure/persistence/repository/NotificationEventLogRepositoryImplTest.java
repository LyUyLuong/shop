package com.lul.shop.notification.infrastructure.persistence.repository;

import com.lul.shop.notification.domain.NotificationEventLog;
import com.lul.shop.notification.domain.NotificationEventLogRepository;
import com.lul.shop.notification.domain.NotificationEventLogStatus;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class NotificationEventLogRepositoryImplTest extends PostgresIntegrationTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_EVENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant PROCESSED_AT = Instant.parse("2026-06-30T08:00:00Z");

    @Autowired
    private NotificationEventLogRepository notificationEventLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldSaveAndFindProcessedEventLogByEventId() {
        NotificationEventLog saved = notificationEventLogRepository.save(newProcessedEventLog(EVENT_ID));

        flushAndClear();

        assertThat(notificationEventLogRepository.existsByEventId(EVENT_ID)).isTrue();

        NotificationEventLog found = notificationEventLogRepository.findByEventId(EVENT_ID).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEventId()).isEqualTo(EVENT_ID);
        assertThat(found.getEventType()).isEqualTo("ORDER_PAID");
        assertThat(found.getAggregateType()).isEqualTo("ORDER");
        assertThat(found.getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(found.getPayload()).contains("\"eventType\":\"ORDER_PAID\"");
        assertThat(found.getStatus()).isEqualTo(NotificationEventLogStatus.PROCESSED);
        assertThat(found.getProcessedAt()).isEqualTo(PROCESSED_AT);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldReturnFalseAndEmptyWhenEventIdDoesNotExist() {
        notificationEventLogRepository.save(newProcessedEventLog(EVENT_ID));

        flushAndClear();

        Optional<NotificationEventLog> found = notificationEventLogRepository.findByEventId(OTHER_EVENT_ID);

        assertThat(notificationEventLogRepository.existsByEventId(OTHER_EVENT_ID)).isFalse();
        assertThat(found).isEmpty();
    }

    @Test
    void shouldAllowNullPayload() {
        NotificationEventLog saved = notificationEventLogRepository.save(
                NotificationEventLog.recordProcessedEvent(
                        EVENT_ID,
                        "ORDER_PAID",
                        "ORDER",
                        ORDER_ID,
                        null,
                        PROCESSED_AT
                )
        );

        flushAndClear();

        NotificationEventLog found = notificationEventLogRepository.findByEventId(saved.getEventId()).orElseThrow();

        assertThat(found.getPayload()).isNull();
        assertThat(found.getStatus()).isEqualTo(NotificationEventLogStatus.PROCESSED);
    }

    @Test
    void shouldRejectDuplicateEventId() {
        notificationEventLogRepository.save(newProcessedEventLog(EVENT_ID));

        flushAndClear();

        NotificationEventLog duplicateLog = NotificationEventLog.recordProcessedEvent(
                EVENT_ID,
                "ORDER_PAID",
                "ORDER",
                UUID.randomUUID(),
                payload("duplicate-event"),
                PROCESSED_AT.plusSeconds(60)
        );

        notificationEventLogRepository.save(duplicateLog);

        assertThatThrownBy(this::flushAndClear)
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uq_notification_event_logs_event_id");
    }

    private NotificationEventLog newProcessedEventLog(UUID eventId) {
        return NotificationEventLog.recordProcessedEvent(
                eventId,
                "ORDER_PAID",
                "ORDER",
                ORDER_ID,
                payload("processed-event"),
                PROCESSED_AT
        );
    }

    private String payload(String name) {
        return """
                {"eventType":"ORDER_PAID","name":"%s"}
                """.formatted(name).trim();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}