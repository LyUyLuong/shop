package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderFulfillmentResult;
import com.lul.shop.ordering.application.dto.OrderItemResult;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.application.port.CheckoutCartClient;
import com.lul.shop.ordering.application.port.CheckoutCartItemSnapshot;
import com.lul.shop.ordering.application.port.CheckoutCartSnapshot;
import com.lul.shop.ordering.application.port.CheckoutPaymentModePolicy;
import com.lul.shop.ordering.application.port.CheckoutProductClient;
import com.lul.shop.ordering.application.port.CheckoutProductSnapshot;
import com.lul.shop.ordering.application.port.ShippingFeePolicy;
import com.lul.shop.ordering.domain.FulfillmentSnapshot;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderAmounts;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderingService {

    private static final Logger log =
            LoggerFactory.getLogger(OrderingService.class);

    private final OrderRepository orderRepository;
    private final CheckoutCartClient checkoutCartClient;
    private final CheckoutProductClient checkoutProductClient;
    private final OrderPlacementIdempotencyService idempotencyService;
    private final ShippingFeePolicy shippingFeePolicy;
    private final CheckoutPaymentModePolicy paymentModePolicy;
    private final Clock clock;

    public OrderingService(
            OrderRepository orderRepository,
            CheckoutCartClient checkoutCartClient,
            CheckoutProductClient checkoutProductClient,
            OrderPlacementIdempotencyService idempotencyService,
            ShippingFeePolicy shippingFeePolicy,
            CheckoutPaymentModePolicy paymentModePolicy,
            Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.checkoutCartClient = checkoutCartClient;
        this.checkoutProductClient = checkoutProductClient;
        this.idempotencyService = idempotencyService;
        this.shippingFeePolicy = shippingFeePolicy;
        this.paymentModePolicy = paymentModePolicy;
        this.clock = clock;
    }

    @Transactional
    public OrderResult placeOrder(PlaceOrderCommand command) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        OrderPlacementIdempotencyService.Decision decision =
                idempotencyService.begin(command);

        if (decision.isReplay()) {
            return replayOrder(
                    command.userId(),
                    decision.replayOrderId()
            );
        }

        /*
         * Check availability after replay so a completed request remains
         * replayable even if its payment mode is disabled later.
         */
        if (!paymentModePolicy.isEnabled(
                command.paymentMode()
        )) {
            throw new BusinessException(
                    OrderingErrorCode
                            .PAYMENT_MODE_NOT_AVAILABLE
            );
        }

        CheckoutCartSnapshot cart =
                checkoutCartClient.claimForCheckout(
                        command.userId(),
                        command.cartId(),
                        command.cartVersion()
                );

        if (cart.isEmpty()) {
            throw new BusinessException(
                    OrderingErrorCode.CART_EMPTY
            );
        }

        /*
         * Stable product ordering preserves the existing deadlock-avoidance
         * policy for concurrent checkouts with overlapping products.
         */
        List<OrderItem> orderItems = cart.items()
                .stream()
                .sorted(Comparator.comparing(
                        CheckoutCartItemSnapshot::productId
                ))
                .map(this::createOrderItem)
                .toList();

        BigDecimal subtotalAmount = orderItems.stream()
                .map(OrderItem::getLineTotal)
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );

        BigDecimal shippingFee =
                shippingFeePolicy.shippingFeeFor(
                        command.fulfillment()
                                .shippingMethod()
                );

        OrderAmounts amounts = OrderAmounts.calculate(
                subtotalAmount,
                shippingFee
        );

        Order order = Order.create(
                command.userId(),
                orderItems,
                command.fulfillment(),
                command.paymentMode(),
                amounts,
                Instant.now(clock)
        );

        Order savedOrder = orderRepository.save(order);

        idempotencyService.complete(
                decision.claimId(),
                savedOrder.getId()
        );

        log.info(
                "action=order.placed userId={} orderId={} "
                        + "paymentMode={} itemCount={} subtotalAmount={} "
                        + "shippingFee={} totalAmount={}",
                savedOrder.getUserId(),
                savedOrder.getId(),
                savedOrder.getPaymentMode(),
                savedOrder.getItems().size(),
                savedOrder.getSubtotalAmount(),
                savedOrder.getShippingFee(),
                savedOrder.getTotalAmount()
        );

        return toResult(savedOrder);
    }

    public List<OrderResult> getOrders(UUID userId) {
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        return orderRepository.findByUserId(userId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    public OrderResult getOrder(
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
                .findByIdAndUserId(orderId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                OrderingErrorCode.ORDER_NOT_FOUND
                        )
                );

        return toResult(order);
    }

    private OrderResult replayOrder(
            UUID userId,
            UUID orderId
    ) {
        Order order = orderRepository
                .findByIdAndUserId(orderId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                OrderingErrorCode
                                        .ORDER_IDEMPOTENCY_STATE_INVALID
                        )
                );

        log.info(
                "action=order.replayed userId={} orderId={}",
                userId,
                orderId
        );

        return toResult(order);
    }

    private OrderItem createOrderItem(
            CheckoutCartItemSnapshot cartItem
    ) {
        CheckoutProductSnapshot product =
                checkoutProductClient
                        .getProductForCheckout(
                                cartItem.productId()
                        );

        boolean stockDecreased =
                checkoutProductClient
                        .decreaseStockIfEnough(
                                product.id(),
                                cartItem.quantity()
                        );

        if (!stockDecreased) {
            throw new BusinessException(
                    OrderingErrorCode.INSUFFICIENT_STOCK
            );
        }

        return OrderItem.create(
                product.id(),
                product.sku(),
                product.name(),
                product.imageKey(),
                product.price(),
                cartItem.quantity()
        );
    }

    private OrderResult toResult(Order order) {
        return new OrderResult(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getPaymentMode(),
                order.getSubtotalAmount(),
                order.getShippingFee(),
                order.getTotalAmount(),
                toFulfillmentResult(
                        order.getFulfillment()
                ),
                order.getItems()
                        .stream()
                        .map(this::toItemResult)
                        .toList(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private OrderFulfillmentResult toFulfillmentResult(
            FulfillmentSnapshot fulfillment
    ) {
        if (fulfillment == null) {
            return null;
        }

        return new OrderFulfillmentResult(
                fulfillment.recipientName(),
                fulfillment.recipientPhone(),
                fulfillment.shippingAddress(),
                fulfillment.shippingMethod()
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
}
