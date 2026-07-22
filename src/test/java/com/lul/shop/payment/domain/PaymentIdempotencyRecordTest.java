package com.lul.shop.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIdempotencyRecordTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID PAYMENT_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-22T01:00:00Z");

    private static final String KEY =
            "payment-request-001";

    private static final String FINGERPRINT =
            "a".repeat(64);

    @Test
    void shouldCreateProcessingRecordWithoutPaymentResult() {
        PaymentIdempotencyRecord record =
                PaymentIdempotencyRecord.processing(
                        USER_ID,
                        KEY,
                        FINGERPRINT,
                        NOW
                );

        assertThat(record.id()).isNotNull();
        assertThat(record.userId()).isEqualTo(USER_ID);
        assertThat(record.idempotencyKey()).isEqualTo(KEY);
        assertThat(record.requestFingerprint())
                .isEqualTo(FINGERPRINT);
        assertThat(record.status()).isEqualTo(
                PaymentIdempotencyRecord.Status.PROCESSING
        );
        assertThat(record.paymentId()).isNull();
        assertThat(record.createdAt()).isEqualTo(NOW);
        assertThat(record.updatedAt()).isEqualTo(NOW);
        assertThat(record.matchesFingerprint(FINGERPRINT))
                .isTrue();
        assertThat(record.isCompleted()).isFalse();
    }

    @Test
    void shouldRehydrateCompletedRecordWithPaymentResult() {
        PaymentIdempotencyRecord record =
                new PaymentIdempotencyRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        KEY,
                        FINGERPRINT,
                        PaymentIdempotencyRecord.Status.COMPLETED,
                        PAYMENT_ID,
                        NOW,
                        NOW.plusSeconds(1)
                );

        assertThat(record.isCompleted()).isTrue();
        assertThat(record.paymentId()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void shouldRejectStatusAndPaymentResultMismatch() {
        assertThatThrownBy(() ->
                new PaymentIdempotencyRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        KEY,
                        FINGERPRINT,
                        PaymentIdempotencyRecord.Status.PROCESSING,
                        PAYMENT_ID,
                        NOW,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "status and paymentId are inconsistent"
                );

        assertThatThrownBy(() ->
                new PaymentIdempotencyRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        KEY,
                        FINGERPRINT,
                        PaymentIdempotencyRecord.Status.COMPLETED,
                        null,
                        NOW,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "status and paymentId are inconsistent"
                );
    }

    @Test
    void shouldRejectInvalidKeyFingerprintAndTimestamp() {
        assertThatThrownBy(() ->
                new PaymentIdempotencyRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        "short",
                        FINGERPRINT,
                        PaymentIdempotencyRecord.Status.PROCESSING,
                        null,
                        NOW,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey is invalid");

        assertThatThrownBy(() ->
                new PaymentIdempotencyRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        KEY,
                        "not-a-sha256-fingerprint",
                        PaymentIdempotencyRecord.Status.PROCESSING,
                        null,
                        NOW,
                        NOW
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestFingerprint is invalid");

        assertThatThrownBy(() ->
                new PaymentIdempotencyRecord(
                        UUID.randomUUID(),
                        USER_ID,
                        KEY,
                        FINGERPRINT,
                        PaymentIdempotencyRecord.Status.PROCESSING,
                        null,
                        NOW,
                        NOW.minusSeconds(1)
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "updatedAt must not precede createdAt"
                );
    }
}
