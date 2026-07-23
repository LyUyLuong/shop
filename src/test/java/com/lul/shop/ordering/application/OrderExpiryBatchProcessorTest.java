package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.port.OrderInventoryClient;
import com.lul.shop.ordering.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static com.lul.shop.ordering.support.OrderingTestFixtures.createMockOrder;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderExpiryBatchProcessorTest {

    private static final Instant NOW =
            Instant.parse("2026-07-18T06:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Test
    void shouldExpireClaimedAggregateWithoutLoadingOrderAgain() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = expiredOrder();

        when(orderRepository.claimExpiredForUpdate(NOW, 10))
                .thenReturn(List.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(inventoryClient.restoreStock(PRODUCT_ID, 2))
                .thenReturn(true);

        OrderLifecycleService lifecycleService =
                new OrderLifecycleService(
                        orderRepository,
                        historyRepository,
                        inventoryClient,
                        CLOCK
                );

        OrderExpiryBatchProcessor processor =
                new OrderExpiryBatchProcessor(
                        orderRepository,
                        lifecycleService,
                        CLOCK
                );

        assertThat(processor.expireNextBatch(10)).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getInventoryReleasedAt()).isEqualTo(NOW);

        verify(orderRepository)
                .claimExpiredForUpdate(NOW, 10);
        verify(orderRepository, never())
                .findByIdForUpdate(any(UUID.class));
        verify(inventoryClient)
                .restoreStock(PRODUCT_ID, 2);
        verify(orderRepository).save(order);
        verify(historyRepository).save(argThat(history ->
                history.getOrderId().equals(order.getId())
                        && history.getActorType()
                        == OrderStatusChangeActorType.SYSTEM
                        && history.getFromStatus()
                        == OrderStatus.PENDING_PAYMENT
                        && history.getToStatus()
                        == OrderStatus.EXPIRED
        ));
    }

    @Test
    void shouldRejectNonPositiveBatchSizeBeforeClaiming() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        OrderExpiryBatchProcessor processor =
                new OrderExpiryBatchProcessor(
                        orderRepository,
                        lifecycleService,
                        CLOCK
                );

        assertThatThrownBy(() -> processor.expireNextBatch(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("batchSize must be greater than 0");

        verifyNoInteractions(orderRepository, lifecycleService);
    }

    private static Order expiredOrder() {
        return createMockOrder(
                USER_ID,
                List.of(OrderItem.create(
                        PRODUCT_ID,
                        "EXPIRY-SKU",
                        "Expiry Product",
                        null,
                        new BigDecimal("100000.00"),
                        2
                )),
                NOW.minusSeconds(30 * 60)
        );
    }
}
