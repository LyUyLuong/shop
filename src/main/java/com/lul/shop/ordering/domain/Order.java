package com.lul.shop.ordering.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Order {

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(30);

    private UUID id;
    private UUID userId;
    private OrderStatus status;
    private List<OrderItem> items;
    private Instant expiresAt;
    private Instant inventoryReleasedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private FulfillmentSnapshot fulfillment;
    private OrderPaymentMode paymentMode;
    private OrderAmounts amounts;

    private Order(
            UUID id,
            UUID userId,
            OrderStatus status,
            FulfillmentSnapshot fulfillment,
            OrderPaymentMode paymentMode,
            OrderAmounts amounts,
            List<OrderItem> items,
            Instant expiresAt,
            Instant inventoryReleasedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.paymentMode = Objects.requireNonNull(
                paymentMode,
                "paymentMode must not be null"
        );
        this.amounts = Objects.requireNonNull(
                amounts,
                "amounts must not be null"
        );

        List<OrderItem> validItems = requireNonEmptyItems(items);
        validateItemSubtotal(validItems, amounts);
        validatePaymentState(status, paymentMode, expiresAt);

        if (fulfillment == null
                && (paymentMode != OrderPaymentMode.MOCK
                || amounts.shippingFee().signum() != 0)) {
            throw new IllegalArgumentException(
                    "only legacy MOCK orders may omit fulfillment"
            );
        }

        if (createdAt != null
                && expiresAt != null
                && expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must not be before createdAt"
            );
        }

        validateInventoryRelease(status, inventoryReleasedAt, createdAt);

        this.fulfillment = fulfillment;
        this.items = new ArrayList<>(validItems);
        this.expiresAt = expiresAt;
        this.inventoryReleasedAt = inventoryReleasedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Order create(
            UUID userId,
            List<OrderItem> items,
            FulfillmentSnapshot fulfillment,
            OrderPaymentMode paymentMode,
            OrderAmounts amounts,
            Instant now
    ) {
        Objects.requireNonNull(fulfillment, "fulfillment must not be null");
        Objects.requireNonNull(paymentMode, "paymentMode must not be null");
        Objects.requireNonNull(amounts, "amounts must not be null");

        Instant validNow = Objects.requireNonNull(now, "now must not be null");
        List<OrderItem> validItems = requireNonEmptyItems(items);

        validateItemSubtotal(validItems, amounts);

        OrderStatus initialStatus = switch (paymentMode) {
            case MOCK -> OrderStatus.PENDING_PAYMENT;
            case COD -> OrderStatus.CONFIRMED;
        };

        Instant expiresAt = paymentMode == OrderPaymentMode.MOCK
                ? validNow.plus(PAYMENT_WINDOW)
                : null;

        return new Order(
                UUID.randomUUID(),
                userId,
                initialStatus,
                fulfillment,
                paymentMode,
                amounts,
                validItems,
                expiresAt,
                null,
                null,
                null
        );
    }

    public static Order restore(
            UUID id,
            UUID userId,
            OrderStatus status,
            FulfillmentSnapshot fulfillment,
            OrderPaymentMode paymentMode,
            OrderAmounts amounts,
            List<OrderItem> items,
            Instant expiresAt,
            Instant inventoryReleasedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new Order(
                id,
                userId,
                status,
                fulfillment,
                paymentMode,
                amounts,
                items,
                expiresAt,
                inventoryReleasedAt,
                createdAt,
                updatedAt
        );
    }

    private static void validateItemSubtotal(
            List<OrderItem> items,
            OrderAmounts amounts
    ) {
        BigDecimal itemSubtotal = items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (amounts.subtotalAmount().compareTo(itemSubtotal) != 0) {
            throw new IllegalArgumentException(
                    "subtotalAmount must equal sum of order item line totals"
            );
        }
    }

    private static void validatePaymentState(
            OrderStatus status,
            OrderPaymentMode paymentMode,
            Instant expiresAt
    ) {
        if (paymentMode == OrderPaymentMode.MOCK) {
            if (expiresAt == null || status == OrderStatus.CONFIRMED) {
                throw new IllegalArgumentException(
                        "MOCK order requires a payment deadline and MOCK status"
                );
            }
            return;
        }

        if (expiresAt != null
                || status == OrderStatus.PENDING_PAYMENT
                || status == OrderStatus.PAID
                || status == OrderStatus.EXPIRED) {
            throw new IllegalArgumentException(
                    "COD order has an incompatible status or payment deadline"
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getInventoryReleasedAt() {
        return inventoryReleasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean belongsTo(UUID userId) {
        return this.userId.equals(userId);
    }

    public FulfillmentSnapshot getFulfillment() {
        return fulfillment;
    }

    public OrderPaymentMode getPaymentMode() {
        return paymentMode;
    }

    public OrderAmounts getAmounts() {
        return amounts;
    }

    public BigDecimal getSubtotalAmount() {
        return amounts.subtotalAmount();
    }

    public BigDecimal getShippingFee() {
        return amounts.shippingFee();
    }

    public BigDecimal getTotalAmount() {
        return amounts.totalAmount();
    }

    public boolean isPayableAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        return paymentMode == OrderPaymentMode.MOCK
                && status == OrderStatus.PENDING_PAYMENT
                && now.isBefore(expiresAt);
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        return paymentMode == OrderPaymentMode.MOCK
                && status == OrderStatus.PENDING_PAYMENT
                && !now.isBefore(expiresAt);
    }

    public OrderStatus expire(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "order cannot expire from " + status
            );
        }

        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException(
                    "order cannot expire before its payment deadline"
            );
        }

        OrderStatus previousStatus = status;
        status = OrderStatus.EXPIRED;

        return previousStatus;
    }

    public boolean isInventoryReleased() {
        return inventoryReleasedAt != null;
    }

    public void markInventoryReleased(Instant releasedAt) {
        Instant validReleasedAt = Objects.requireNonNull(
                releasedAt,
                "releasedAt must not be null"
        );

        if (inventoryReleasedAt != null) {
            throw new IllegalStateException("inventory was already released");
        }

        if (status != OrderStatus.CANCELLED
                && status != OrderStatus.EXPIRED) {
            throw new IllegalStateException(
                    "inventory can only be released for CANCELLED or EXPIRED orders"
            );
        }

        if (createdAt != null && validReleasedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "releasedAt must not be before createdAt"
            );
        }

        inventoryReleasedAt = validReleasedAt;
    }

    public void markPaid() {
        changeStatus(OrderStatus.PAID);
    }

    public OrderStatus changeStatus(OrderStatus targetStatus) {
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");

        OrderStatus previousStatus = this.status;

        if (!canMoveTo(targetStatus)) {
            throw new IllegalStateException(
                    "order status cannot move from " + previousStatus + " to " + targetStatus
            );
        }

        this.status = targetStatus;

        return previousStatus;
    }

    public boolean canMoveTo(OrderStatus targetStatus) {
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");

        return switch (status) {
            case PENDING_PAYMENT ->
                    paymentMode == OrderPaymentMode.MOCK
                            && (targetStatus == OrderStatus.PAID
                            || targetStatus == OrderStatus.CANCELLED);

            case CONFIRMED ->
                    paymentMode == OrderPaymentMode.COD
                            && (targetStatus == OrderStatus.PACKING
                            || targetStatus == OrderStatus.CANCELLED);

            case PAID ->
                    paymentMode == OrderPaymentMode.MOCK
                            && targetStatus == OrderStatus.PACKING;

            case PACKING ->
                    targetStatus == OrderStatus.SHIPPED;

            case SHIPPED ->
                    paymentMode == OrderPaymentMode.MOCK
                            && targetStatus == OrderStatus.COMPLETED;

            case COMPLETED, CANCELLED, EXPIRED -> false;
        };
    }

    private static void validateInventoryRelease(
            OrderStatus status,
            Instant inventoryReleasedAt,
            Instant createdAt
    ) {
        if (inventoryReleasedAt == null) {
            return;
        }

        if (status != OrderStatus.CANCELLED
                && status != OrderStatus.EXPIRED) {
            throw new IllegalArgumentException(
                    "inventory can only be released for CANCELLED or EXPIRED orders"
            );
        }

        if (createdAt != null && inventoryReleasedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "inventoryReleasedAt must not be before createdAt"
            );
        }
    }

    private static List<OrderItem> requireNonEmptyItems(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("order must have at least one item");
        }

        return List.copyOf(items);
    }

}
