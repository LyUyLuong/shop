package com.lul.shop.auth.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class User {

    private UUID id;
    private String email;
    private String name;
    private String passwordHash;
    private boolean enabled;
    private Set<UserRole> roles;
    private Instant createdAt;
    private Instant updatedAt;

    public User(UUID id,
                String email,
                String name,
                String passwordHash,
                boolean enabled,
                Set<UserRole> roles,
                Instant createdAt,
                Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.email = normalizeEmail(email);
        this.name = requireText(name, "name");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.enabled = enabled;
        this.roles = normalizeRoles(roles);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User create(String email, String name, String passwordHash) {
        return new User(
                UUID.randomUUID(),
                normalizeEmail(email),
                name,
                passwordHash,
                true,
                EnumSet.of(UserRole.USER),
                null,
                null
        );
    }

    public static User createAdmin(String email, String name, String passwordHash) {
        return new User(
                UUID.randomUUID(),
                normalizeEmail(email),
                name,
                passwordHash,
                true,
                EnumSet.of(UserRole.ADMIN),
                null,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<UserRole> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = requireText(newPasswordHash, "passwordHash");
    }

    public void changeName(String newName) {
        this.name = requireText(newName, "name");
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void addRole(UserRole role) {
        roles.add(Objects.requireNonNull(role, "role must not be null"));
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }

    private static Set<UserRole> normalizeRoles(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return EnumSet.of(UserRole.USER);
        }
        return EnumSet.copyOf(roles);
    }

    private static String normalizeEmail(String email) {
        return requireText(email, "email").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
