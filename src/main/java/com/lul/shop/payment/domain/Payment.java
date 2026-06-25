package com.lul.shop.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Payment {

    private UUID id;
    private UUID orderId;
    private UUID userId;
    private PaymentMethod method;
    private PaymentStatus status;
    private BigDecimal amount;
    private Instant paidAt;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public Payment(UUID id,
                   UUID orderId,
                   UUID userId,
                   PaymentMethod method,
                   PaymentStatus status,
                   BigDecimal amount,
                   Instant paidAt,
                   String failureReason,
                   Instant createdAt,
                   Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.amount = requireNonNegativeMoney(amount, "amount");
        this.paidAt = paidAt;
        this.failureReason = normalizeFailureReason(failureReason);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;

        validateStatusFields();
    }

    public static Payment createPending(UUID orderId,
                                        UUID userId,
                                        BigDecimal amount,
                                        PaymentMethod method) {
        return new Payment(
                UUID.randomUUID(),
                orderId,
                userId,
                method,
                PaymentStatus.PENDING,
                amount,
                null,
                null,
                null,
                null
        );
    }

    public static Payment createSucceededMock(UUID orderId,
                                              UUID userId,
                                              BigDecimal amount,
                                              Instant paidAt) {
        return new Payment(
                UUID.randomUUID(),
                orderId,
                userId,
                PaymentMethod.MOCK,
                PaymentStatus.SUCCEEDED,
                amount,
                Objects.requireNonNull(paidAt, "paidAt must not be null"),
                null,
                null,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getUserId() {
        return userId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getFailureReason() {
        return failureReason;
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

    public boolean isSucceeded() {
        return status == PaymentStatus.SUCCEEDED;
    }

    public void succeed(Instant paidAt) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("only pending payment can succeed");
        }

        this.status = PaymentStatus.SUCCEEDED;
        this.paidAt = Objects.requireNonNull(paidAt, "paidAt must not be null");
        this.failureReason = null;
    }

    public void fail(String failureReason) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("only pending payment can fail");
        }

        this.status = PaymentStatus.FAILED;
        this.paidAt = null;
        this.failureReason = requireText(failureReason, "failureReason");
    }

    private void validateStatusFields() {
        if (status == PaymentStatus.PENDING) {
            requirePaidAtAbsent();
            requireFailureReasonAbsent();
            return;
        }

        if (status == PaymentStatus.SUCCEEDED) {
            requirePaidAtPresent();
            requireFailureReasonAbsent();
            return;
        }

        if (status == PaymentStatus.FAILED) {
            requirePaidAtAbsent();
            requireFailureReasonPresent();
        }
    }

    private void requirePaidAtPresent() {
        if (paidAt == null) {
            throw new IllegalArgumentException("paidAt is required for succeeded payment");
        }
    }

    private void requirePaidAtAbsent() {
        if (paidAt != null) {
            throw new IllegalArgumentException("paidAt must be null unless payment succeeded");
        }
    }

    private void requireFailureReasonPresent() {
        if (failureReason == null) {
            throw new IllegalArgumentException("failureReason is required for failed payment");
        }
    }

    private void requireFailureReasonAbsent() {
        if (failureReason != null) {
            throw new IllegalArgumentException("failureReason must be null unless payment failed");
        }
    }

    private static BigDecimal requireNonNegativeMoney(BigDecimal value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");

        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }

        return value;
    }

    private static String normalizeFailureReason(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value.trim();
    }
}