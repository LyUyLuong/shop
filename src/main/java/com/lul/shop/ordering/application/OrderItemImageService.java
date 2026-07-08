package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderItemImageContent;
import com.lul.shop.ordering.application.port.OrderItemImageClient;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderItemImageService {

    private final OrderRepository orderRepository;
    private final OrderItemImageClient orderItemImageClient;

    public OrderItemImageService(OrderRepository orderRepository,
                                 OrderItemImageClient orderItemImageClient) {
        this.orderRepository = orderRepository;
        this.orderItemImageClient = orderItemImageClient;
    }

    public OrderItemImageContent getCustomerOrderItemImage(UUID userId, UUID orderId, UUID orderItemId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(orderItemId, "orderItemId must not be null");

        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(OrderingErrorCode.ORDER_NOT_FOUND));

        return loadImage(order, orderItemId);
    }

    public OrderItemImageContent getAdminOrderItemImage(UUID orderId, UUID orderItemId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(orderItemId, "orderItemId must not be null");

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderingErrorCode.ORDER_NOT_FOUND));

        return loadImage(order, orderItemId);
    }

    private OrderItemImageContent loadImage(Order order, UUID orderItemId) {
        OrderItem item = order.getItems()
                .stream()
                .filter(orderItem -> orderItem.getId().equals(orderItemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(OrderingErrorCode.ORDER_ITEM_NOT_FOUND));

        String imageKey = item.getProductImageKey();

        if (imageKey == null || imageKey.isBlank()) {
            throw new BusinessException(OrderingErrorCode.ORDER_ITEM_IMAGE_NOT_FOUND);
        }

        return orderItemImageClient.loadOrderItemImage(imageKey);
    }
}