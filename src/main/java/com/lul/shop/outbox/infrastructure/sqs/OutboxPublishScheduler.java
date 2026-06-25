package com.lul.shop.outbox.infrastructure.publisher;

import com.lul.shop.outbox.application.OutboxPublisherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublishScheduler.class);

    private final OutboxPublisherService outboxPublisherService;
    private final OutboxPublisherProperties properties;

    public OutboxPublishScheduler(OutboxPublisherService outboxPublisherService,
                                  OutboxPublisherProperties properties) {
        this.outboxPublisherService = outboxPublisherService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.publisher.fixed-delay-ms:10000}")
    public void publishOutboxEvents() {
        if (!properties.enabled()) {
            return;
        }

        if (!properties.hasQueueUrl()) {
            log.warn("Outbox publisher is enabled but app.outbox.publisher.queue-url is empty");
            return;
        }

        int publishedCount = outboxPublisherService.publishBatch(
                properties.maxBatchSize(),
                properties.maxRetryCount()
        );

        if (publishedCount > 0) {
            log.info("Published {} outbox event(s)", publishedCount);
        }
    }
}