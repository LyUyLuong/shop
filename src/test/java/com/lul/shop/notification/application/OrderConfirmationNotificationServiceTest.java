package com.lul.shop.notification.application;

import com.lul.shop.notification.application.dto.OrderConfirmationEmail;
import com.lul.shop.notification.application.dto.OrderPaidNotificationMessage;
import com.lul.shop.notification.application.port.EmailSender;
import com.lul.shop.notification.domain.NotificationEventLog;
import com.lul.shop.notification.domain.NotificationEventLogRepository;
import com.lul.shop.notification.domain.NotificationEventLogStatus;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class OrderConfirmationNotificationServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PAYMENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void shouldSendOrderConfirmationAndSaveProcessedLogWhenEventIsNew() {
        FakeNotificationEventLogRepository eventLogRepository = new FakeNotificationEventLogRepository();
        FakeEmailSender emailSender = new FakeEmailSender();

        OrderConfirmationNotificationService service = new OrderConfirmationNotificationService(
                eventLogRepository,
                emailSender
        );

        service.handleOrderPaid(orderPaidMessage());

        assertThat(emailSender.sentEmails)
                .containsExactly(new OrderConfirmationEmail(USER_ID, ORDER_ID, PAYMENT_ID));

        assertThat(eventLogRepository.savedLogs).hasSize(1);

        NotificationEventLog savedLog = eventLogRepository.savedLogs.get(0);

        assertThat(savedLog.getEventId()).isEqualTo(EVENT_ID);
        assertThat(savedLog.getEventType()).isEqualTo("ORDER_PAID");
        assertThat(savedLog.getAggregateType()).isEqualTo("ORDER");
        assertThat(savedLog.getAggregateId()).isEqualTo(ORDER_ID);
        assertThat(savedLog.getPayload()).isEqualTo(orderPaidPayload());
        assertThat(savedLog.getStatus()).isEqualTo(NotificationEventLogStatus.PROCESSED);
        assertThat(savedLog.getProcessedAt()).isNotNull();
    }

    @Test
    void shouldSkipEventWhenEventIdAlreadyProcessed() {
        FakeNotificationEventLogRepository eventLogRepository = new FakeNotificationEventLogRepository();
        eventLogRepository.givenEventAlreadyProcessed(EVENT_ID);

        FakeEmailSender emailSender = new FakeEmailSender();

        OrderConfirmationNotificationService service = new OrderConfirmationNotificationService(
                eventLogRepository,
                emailSender
        );

        service.handleOrderPaid(orderPaidMessage());

        assertThat(emailSender.sentEmails).isEmpty();
        assertThat(eventLogRepository.savedLogs).isEmpty();
    }

    @Test
    void shouldNotSaveProcessedLogWhenEmailSenderFails() {
        FakeNotificationEventLogRepository eventLogRepository = new FakeNotificationEventLogRepository();

        FakeEmailSender emailSender = new FakeEmailSender();
        emailSender.failOnSend = true;

        OrderConfirmationNotificationService service = new OrderConfirmationNotificationService(
                eventLogRepository,
                emailSender
        );

        assertThatThrownBy(() -> service.handleOrderPaid(orderPaidMessage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email provider unavailable");

        assertThat(emailSender.sentEmails).isEmpty();
        assertThat(eventLogRepository.savedLogs).isEmpty();
    }

    private static OrderPaidNotificationMessage orderPaidMessage() {
        return new OrderPaidNotificationMessage(
                EVENT_ID,
                "ORDER_PAID",
                "ORDER",
                ORDER_ID,
                ORDER_ID,
                PAYMENT_ID,
                USER_ID,
                orderPaidPayload()
        );
    }

    private static String orderPaidPayload() {
        return """
                {"eventType":"ORDER_PAID","orderId":"22222222-2222-4222-8222-222222222222","paymentId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444"}
                """.trim();
    }

    private static class FakeNotificationEventLogRepository implements NotificationEventLogRepository {

        private final Set<UUID> processedEventIds = new HashSet<>();
        private final List<NotificationEventLog> savedLogs = new ArrayList<>();

        private void givenEventAlreadyProcessed(UUID eventId) {
            processedEventIds.add(eventId);
        }

        @Override
        public NotificationEventLog save(NotificationEventLog eventLog) {
            savedLogs.add(eventLog);
            processedEventIds.add(eventLog.getEventId());
            return eventLog;
        }

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processedEventIds.contains(eventId);
        }

        @Override
        public Optional<NotificationEventLog> findByEventId(UUID eventId) {
            return savedLogs.stream()
                    .filter(log -> log.getEventId().equals(eventId))
                    .findFirst();
        }
    }

    private static class FakeEmailSender implements EmailSender {

        private final List<OrderConfirmationEmail> sentEmails = new ArrayList<>();
        private boolean failOnSend;

        @Override
        public void sendOrderConfirmation(OrderConfirmationEmail email) {
            if (failOnSend) {
                throw new IllegalStateException("Email provider unavailable");
            }

            sentEmails.add(email);
        }
    }
}