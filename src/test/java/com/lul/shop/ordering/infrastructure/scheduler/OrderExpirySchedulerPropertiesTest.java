package com.lul.shop.ordering.infrastructure.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OrderExpirySchedulerPropertiesTest {

    @Test
    void shouldUseSafeDefaultsWhenValuesAreAbsent() {
        OrderExpirySchedulerProperties properties =
                new OrderExpirySchedulerProperties(
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.batchSize()).isEqualTo(20);
        assertThat(properties.initialDelayMs()).isEqualTo(60_000L);
        assertThat(properties.fixedDelayMs()).isEqualTo(30_000L);
    }

    @Test
    void shouldPreserveExplicitValidValues() {
        OrderExpirySchedulerProperties properties =
                new OrderExpirySchedulerProperties(
                        true,
                        10,
                        5_000L,
                        15_000L
                );

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.batchSize()).isEqualTo(10);
        assertThat(properties.initialDelayMs()).isEqualTo(5_000L);
        assertThat(properties.fixedDelayMs()).isEqualTo(15_000L);
    }

    @Test
    void shouldRejectNonPositiveBatchSize() {
        assertThatThrownBy(() ->
                new OrderExpirySchedulerProperties(
                        true,
                        0,
                        5_000L,
                        15_000L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("batchSize must be greater than 0");
    }

    @Test
    void shouldRejectNegativeInitialDelay() {
        assertThatThrownBy(() ->
                new OrderExpirySchedulerProperties(
                        true,
                        10,
                        -1L,
                        15_000L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("initialDelayMs must not be negative");
    }

    @Test
    void shouldRejectNonPositiveFixedDelay() {
        assertThatThrownBy(() ->
                new OrderExpirySchedulerProperties(
                        true,
                        10,
                        5_000L,
                        0L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fixedDelayMs must be greater than 0");
    }
}
