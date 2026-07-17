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
import com.lul.shop.ordering.domain.OrderStatusHistory;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.ordering.domain.OrderSummary;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class OrderOperationsService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderLifecycleService lifecycleService;

    public OrderOperationsService(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository historyRepository,
            OrderLifecycleService lifecycleService
    ) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.lifecycleService = lifecycleService;
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryResult> searchOrders(
            OrderSearchCriteria criteria,
            PageQuery pageQuery
    ) {
        Objects.requireNonNull(
                criteria,
                "criteria must not be null"
        );
        Objects.requireNonNull(
                pageQuery,
                "pageQuery must not be null"
        );

        return orderRepository
                .searchSummaries(criteria, pageQuery)
                .map(this::toAdminSummaryResult);
    }

    @Transactional(readOnly = true)
    public AdminOrderDetailResult getOrder(UUID orderId) {
        Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );

        return toAdminDetailResult(
                getOrderOrThrow(orderId)
        );
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResult> getStatusHistory(
            UUID orderId
    ) {
        Objects.requireNonNull(
                orderId,
                "orderId must not be null"
        );

        getOrderOrThrow(orderId);

        return historyRepository
                .findTimelineByOrderId(orderId)
                .stream()
                .map(this::toStatusHistoryResult)
                .toList();
    }

    public AdminOrderDetailResult changeStatus(
            ChangeOrderStatusCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        Order changedOrder =
                lifecycleService.changeStatusAsAdmin(command);

        return toAdminDetailResult(changedOrder);
    }

    private Order getOrderOrThrow(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new BusinessException(
                                OrderingErrorCode.ORDER_NOT_FOUND
                        )
                );
    }

    private AdminOrderSummaryResult toAdminSummaryResult(
            OrderSummary summary
    ) {
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

    private AdminOrderDetailResult toAdminDetailResult(
            Order order
    ) {
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
                item.getProductImageKey(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineTotal()
        );
    }

    private OrderStatusHistoryResult toStatusHistoryResult(
            OrderStatusHistory history
    ) {
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