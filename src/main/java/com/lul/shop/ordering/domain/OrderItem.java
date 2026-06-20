package com.lul.shop.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class OrderItem {

    private UUID id;
    private UUID productId;
    private String productSku;
    private String productName;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;
    private Instant createdAt;
    private Instant updatedAt;

    public OrderItem(UUID id,
                     UUID productId,
                     String productSku,
                     String productName,
                     BigDecimal unitPrice,
                     int quantity,
                     BigDecimal lineTotal,
                     Instant createdAt,
                     Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.productSku = requireNonBlank(productSku, "productSku");
        this.productName = requireNonBlank(productName, "productName");

        BigDecimal validUnitPrice = requireNonNegativeMoney(unitPrice, "unitPrice");
        int validQuantity = requirePositiveQuantity(quantity);
        BigDecimal expectedLineTotal = calculateLineTotal(validUnitPrice, validQuantity);
        BigDecimal validLineTotal = requireNonNegativeMoney(lineTotal, "lineTotal");

        if (validLineTotal.compareTo(expectedLineTotal) != 0) {
            throw new IllegalArgumentException("lineTotal must equal unitPrice * quantity");
        }

        this.unitPrice = validUnitPrice;
        this.quantity = validQuantity;
        this.lineTotal = validLineTotal;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static OrderItem create(UUID productId,
                                   String productSku,
                                   String productName,
                                   BigDecimal unitPrice,
                                   int quantity) {
        BigDecimal validUnitPrice = requireNonNegativeMoney(unitPrice, "unitPrice");
        int validQuantity = requirePositiveQuantity(quantity);

        return new OrderItem(
                UUID.randomUUID(),
                productId,
                productSku,
                productName,
                validUnitPrice,
                validQuantity,
                calculateLineTotal(validUnitPrice, validQuantity),
                null,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductSku() {
        return productSku;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static BigDecimal calculateLineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }

    private static int requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        return quantity;
    }

    private static BigDecimal requireNonNegativeMoney(BigDecimal value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }

        return value;
    }
}