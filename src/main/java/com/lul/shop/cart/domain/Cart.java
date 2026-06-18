package com.lul.shop.cart.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class Cart {

    private UUID id;
    private UUID userId;
    private List<CartItem> items;
    private Instant createdAt;
    private Instant updatedAt;

    public Cart(UUID id,
                UUID userId,
                List<CartItem> items,
                Instant createdAt,
                Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.items = new ArrayList<>(items == null ? List.of() : items);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Cart create(UUID userId) {
        return new Cart(
                UUID.randomUUID(),
                userId,
                List.of(),
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

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
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

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void addItem(UUID productId, int quantity) {
        Objects.requireNonNull(productId, "productId must not be null");

        Optional<CartItem> existingItem = findItemByProductId(productId);

        if (existingItem.isPresent()) {
            existingItem.get().increaseQuantity(quantity);
            return;
        }

        items.add(CartItem.create(productId, quantity));
    }

    public boolean updateItemQuantity(UUID itemId, int quantity) {
        Objects.requireNonNull(itemId, "itemId must not be null");

        Optional<CartItem> existingItem = findItemById(itemId);

        if (existingItem.isEmpty()) {
            return false;
        }

        existingItem.get().changeQuantity(quantity);
        return true;
    }

    public boolean removeItem(UUID itemId) {
        Objects.requireNonNull(itemId, "itemId must not be null");

        return items.removeIf(item -> item.hasId(itemId));
    }

    private Optional<CartItem> findItemByProductId(UUID productId) {
        return items.stream()
                .filter(item -> item.hasProduct(productId))
                .findFirst();
    }

    private Optional<CartItem> findItemById(UUID itemId) {
        return items.stream()
                .filter(item -> item.hasId(itemId))
                .findFirst();
    }
}