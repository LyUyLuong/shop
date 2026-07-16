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
    private BigDecimal totalAmount;
    private List<OrderItem> items;
    private Instant expiresAt;
    private Instant inventoryReleasedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Order(UUID id,
                 UUID userId,
                 OrderStatus status,
                 BigDecimal totalAmount,
                 List<OrderItem> items,
                 Instant expiresAt,
                 Instant inventoryReleasedAt,
                 Instant createdAt,
                 Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");

        List<OrderItem> validItems = requireNonEmptyItems(items);
        BigDecimal expectedTotalAmount = calculateTotalAmount(validItems);
        BigDecimal validTotalAmount = requireNonNegativeMoney(totalAmount, "totalAmount");

        if (validTotalAmount.compareTo(expectedTotalAmount) != 0) {
            throw new IllegalArgumentException(
                    "totalAmount must equal sum of order item line totals"
            );
        }

        Instant validExpiresAt = Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        if (createdAt != null && validExpiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must not be before createdAt"
            );
        }

        validateInventoryRelease(
                status,
                inventoryReleasedAt,
                createdAt
        );

        this.items = new ArrayList<>(validItems);
        this.totalAmount = validTotalAmount;
        this.expiresAt = validExpiresAt;
        this.inventoryReleasedAt = inventoryReleasedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Order create(UUID userId,
                               List<OrderItem> items,
                               Instant now) {
        Instant validNow = Objects.requireNonNull(now, "now must not be null");
        List<OrderItem> validItems = requireNonEmptyItems(items);

        return new Order(
                UUID.randomUUID(),
                userId,
                OrderStatus.PENDING_PAYMENT,
                calculateTotalAmount(validItems),
                validItems,
                validNow.plus(PAYMENT_WINDOW),
                null,
                null,
                null
        );
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

    public BigDecimal getTotalAmount() {
        return totalAmount;
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

    public boolean isPayableAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        return status == OrderStatus.PENDING_PAYMENT
                && now.isBefore(expiresAt);
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        return status == OrderStatus.PENDING_PAYMENT
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
            case PENDING_PAYMENT -> targetStatus == OrderStatus.PAID
                    || targetStatus == OrderStatus.CANCELLED;
            case PAID -> targetStatus == OrderStatus.PACKING;
            case PACKING -> targetStatus == OrderStatus.SHIPPED;
            case SHIPPED -> targetStatus == OrderStatus.COMPLETED;
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

    private static BigDecimal calculateTotalAmount(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal requireNonNegativeMoney(
            BigDecimal value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }

        return value;
    }
}
