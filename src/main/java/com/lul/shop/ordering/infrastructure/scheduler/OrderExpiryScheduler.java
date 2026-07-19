package com.lul.shop.ordering.infrastructure.scheduler;

import com.lul.shop.ordering.application.OrderExpiryBatchProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class OrderExpiryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderExpiryBatchProcessor batchProcessor;
    private final OrderExpirySchedulerProperties properties;

    public OrderExpiryScheduler(
            OrderExpiryBatchProcessor batchProcessor,
            OrderExpirySchedulerProperties properties
    ) {
        this.batchProcessor = Objects.requireNonNull(
                batchProcessor,
                "batchProcessor must not be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
    }

    @Scheduled(
            initialDelayString =
                    "${app.ordering.expiry.initial-delay-ms:60000}",
            fixedDelayString =
                    "${app.ordering.expiry.fixed-delay-ms:30000}"
    )
    public void expireOverdueOrders() {
        if (!properties.enabled()) {
            return;
        }

        try {
            int expiredCount = batchProcessor.expireNextBatch(
                    properties.batchSize()
            );

            if (expiredCount > 0) {
                log.info(
                        "action=order.expiry_scheduler_completed "
                                + "batchSize={} expiredCount={} result=success",
                        properties.batchSize(),
                        expiredCount
                );
            }
        } catch (RuntimeException ex) {
            log.error(
                    "action=order.expiry_scheduler_failed "
                            + "batchSize={} reason={} result=failure",
                    properties.batchSize(),
                    failureReason(ex),
                    ex
            );

            throw ex;
        }
    }

    private String failureReason(RuntimeException exception) {
        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return message.trim();
    }
}
