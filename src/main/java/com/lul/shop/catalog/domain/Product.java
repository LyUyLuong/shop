package com.lul.shop.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public class Product {

    private UUID id;
    private long version;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private int stockQuantity;
    private ProductStatus status;
    private String imageKey;
    private String imageUrl;
    private Instant createdAt;
    private Instant updatedAt;

    public Product(
            UUID id,
            long version,
            String sku,
            String name,
            String description,
            BigDecimal price,
            int stockQuantity,
            ProductStatus status,
            String imageKey,
            String imageUrl,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");

        if (version < 0) {
            throw new IllegalArgumentException(
                    "version must not be negative"
            );
        }

        this.version = version;
        this.sku = normalizeSku(sku);
        this.name = requireProductName(name);
        this.description = normalizeOptionalText(description);
        this.price = requireNonNegativePrice(price);
        this.stockQuantity = requireNonNegativeStock(stockQuantity);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.imageKey = normalizeOptionalText(imageKey);
        this.imageUrl = normalizeOptionalText(imageUrl);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Product create(
            String sku,
            String name,
            String description,
            BigDecimal price,
            int stockQuantity
    ) {
        return new Product(
                UUID.randomUUID(),
                0L,
                sku,
                name,
                description,
                price,
                stockQuantity,
                ProductStatus.ACTIVE,
                null,
                null,
                null,
                null
        );
    }

    public long getVersion() {
        return version;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public String getImageKey() {
        return imageKey;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateDetails(String sku,
                              String name,
                              String description,
                              BigDecimal price,
                              int stockQuantity) {
        this.sku = normalizeSku(sku);
        this.name = requireProductName(name);
        this.description = normalizeOptionalText(description);
        this.price = requireNonNegativePrice(price);
        this.stockQuantity = requireNonNegativeStock(stockQuantity);
    }

    public void updateStockQuantity(int stockQuantity) {
        this.stockQuantity = requireNonNegativeStock(stockQuantity);
    }

    public void updateImage(String imageKey, String imageUrl) {
        this.imageKey = requireImageKey(imageKey);
        this.imageUrl = normalizeOptionalText(imageUrl);
    }

    public void clearImage() {
        this.imageKey = null;
        this.imageUrl = null;
    }

    public void activate() {
        this.status = ProductStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = ProductStatus.INACTIVE;
    }

    public boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }

    private static String normalizeSku(String sku) {
        return requireNonBlank(sku, "sku").toUpperCase(Locale.ROOT);
    }

    private static String requireProductName(String name) {
        return requireNonBlank(name, "name");
    }

    private static String requireImageKey(String imageKey) {
        return requireNonBlank(imageKey, "imageKey");
    }

    private static String requireImageUrl(String imageUrl) {
        return requireNonBlank(imageUrl, "imageUrl");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal requireNonNegativePrice(BigDecimal price) {
        Objects.requireNonNull(price, "price must not be null");

        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must be >= 0");
        }

        return price;
    }

    private static int requireNonNegativeStock(int stockQuantity) {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("stockQuantity must be >= 0");
        }

        return stockQuantity;
    }
}