package com.lul.shop.notification.application;

import com.lul.shop.notification.application.dto.OrderConfirmationEmail;
import com.lul.shop.notification.application.dto.OrderPaidNotificationMessage;
import com.lul.shop.notification.application.port.EmailSender;
import com.lul.shop.notification.domain.NotificationEventLog;
import com.lul.shop.notification.domain.NotificationEventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class OrderConfirmationNotificationService {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationNotificationService.class);

    private final NotificationEventLogRepository notificationEventLogRepository;
    private final EmailSender emailSender;

    public OrderConfirmationNotificationService(NotificationEventLogRepository notificationEventLogRepository,
                                                EmailSender emailSender) {
        this.notificationEventLogRepository = notificationEventLogRepository;
        this.emailSender = emailSender;
    }

    @Transactional
    public void handleOrderPaid(OrderPaidNotificationMessage message) {
        Objects.requireNonNull(message, "message must not be null");

        if (notificationEventLogRepository.existsByEventId(message.eventId())) {

            log.info(
                    "action=notification.duplicate_skipped eventId={} orderId={} paymentId={}",
                    message.eventId(),
                    message.orderId(),
                    message.paymentId()
            );

            return;
        }

        emailSender.sendOrderConfirmation(new OrderConfirmationEmail(
                message.userId(),
                message.orderId(),
                message.paymentId()
        ));

        NotificationEventLog eventLog = NotificationEventLog.recordProcessedEvent(
                message.eventId(),
                message.eventType(),
                message.aggregateType(),
                message.aggregateId(),
                message.payload(),
                Instant.now()
        );

        notificationEventLogRepository.save(eventLog);

        log.info(
                "action=notification.processed eventId={} orderId={} paymentId={} userId={}",
                message.eventId(),
                message.orderId(),
                message.paymentId(),
                message.userId()
        );
    }
}