package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderItemResult;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.application.port.CheckoutCartClient;
import com.lul.shop.ordering.application.port.CheckoutCartItemSnapshot;
import com.lul.shop.ordering.application.port.CheckoutCartSnapshot;
import com.lul.shop.ordering.application.port.CheckoutProductClient;
import com.lul.shop.ordering.application.port.CheckoutProductSnapshot;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderingService {

    private final OrderRepository orderRepository;
    private final CheckoutCartClient checkoutCartClient;
    private final CheckoutProductClient checkoutProductClient;

    public OrderingService(OrderRepository orderRepository,
                           CheckoutCartClient checkoutCartClient,
                           CheckoutProductClient checkoutProductClient) {
        this.orderRepository = orderRepository;
        this.checkoutCartClient = checkoutCartClient;
        this.checkoutProductClient = checkoutProductClient;
    }

    @Transactional
    public OrderResult placeOrder(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        CheckoutCartSnapshot cart = checkoutCartClient.getCartForCheckout(command.userId());

        if (cart.isEmpty()) {
            throw new BusinessException(OrderingErrorCode.CART_EMPTY);
        }

        List<OrderItem> orderItems = cart.items()
                .stream()
                .map(this::createOrderItem)
                .toList();

        Order order = Order.create(command.userId(), orderItems);

        Order savedOrder = orderRepository.save(order);

        checkoutCartClient.clearCart(command.userId());

        return toResult(savedOrder);
    }

    public List<OrderResult> getOrders(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        return orderRepository.findByUserId(userId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    public OrderResult getOrder(UUID userId, UUID orderId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");

        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(OrderingErrorCode.ORDER_NOT_FOUND));

        return toResult(order);
    }

    @Transactional
    public void markOrderAsPaid(UUID userId, UUID orderId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");

        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(OrderingErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(OrderingErrorCode.ORDER_NOT_PAYABLE);
        }

        order.markPaid();

        orderRepository.save(order);
    }

    private OrderItem createOrderItem(CheckoutCartItemSnapshot cartItem) {
        CheckoutProductSnapshot product = checkoutProductClient.getProductForCheckout(cartItem.productId());

        boolean stockDecreased = checkoutProductClient.decreaseStockIfEnough(
                product.id(),
                cartItem.quantity()
        );

        if (!stockDecreased) {
            throw new BusinessException(OrderingErrorCode.INSUFFICIENT_STOCK);
        }

        return OrderItem.create(
                product.id(),
                product.sku(),
                product.name(),
                product.price(),
                cartItem.quantity()
        );
    }

    private OrderResult toResult(Order order) {
        return new OrderResult(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getItems()
                        .stream()
                        .map(this::toItemResult)
                        .toList(),
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
}