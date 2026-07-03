package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.AdminOrderDetailResult;
import com.lul.shop.ordering.application.dto.AdminOrderSummaryResult;
import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.application.dto.OrderItemResult;
import com.lul.shop.ordering.application.dto.OrderStatusHistoryResult;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderSearchCriteria;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderOperationsService {

    private static final Logger log = LoggerFactory.getLogger(OrderOperationsService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public OrderOperationsService(OrderRepository orderRepository,
                                  OrderStatusHistoryRepository orderStatusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    public PageResult<AdminOrderSummaryResult> searchOrders(OrderSearchCriteria criteria, PageQuery pageQuery) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Objects.requireNonNull(pageQuery, "pageQuery must not be null");

        return orderRepository.searchSummaries(criteria, pageQuery)
                .map(this::toAdminSummaryResult);
    }

    public AdminOrderDetailResult getOrder(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        Order order = getOrderOrThrow(orderId);

        return toAdminDetailResult(order);
    }

    public List<OrderStatusHistoryResult> getStatusHistory(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");

        getOrderOrThrow(orderId);

        return orderStatusHistoryRepository.findTimelineByOrderId(orderId)
                .stream()
                .map(this::toStatusHistoryResult)
                .toList();
    }

    @Transactional
    public AdminOrderDetailResult changeStatus(ChangeOrderStatusCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Order order = getOrderOrThrow(command.orderId());

        OrderStatus fromStatus = changeOrderStatus(order, command.targetStatus());

        Order savedOrder = orderRepository.save(order);

        OrderStatusHistory history = OrderStatusHistory.recordAdminChange(
                savedOrder.getId(),
                command.adminUserId(),
                fromStatus,
                savedOrder.getStatus(),
                command.reason()
        );

        orderStatusHistoryRepository.save(history);

        log.info(
                "action=order.status_changed orderId={} adminUserId={} fromStatus={} toStatus={} result=success",
                savedOrder.getId(),
                command.adminUserId(),
                fromStatus,
                savedOrder.getStatus()
        );

        return toAdminDetailResult(savedOrder);
    }

    private OrderStatus changeOrderStatus(Order order, OrderStatus targetStatus) {
        try {
            return order.changeStatus(targetStatus);
        } catch (IllegalStateException ex) {
            throw new BusinessException(OrderingErrorCode.INVALID_ORDER_STATUS_TRANSITION, ex.getMessage());
        }
    }

    private Order getOrderOrThrow(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderingErrorCode.ORDER_NOT_FOUND));
    }

    private AdminOrderSummaryResult toAdminSummaryResult(OrderSummary summary) {
        return new AdminOrderSummaryResult(
                summary.id(),
                summary.userId(),
                summary.status(),
                summary.totalAmount(),
                summary.itemCount(),
                summary.createdAt(),
                summary.updatedAt()
        );
    }

    private AdminOrderDetailResult toAdminDetailResult(Order order) {
        List<OrderItemResult> items = order.getItems()
                .stream()
                .map(this::toItemResult)
                .toList();

        return new AdminOrderDetailResult(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private OrderItemResult toItemResult(OrderItem item) {
        return new OrderItemResult(
                item.getId(),
                item.getProductId(),
                item.getProductSku(),
                item.getProductName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineTotal()
        );
    }

    private OrderStatusHistoryResult toStatusHistoryResult(OrderStatusHistory history) {
        return new OrderStatusHistoryResult(
                history.getId(),
                history.getOrderId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getActorType(),
                history.getActorUserId(),
                history.getReason(),
                history.getCreatedAt()
        );
    }
}