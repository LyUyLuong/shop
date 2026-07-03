package com.lul.shop.outbox.application;

import com.lul.shop.outbox.application.port.OutboxMessagePublisher;
import com.lul.shop.outbox.domain.OutboxEvent;
import com.lul.shop.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class OutboxPublisherService {

    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessagePublisher outboxMessagePublisher;
    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  OutboxMessagePublisher outboxMessagePublisher,
                                  Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxMessagePublisher = outboxMessagePublisher;
        this.clock = clock;
    }

    @Transactional
    public int publishBatch(int batchSize, int maxRetryCount) {
        requirePositive(batchSize, "batchSize");
        requirePositive(maxRetryCount, "maxRetryCount");

        List<OutboxEvent> events = outboxEventRepository.findPublishableEvents(batchSize, maxRetryCount);

        int publishedCount = 0;

        for (OutboxEvent event : events) {
            if (publishEvent(event, maxRetryCount)) {
                publishedCount++;
            }
        }

        return publishedCount;
    }

    private boolean publishEvent(OutboxEvent event, int maxRetryCount) {
        Objects.requireNonNull(event, "event must not be null");

        try {
            outboxMessagePublisher.publish(event);
            event.markPublished(Instant.now(clock));
            outboxEventRepository.save(event);

            log.info(
                    "action=outbox.published eventId={} eventType={} aggregateType={} aggregateId={} retryCount={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getRetryCount()
            );

            return true;
        } catch (RuntimeException ex) {
            event.markPublishFailed(toLastError(ex), maxRetryCount);
            outboxEventRepository.save(event);

            log.warn(
                    "action=outbox.publish_failed eventId={} eventType={} aggregateType={} aggregateId={} retryCount={} maxRetryCount={} statusAfterFailure={} lastError={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getRetryCount(),
                    maxRetryCount,
                    event.getStatus(),
                    event.getLastError()
            );

            return false;
        }
    }

    private String toLastError(RuntimeException ex) {
        String message = ex.getMessage();

        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }

        String trimmed = message.trim();

        if (trimmed.length() <= LAST_ERROR_MAX_LENGTH) {
            return trimmed;
        }

        return trimmed.substring(0, LAST_ERROR_MAX_LENGTH);
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }

        return value;
    }
}