package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.application.port.OrderInventoryClient;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderLifecycleServiceTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ADMIN_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_A_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID PRODUCT_B_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-17T01:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldLockOrderChangeAdminStatusAndRecordHistory() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = paidOrder();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        OrderLifecycleService service = new OrderLifecycleService(
                orderRepository,
                historyRepository,
                inventoryClient,
                CLOCK
        );

        ChangeOrderStatusCommand command =
                new ChangeOrderStatusCommand(
                        order.getId(),
                        ADMIN_ID,
                        OrderStatus.PACKING,
                        "Start packing"
                );

        Order result =
                service.changeStatusAsAdmin(command);

        assertThat(result).isSameAs(order);
        assertThat(result.getStatus())
                .isEqualTo(OrderStatus.PACKING);
        assertThat(result.getInventoryReleasedAt()).isNull();

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
                .isEqualTo(OrderStatus.PAID);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.PACKING);
        assertThat(history.getActorType())
                .isEqualTo(OrderStatusChangeActorType.ADMIN);
        assertThat(history.getActorUserId())
                .isEqualTo(ADMIN_ID);
        assertThat(history.getReason())
                .isEqualTo("Start packing");
    }

    @Test
    void shouldCancelOrderRestoreAggregatedInventoryAndMarkRelease() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = pendingOrderWithDuplicateProducts();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );
        when(inventoryClient.restoreStock(PRODUCT_A_ID, 5))
                .thenReturn(true);
        when(inventoryClient.restoreStock(PRODUCT_B_ID, 1))
                .thenReturn(true);

        OrderLifecycleService service = new OrderLifecycleService(
                orderRepository,
                historyRepository,
                inventoryClient,
                CLOCK
        );

        Order result = service.changeStatusAsAdmin(
                new ChangeOrderStatusCommand(
                        order.getId(),
                        ADMIN_ID,
                        OrderStatus.CANCELLED,
                        "Customer contacted support"
                )
        );

        assertThat(result.getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.getInventoryReleasedAt())
                .isEqualTo(NOW);

        verify(inventoryClient)
                .restoreStock(PRODUCT_A_ID, 5);
        verify(inventoryClient)
                .restoreStock(PRODUCT_B_ID, 1);
        verify(orderRepository).save(order);

        ArgumentCaptor<OrderStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(
                        OrderStatusHistory.class
                );

        verify(historyRepository)
                .save(historyCaptor.capture());

        assertThat(historyCaptor.getValue().getFromStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(historyCaptor.getValue().getToStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyCaptor.getValue().getActorType())
                .isEqualTo(OrderStatusChangeActorType.ADMIN);
    }

    @Test
    void shouldRejectInvalidTransitionBeforeRestoringInventory() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = paidOrder();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        OrderLifecycleService service = new OrderLifecycleService(
                orderRepository,
                historyRepository,
                inventoryClient,
                CLOCK
        );

        assertThatThrownBy(
                () -> service.changeStatusAsAdmin(
                        new ChangeOrderStatusCommand(
                                order.getId(),
                                ADMIN_ID,
                                OrderStatus.COMPLETED,
                                "Skip fulfillment"
                        )
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode
                                        .INVALID_ORDER_STATUS_TRANSITION
                        )
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.PAID);

        verifyNoInteractions(inventoryClient);
        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(historyRepository);
    }

    @Test
    void shouldRejectMissingOrderBeforeAnyMutation() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        UUID missingOrderId = UUID.randomUUID();

        when(orderRepository.findByIdForUpdate(missingOrderId))
                .thenReturn(Optional.empty());

        OrderLifecycleService service = new OrderLifecycleService(
                orderRepository,
                historyRepository,
                inventoryClient,
                CLOCK
        );

        assertThatThrownBy(
                () -> service.changeStatusAsAdmin(
                        new ChangeOrderStatusCommand(
                                missingOrderId,
                                ADMIN_ID,
                                OrderStatus.CANCELLED,
                                "Cancel missing order"
                        )
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode.ORDER_NOT_FOUND
                        )
                );

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(inventoryClient);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void shouldFailWithoutSavingWhenInventoryCannotBeRestored() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = pendingOrderWithDuplicateProducts();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        when(inventoryClient.restoreStock(PRODUCT_A_ID, 5))
                .thenReturn(true);
        when(inventoryClient.restoreStock(PRODUCT_B_ID, 1))
                .thenReturn(false);

        OrderLifecycleService service = new OrderLifecycleService(
                orderRepository,
                historyRepository,
                inventoryClient,
                CLOCK
        );

        assertThatThrownBy(
                () -> service.changeStatusAsAdmin(
                        new ChangeOrderStatusCommand(
                                order.getId(),
                                ADMIN_ID,
                                OrderStatus.CANCELLED,
                                "Cancel order"
                        )
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode
                                        .ORDER_INVENTORY_RESTORE_FAILED
                        )
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getInventoryReleasedAt()).isNull();

        verify(inventoryClient)
                .restoreStock(PRODUCT_A_ID, 5);
        verify(inventoryClient)
                .restoreStock(PRODUCT_B_ID, 1);
        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(historyRepository);
    }

    @Test
    void shouldLockOwnedOrderMarkPaidAndRecordPaymentHistory() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = pendingOrderWithDuplicateProducts();

        when(
                orderRepository.findByIdAndUserIdForUpdate(
                        order.getId(),
                        USER_ID
                )
        ).thenReturn(Optional.of(order));

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        OrderLifecycleService service =
                new OrderLifecycleService(
                        orderRepository,
                        historyRepository,
                        inventoryClient,
                        CLOCK
                );

        Order result = service.markPaidByPayment(
                USER_ID,
                order.getId()
        );

        assertThat(result.getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(result.getInventoryReleasedAt()).isNull();

        verify(orderRepository)
                .findByIdAndUserIdForUpdate(
                        order.getId(),
                        USER_ID
                );
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
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(history.getActorType())
                .isEqualTo(
                        OrderStatusChangeActorType.PAYMENT
                );
        assertThat(history.getActorUserId()).isNull();
        assertThat(history.getReason())
                .isEqualTo("Payment succeeded");
    }

    @Test
    void shouldRejectPaymentAtExactDeadline() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = pendingOrderWithDuplicateProducts();

        when(
                orderRepository.findByIdAndUserIdForUpdate(
                        order.getId(),
                        USER_ID
                )
        ).thenReturn(Optional.of(order));

        Clock deadlineClock = Clock.fixed(
                order.getExpiresAt(),
                ZoneOffset.UTC
        );

        OrderLifecycleService service =
                new OrderLifecycleService(
                        orderRepository,
                        historyRepository,
                        inventoryClient,
                        deadlineClock
                );

        assertThatThrownBy(
                () -> service.markPaidByPayment(
                        USER_ID,
                        order.getId()
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode.ORDER_NOT_PAYABLE
                        )
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(historyRepository);
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void shouldLockExpiredOrderRestoreInventoryAndRecordSystemHistory() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = pendingOrderWithDuplicateProducts();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );
        when(inventoryClient.restoreStock(PRODUCT_A_ID, 5))
                .thenReturn(true);
        when(inventoryClient.restoreStock(PRODUCT_B_ID, 1))
                .thenReturn(true);

        Clock expiryClock = Clock.fixed(
                order.getExpiresAt(),
                ZoneOffset.UTC
        );

        OrderLifecycleService service =
                new OrderLifecycleService(
                        orderRepository,
                        historyRepository,
                        inventoryClient,
                        expiryClock
                );

        Order result =
                service.expireBySystem(order.getId());

        assertThat(result.getStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(result.getInventoryReleasedAt())
                .isEqualTo(order.getExpiresAt());

        verify(orderRepository)
                .findByIdForUpdate(order.getId());
        verify(inventoryClient)
                .restoreStock(PRODUCT_A_ID, 5);
        verify(inventoryClient)
                .restoreStock(PRODUCT_B_ID, 1);
        verify(orderRepository).save(order);

        ArgumentCaptor<OrderStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(
                        OrderStatusHistory.class
                );

        verify(historyRepository)
                .save(historyCaptor.capture());

        OrderStatusHistory history =
                historyCaptor.getValue();

        assertThat(history.getFromStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(history.getActorType())
                .isEqualTo(OrderStatusChangeActorType.SYSTEM);
        assertThat(history.getActorUserId()).isNull();
        assertThat(history.getReason())
                .isEqualTo("Payment deadline expired");
    }

    @Test
    void shouldRejectSystemExpiryBeforeDeadline() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = pendingOrderWithDuplicateProducts();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        OrderLifecycleService service =
                new OrderLifecycleService(
                        orderRepository,
                        historyRepository,
                        inventoryClient,
                        CLOCK
                );

        assertThatThrownBy(
                () -> service.expireBySystem(order.getId())
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode.ORDER_NOT_EXPIRABLE
                        )
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getInventoryReleasedAt()).isNull();

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(historyRepository);
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void shouldRejectSystemExpiryAfterPaymentWon() {
        OrderRepository orderRepository =
                mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepository =
                mock(OrderStatusHistoryRepository.class);
        OrderInventoryClient inventoryClient =
                mock(OrderInventoryClient.class);

        Order order = paidOrder();

        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        Clock afterDeadlineClock = Clock.fixed(
                order.getExpiresAt().plusSeconds(1),
                ZoneOffset.UTC
        );

        OrderLifecycleService service =
                new OrderLifecycleService(
                        orderRepository,
                        historyRepository,
                        inventoryClient,
                        afterDeadlineClock
                );

        assertThatThrownBy(
                () -> service.expireBySystem(order.getId())
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode.ORDER_NOT_EXPIRABLE
                        )
                );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(order.getInventoryReleasedAt()).isNull();

        verify(orderRepository, never())
                .save(any(Order.class));
        verifyNoInteractions(historyRepository);
        verifyNoInteractions(inventoryClient);
    }

    private static Order paidOrder() {
        Order order = Order.create(
                USER_ID,
                List.of(
                        orderItem(
                                PRODUCT_A_ID,
                                "SKU-A",
                                "Product A",
                                1
                        )
                ),
                NOW
        );

        order.markPaid();

        return order;
    }

    private static Order pendingOrderWithDuplicateProducts() {
        return Order.create(
                USER_ID,
                List.of(
                        orderItem(
                                PRODUCT_A_ID,
                                "SKU-A",
                                "Product A",
                                2
                        ),
                        orderItem(
                                PRODUCT_A_ID,
                                "SKU-A",
                                "Product A",
                                3
                        ),
                        orderItem(
                                PRODUCT_B_ID,
                                "SKU-B",
                                "Product B",
                                1
                        )
                ),
                NOW
        );
    }

    private static OrderItem orderItem(
            UUID productId,
            String sku,
            String name,
            int quantity
    ) {
        return OrderItem.create(
                productId,
                sku,
                name,
                null,
                new BigDecimal("100000.00"),
                quantity
        );
    }
}