package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.application.dto.OrderPaymentTransitionResult;
import com.lul.shop.ordering.application.port.OrderInventoryClient;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class OrderLifecycleService {

    private static final Logger log =
            LoggerFactory.getLogger(OrderLifecycleService.class);

    private static final String PAYMENT_HISTORY_REASON =
            "Payment succeeded";

    private static final String SYSTEM_EXPIRY_HISTORY_REASON =
            "Payment deadline expired";

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderInventoryClient inventoryClient;
    private final Clock clock;

    public OrderLifecycleService(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository historyRepository,
            OrderInventoryClient inventoryClient,
            Clock clock
    ) {
        this.orderRepository = Objects.requireNonNull(
                orderRepository,
                "orderRepository must not be null"
        );
        this.historyRepository = Objects.requireNonNull(
                historyRepository,
                "historyRepository must not be null"
        );
        this.inventoryClient = Objects.requireNonNull(
                inventoryClient,
                "inventoryClient must not be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock must not be null"
        );
    }

    @Transactional
    public Order changeStatusAsAdmin(
            ChangeOrderStatusCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        Order order = orderRepository
                .findByIdForUpdate(command.orderId())
                .orElseThrow(() ->
                        new BusinessException(
                                OrderingErrorCode.ORDER_NOT_FOUND
                        )
                );

        requireValidTransition(
                order,
                command.targetStatus()
        );

        Instant inventoryReleasedAt = null;

        if (command.targetStatus() == OrderStatus.CANCELLED) {
            inventoryReleasedAt = Instant.now(clock);
            restoreInventory(order);
        }

        OrderStatus fromStatus =
                order.changeStatus(command.targetStatus());

        if (inventoryReleasedAt != null) {
            order.markInventoryReleased(inventoryReleasedAt);
        }

        Order savedOrder = orderRepository.save(order);

        historyRepository.save(
                OrderStatusHistory.recordAdminChange(
                        savedOrder.getId(),
                        command.adminUserId(),
                        fromStatus,
                        savedOrder.getStatus(),
                        command.reason()
                )
        );

        log.info(
                "action=order.status_changed "
                        + "orderId={} actorType=ADMIN actorUserId={} "
                        + "fromStatus={} toStatus={} "
                        + "inventoryReleased={} result=success",
                savedOrder.getId(),
                command.adminUserId(),
                fromStatus,
                savedOrder.getStatus(),
                savedOrder.isInventoryReleased()
        );

        return savedOrder;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderPaymentTransitionResult markPaidByPayment(
            UUID userId,
            UUID orderId
    ) {
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );

        Order order = orderRepository
                .findByIdAndUserIdForUpdate(
                        orderId,
                        userId
                )
                .orElseThrow(() ->
                        new BusinessException(
                                OrderingErrorCode.ORDER_NOT_FOUND
                        )
                );

        if (order.getStatus() == OrderStatus.PAID) {
            log.info(
                    "action=order.payment_transition "
                            + "orderId={} userId={} status={} "
                            + "outcome={} result=success",
                    order.getId(),
                    userId,
                    order.getStatus(),
                    OrderPaymentTransitionResult.Outcome.ALREADY_PAID
            );

            return toPaymentTransitionResult(
                    order,
                    OrderPaymentTransitionResult.Outcome.ALREADY_PAID
            );
        }

        Instant paidAt = Instant.now(clock);

        if (!order.isPayableAt(paidAt)) {
            throw new BusinessException(
                    OrderingErrorCode.ORDER_NOT_PAYABLE
            );
        }

        OrderStatus fromStatus = order.getStatus();

        order.markPaid();

        Order savedOrder = orderRepository.save(order);

        historyRepository.save(
                OrderStatusHistory.recordPaymentChange(
                        savedOrder.getId(),
                        fromStatus,
                        savedOrder.getStatus(),
                        PAYMENT_HISTORY_REASON
                )
        );

        log.info(
                "action=order.status_changed "
                        + "orderId={} actorType=PAYMENT userId={} "
                        + "fromStatus={} toStatus={} outcome={} "
                        + "result=success",
                savedOrder.getId(),
                userId,
                fromStatus,
                savedOrder.getStatus(),
                OrderPaymentTransitionResult.Outcome.NEWLY_PAID
        );

        return toPaymentTransitionResult(
                savedOrder,
                OrderPaymentTransitionResult.Outcome.NEWLY_PAID
        );
    }

    @Transactional
    public Order expireBySystem(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(
                        OrderingErrorCode.ORDER_NOT_FOUND
                ));

        return expireLockedOrderBySystem(
                order,
                Instant.now(clock)
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Order expireClaimedBySystem(
            Order claimedOrder,
            Instant expiredAt
    ) {
        Objects.requireNonNull(
                claimedOrder,
                "claimedOrder must not be null"
        );
        Objects.requireNonNull(
                expiredAt,
                "expiredAt must not be null"
        );

        return expireLockedOrderBySystem(
                claimedOrder,
                expiredAt
        );
    }

    private Order expireLockedOrderBySystem(
            Order order,
            Instant expiredAt
    ) {
        if (!order.isExpiredAt(expiredAt)) {
            throw new BusinessException(
                    OrderingErrorCode.ORDER_NOT_EXPIRABLE
            );
        }

        restoreInventory(order);

        OrderStatus fromStatus = order.expire(expiredAt);
        order.markInventoryReleased(expiredAt);

        Order savedOrder = orderRepository.save(order);

        historyRepository.save(
                OrderStatusHistory.recordSystemChange(
                        savedOrder.getId(),
                        fromStatus,
                        savedOrder.getStatus(),
                        SYSTEM_EXPIRY_HISTORY_REASON
                )
        );

        log.info(
                "action=order.status_changed orderId={} actorType=SYSTEM "
                        + "fromStatus={} toStatus={} inventoryReleased={} result=success",
                savedOrder.getId(),
                fromStatus,
                savedOrder.getStatus(),
                savedOrder.isInventoryReleased()
        );

        return savedOrder;
    }

    private OrderPaymentTransitionResult toPaymentTransitionResult(
            Order order,
            OrderPaymentTransitionResult.Outcome outcome
    ) {
        return new OrderPaymentTransitionResult(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                outcome
        );
    }

    private void requireValidTransition(
            Order order,
            OrderStatus targetStatus
    ) {
        if (!order.canMoveTo(targetStatus)) {
            throw new BusinessException(
                    OrderingErrorCode.INVALID_ORDER_STATUS_TRANSITION,
                    "order status cannot move from "
                            + order.getStatus()
                            + " to "
                            + targetStatus
            );
        }
    }

    private void restoreInventory(Order order) {
        Map<UUID, Integer> quantitiesByProductId =
                aggregateItemQuantities(order);

        for (Map.Entry<UUID, Integer> entry
                : quantitiesByProductId.entrySet()) {
            boolean restored = inventoryClient.restoreStock(
                    entry.getKey(),
                    entry.getValue()
            );

            if (!restored) {
                throw new BusinessException(
                        OrderingErrorCode
                                .ORDER_INVENTORY_RESTORE_FAILED,
                        "productId=" + entry.getKey()
                );
            }
        }
    }

    private Map<UUID, Integer> aggregateItemQuantities(
            Order order
    ) {
        Map<UUID, Integer> quantitiesByProductId =
                new LinkedHashMap<>();

        for (OrderItem item : order.getItems()) {
            quantitiesByProductId.merge(
                    item.getProductId(),
                    item.getQuantity(),
                    (current, added) ->
                            Math.addExact(current, added)
            );
        }

        return quantitiesByProductId;
    }
}