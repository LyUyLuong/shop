package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderCodCollectionTransitionResult;
import com.lul.shop.ordering.application.port.OrderInventoryClient;
import com.lul.shop.ordering.domain.*;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderCodCollectionLifecycleServiceTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ADMIN_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-27T01:00:00Z");

    private final OrderRepository orderRepository =
            mock(OrderRepository.class);

    private final OrderStatusHistoryRepository historyRepository =
            mock(OrderStatusHistoryRepository.class);

    private final OrderInventoryClient inventoryClient =
            mock(OrderInventoryClient.class);

    private final OrderLifecycleService service =
            new OrderLifecycleService(
                    orderRepository,
                    historyRepository,
                    inventoryClient,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void shouldCollectShippedCodOrderAndRecordAdminHistory() {
        Order order = shippedOrder(OrderPaymentMode.COD);

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        OrderCodCollectionTransitionResult result =
                service.collectCodByAdmin(
                        ADMIN_ID,
                        order.getId()
                );

        assertThat(result.orderId())
                .isEqualTo(order.getId());
        assertThat(result.userId())
                .isEqualTo(USER_ID);
        assertThat(result.totalAmount())
                .isEqualByComparingTo("230000.00");
        assertThat(result.outcome()).isEqualTo(
                OrderCodCollectionTransitionResult
                        .Outcome.NEWLY_COLLECTED
        );
        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);

        verify(orderRepository)
                .findByIdForUpdate(order.getId());
        verify(orderRepository).save(order);
        verifyNoInteractions(inventoryClient);

        ArgumentCaptor<OrderStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(
                        OrderStatusHistory.class
                );

        verify(historyRepository)
                .save(historyCaptor.capture());

        OrderStatusHistory history =
                historyCaptor.getValue();

        assertThat(history.getOrderId())
                .isEqualTo(order.getId());
        assertThat(history.getFromStatus())
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(history.getActorType())
                .isEqualTo(
                        OrderStatusChangeActorType.ADMIN
                );
        assertThat(history.getActorUserId())
                .isEqualTo(ADMIN_ID);
        assertThat(history.getReason())
                .isEqualTo(
                        "Cash on delivery collected"
                );
    }

    @Test
    void shouldReturnAlreadyCollectedWithoutDuplicateWrites() {
        Order order = shippedOrder(OrderPaymentMode.COD);
        order.completeCodCollection();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        OrderCodCollectionTransitionResult result =
                service.collectCodByAdmin(
                        ADMIN_ID,
                        order.getId()
                );

        assertThat(result.outcome()).isEqualTo(
                OrderCodCollectionTransitionResult
                        .Outcome.ALREADY_COLLECTED
        );
        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);

        verify(orderRepository)
                .findByIdForUpdate(order.getId());
        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(
                historyRepository,
                inventoryClient
        );
    }

    @Test
    void shouldRejectCollectionForNonCodOrder() {
        Order order = shippedOrder(OrderPaymentMode.MOCK);

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        assertThatThrownBy(() ->
                service.collectCodByAdmin(
                        ADMIN_ID,
                        order.getId()
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        OrderingErrorCode
                                .COD_COLLECTION_NOT_ALLOWED
                )
        );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.SHIPPED);

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(
                historyRepository,
                inventoryClient
        );
    }

    @Test
    void shouldRejectCodCollectionBeforeShipment() {
        Order order = createOrder(OrderPaymentMode.COD);

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        assertThatThrownBy(() ->
                service.collectCodByAdmin(
                        ADMIN_ID,
                        order.getId()
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        OrderingErrorCode
                                .COD_COLLECTION_NOT_ALLOWED
                )
        );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(
                historyRepository,
                inventoryClient
        );
    }

    @Test
    void shouldRejectMissingOrderBeforeMutation() {
        UUID missingOrderId = UUID.fromString(
                "44444444-4444-4444-8444-444444444444"
        );

        when(orderRepository.findByIdForUpdate(missingOrderId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.collectCodByAdmin(
                        ADMIN_ID,
                        missingOrderId
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        OrderingErrorCode.ORDER_NOT_FOUND
                )
        );

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(
                historyRepository,
                inventoryClient
        );
    }

    private static Order shippedOrder(
            OrderPaymentMode paymentMode
    ) {
        Order order = createOrder(paymentMode);

        if (paymentMode == OrderPaymentMode.MOCK) {
            order.markPaid();
        }

        order.changeStatus(OrderStatus.PACKING);
        order.changeStatus(OrderStatus.SHIPPED);

        return order;
    }

    private static Order createOrder(
            OrderPaymentMode paymentMode
    ) {
        return Order.create(
                USER_ID,
                List.of(OrderItem.create(
                        PRODUCT_ID,
                        "COD-SKU-001",
                        "COD Product",
                        null,
                        new BigDecimal("100000.00"),
                        2
                )),
                fulfillment(),
                paymentMode,
                OrderAmounts.calculate(
                        new BigDecimal("200000.00"),
                        new BigDecimal("30000.00")
                ),
                NOW
        );
    }
}