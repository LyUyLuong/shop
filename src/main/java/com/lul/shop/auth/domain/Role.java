package com.lul.shop.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Role {

    private UUID id;
    private UserRole name;
    private Instant createdAt;

    public Role(UUID id, UserRole name, Instant createdAt) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.createdAt = createdAt;
    }

    public static Role of(UserRole name) {
        return new Role(UUID.randomUUID(), name, null);
    }

    public UUID getId() {
        return id;
    }

    public UserRole getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
