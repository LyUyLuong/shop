package com.lul.shop.ordering.infrastructure.scheduler;

import com.lul.shop.ordering.application.OrderExpiryBatchProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderExpirySchedulerTest {

    @Test
    void shouldNotRunBatchWhenSchedulerIsDisabled() {
        OrderExpiryBatchProcessor batchProcessor =
                mock(OrderExpiryBatchProcessor.class);

        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(
                batchProcessor,
                properties(false)
        );

        scheduler.expireOverdueOrders();

        verifyNoInteractions(batchProcessor);
    }

    @Test
    void shouldRunExactlyOneConfiguredBatchWhenEnabled() {
        OrderExpiryBatchProcessor batchProcessor =
                mock(OrderExpiryBatchProcessor.class);

        when(batchProcessor.expireNextBatch(20))
                .thenReturn(3);

        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(
                batchProcessor,
                properties(true)
        );

        scheduler.expireOverdueOrders();

        verify(batchProcessor).expireNextBatch(20);
        verifyNoMoreInteractions(batchProcessor);
    }

    @Test
    void shouldPropagateBatchFailureAfterLoggingIt() {
        OrderExpiryBatchProcessor batchProcessor =
                mock(OrderExpiryBatchProcessor.class);

        RuntimeException failure =
                new IllegalStateException("forced expiry failure");

        when(batchProcessor.expireNextBatch(20))
                .thenThrow(failure);

        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(
                batchProcessor,
                properties(true)
        );

        assertThatThrownBy(scheduler::expireOverdueOrders)
                .isSameAs(failure);

        verify(batchProcessor).expireNextBatch(20);
        verifyNoMoreInteractions(batchProcessor);
    }

    private static OrderExpirySchedulerProperties properties(
            boolean enabled
    ) {
        return new OrderExpirySchedulerProperties(
                enabled,
                20,
                60_000L,
                30_000L
        );
    }
}
