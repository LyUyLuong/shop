package com.lul.shop.cart.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class CartItem {

    private UUID id;
    private UUID productId;
    private int quantity;
    private Instant createdAt;
    private Instant updatedAt;

    public CartItem(UUID id,
                    UUID productId,
                    int quantity,
                    Instant createdAt,
                    Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.quantity = requirePositiveQuantity(quantity);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static CartItem create(UUID productId, int quantity) {
        return new CartItem(
                UUID.randomUUID(),
                productId,
                quantity,
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

    public int getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasId(UUID itemId) {
        return id.equals(itemId);
    }

    public boolean hasProduct(UUID productId) {
        return this.productId.equals(productId);
    }

    public void increaseQuantity(int quantityToAdd) {
        int validQuantityToAdd = requirePositiveQuantity(quantityToAdd);

        this.quantity = requirePositiveQuantity(this.quantity + validQuantityToAdd);
    }

    public void changeQuantity(int quantity) {
        this.quantity = requirePositiveQuantity(quantity);
    }

    private static int requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        return quantity;
    }
}