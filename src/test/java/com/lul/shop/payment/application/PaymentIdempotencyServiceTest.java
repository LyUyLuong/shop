package com.lul.shop.payment.application;

import com.lul.shop.payment.domain.PaymentIdempotencyRecord;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentIdempotencyServiceTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID OTHER_ORDER_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID CLAIM_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final UUID PAYMENT_ID = UUID.fromString(
            "55555555-5555-4555-8555-555555555555"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-22T02:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String KEY =
            "payment-request-001";

    private PaymentIdempotencyRepository repository;
    private PaymentIdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentIdempotencyRepository.class);

        service = new PaymentIdempotencyService(
                repository,
                CLOCK
        );
    }

    @Test
    void shouldOwnNewClaimWithStableRequestFingerprint() {
        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(true);

        PaymentIdempotencyService.Decision decision =
                service.begin(USER_ID, ORDER_ID, KEY);

        assertThat(decision.isReplay()).isFalse();
        assertThat(decision.claimId()).isNotNull();
        assertThat(decision.replayPaymentId()).isNull();

        ArgumentCaptor<PaymentIdempotencyRecord> captor =
                ArgumentCaptor.forClass(
                        PaymentIdempotencyRecord.class
                );

        verify(repository).insertIfAbsent(captor.capture());

        PaymentIdempotencyRecord claim = captor.getValue();

        assertThat(claim.id()).isEqualTo(decision.claimId());
        assertThat(claim.userId()).isEqualTo(USER_ID);
        assertThat(claim.idempotencyKey()).isEqualTo(KEY);
        assertThat(claim.requestFingerprint()).isEqualTo(
                "139d9097962b5c41c9f56d92cfb306cf"
                        + "35ebfe200d46e20e04476d965f2df143"
        );
        assertThat(claim.status()).isEqualTo(
                PaymentIdempotencyRecord.Status.PROCESSING
        );
        assertThat(claim.paymentId()).isNull();
        assertThat(claim.createdAt()).isEqualTo(NOW);
        assertThat(claim.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldReplayCompletedPaymentForSameRequest() {
        String fingerprint = service.fingerprint(ORDER_ID);

        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(
                        completedRecord(fingerprint)
                ));

        PaymentIdempotencyService.Decision decision =
                service.begin(USER_ID, ORDER_ID, KEY);

        assertThat(decision.isReplay()).isTrue();
        assertThat(decision.claimId()).isNull();
        assertThat(decision.replayPaymentId())
                .isEqualTo(PAYMENT_ID);
    }

    @Test
    void shouldRejectKeyReusedForDifferentOrder() {
        String previousFingerprint =
                service.fingerprint(ORDER_ID);

        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(
                        completedRecord(previousFingerprint)
                ));

        assertThatThrownBy(() ->
                service.begin(USER_ID, OTHER_ORDER_ID, KEY)
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                PaymentErrorCode
                                        .IDEMPOTENCY_KEY_REUSED
                        )
                );
    }

    @Test
    void shouldRejectUnfinishedConflictingClaim() {
        String fingerprint = service.fingerprint(ORDER_ID);

        PaymentIdempotencyRecord processing =
                new PaymentIdempotencyRecord(
                        CLAIM_ID,
                        USER_ID,
                        KEY,
                        fingerprint,
                        PaymentIdempotencyRecord.Status.PROCESSING,
                        null,
                        NOW,
                        NOW
                );

        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(processing));

        assertInvalidState(() ->
                service.begin(USER_ID, ORDER_ID, KEY)
        );
    }

    @Test
    void shouldRejectMissingRecordAfterInsertConflict() {
        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.empty());

        assertInvalidState(() ->
                service.begin(USER_ID, ORDER_ID, KEY)
        );
    }

    @Test
    void shouldAcceptExactKeyBoundariesWithoutNormalization() {
        String minimumKey = "Ab1._:-x";
        String maximumKey = "A".repeat(100);

        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(true);

        service.begin(USER_ID, ORDER_ID, minimumKey);
        service.begin(USER_ID, ORDER_ID, maximumKey);

        ArgumentCaptor<PaymentIdempotencyRecord> captor =
                ArgumentCaptor.forClass(
                        PaymentIdempotencyRecord.class
                );

        verify(repository, times(2))
                .insertIfAbsent(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(
                        PaymentIdempotencyRecord::idempotencyKey
                )
                .containsExactly(minimumKey, maximumKey);
    }

    @Test
    void shouldRejectMissingBoundaryAndSyntaxViolations() {
        assertInvalidKey(null);

        List.of(
                "",
                "A".repeat(7),
                "A".repeat(101),
                "payment request",
                "payment/request",
                "payment@request",
                "thanh-toan-001-\u0111",
                "payment-request-001 "
        ).forEach(this::assertInvalidKey);

        verifyNoInteractions(repository);
    }

    @Test
    void shouldIncludeOrderIdInFingerprint() {
        String original = service.fingerprint(ORDER_ID);

        assertThat(service.fingerprint(ORDER_ID))
                .isEqualTo(original);

        assertThat(service.fingerprint(OTHER_ORDER_ID))
                .isNotEqualTo(original);
    }

    @Test
    void shouldCompleteOwnedClaim() {
        when(repository.complete(
                CLAIM_ID,
                PAYMENT_ID,
                NOW
        )).thenReturn(true);

        service.complete(CLAIM_ID, PAYMENT_ID);

        verify(repository).complete(
                CLAIM_ID,
                PAYMENT_ID,
                NOW
        );
    }

    @Test
    void shouldRejectClaimThatCannotBeCompleted() {
        when(repository.complete(
                CLAIM_ID,
                PAYMENT_ID,
                NOW
        )).thenReturn(false);

        assertInvalidState(() ->
                service.complete(CLAIM_ID, PAYMENT_ID)
        );
    }

    private void assertInvalidKey(String idempotencyKey) {
        assertThatThrownBy(() ->
                service.begin(
                        USER_ID,
                        ORDER_ID,
                        idempotencyKey
                )
        )
                .as(
                        "idempotency key should be rejected: <%s>",
                        idempotencyKey
                )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                PaymentErrorCode
                                        .INVALID_IDEMPOTENCY_KEY
                        )
                );
    }

    private PaymentIdempotencyRecord completedRecord(
            String fingerprint
    ) {
        return new PaymentIdempotencyRecord(
                CLAIM_ID,
                USER_ID,
                KEY,
                fingerprint,
                PaymentIdempotencyRecord.Status.COMPLETED,
                PAYMENT_ID,
                NOW,
                NOW.plusSeconds(1)
        );
    }

    private void assertInvalidState(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                PaymentErrorCode
                                        .PAYMENT_IDEMPOTENCY_STATE_INVALID
                        )
                );
    }
}
