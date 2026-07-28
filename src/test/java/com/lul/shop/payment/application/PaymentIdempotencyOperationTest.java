package com.lul.shop.payment.application;

import com.lul.shop.payment.domain.PaymentIdempotencyRecord;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentIdempotencyOperationTest {

    private static final UUID ADMIN_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID CLAIM_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID PAYMENT_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-28T00:00:00Z");

    private static final String KEY =
            "cod-collection-001";

    private final PaymentIdempotencyRepository repository =
            mock(PaymentIdempotencyRepository.class);

    private final PaymentIdempotencyService service =
            new PaymentIdempotencyService(
                    repository,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void shouldPreserveExactLegacyMockFingerprint() {
        String expected =
                "139d9097962b5c41c9f56d92cfb306cf"
                        + "35ebfe200d46e20e04476d965f2df143";

        assertThat(service.fingerprint(ORDER_ID))
                .isEqualTo(expected);

        assertThat(service.fingerprint(
                PaymentIdempotencyOperation.MOCK_PAYMENT,
                ORDER_ID
        )).isEqualTo(expected);
    }

    @Test
    void shouldUseDistinctStableCodCollectionFingerprint() {
        String codFingerprint = service.fingerprint(
                PaymentIdempotencyOperation.COD_COLLECTION,
                ORDER_ID
        );

        assertThat(codFingerprint).isEqualTo(
                "38dcecd3a9b220e6f6cea8b2fd1c865f"
                        + "7a8380ba9d7544ae6ce7751fe120b6eb"
        );

        assertThat(codFingerprint).isNotEqualTo(
                service.fingerprint(ORDER_ID)
        );
    }

    @Test
    void shouldCreateCodClaimOwnedByAdmin() {
        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(true);

        PaymentIdempotencyService.Decision decision =
                service.begin(
                        ADMIN_ID,
                        ORDER_ID,
                        PaymentIdempotencyOperation.COD_COLLECTION,
                        KEY
                );

        assertThat(decision.isReplay()).isFalse();
        assertThat(decision.claimId()).isNotNull();

        ArgumentCaptor<PaymentIdempotencyRecord> captor =
                ArgumentCaptor.forClass(
                        PaymentIdempotencyRecord.class
                );

        verify(repository)
                .insertIfAbsent(captor.capture());

        PaymentIdempotencyRecord claim =
                captor.getValue();

        assertThat(claim.id())
                .isEqualTo(decision.claimId());
        assertThat(claim.userId())
                .isEqualTo(ADMIN_ID);
        assertThat(claim.idempotencyKey())
                .isEqualTo(KEY);
        assertThat(claim.requestFingerprint()).isEqualTo(
                service.fingerprint(
                        PaymentIdempotencyOperation
                                .COD_COLLECTION,
                        ORDER_ID
                )
        );
        assertThat(claim.status()).isEqualTo(
                PaymentIdempotencyRecord.Status.PROCESSING
        );
        assertThat(claim.paymentId()).isNull();
        assertThat(claim.createdAt()).isEqualTo(NOW);
        assertThat(claim.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectKeyReusedAcrossPaymentOperations() {
        String mockFingerprint =
                service.fingerprint(ORDER_ID);

        PaymentIdempotencyRecord completedMockClaim =
                new PaymentIdempotencyRecord(
                        CLAIM_ID,
                        ADMIN_ID,
                        KEY,
                        mockFingerprint,
                        PaymentIdempotencyRecord
                                .Status.COMPLETED,
                        PAYMENT_ID,
                        NOW,
                        NOW.plusSeconds(1)
                );

        when(repository.insertIfAbsent(
                any(PaymentIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(
                ADMIN_ID,
                KEY
        )).thenReturn(
                Optional.of(completedMockClaim)
        );

        assertThatThrownBy(() ->
                service.begin(
                        ADMIN_ID,
                        ORDER_ID,
                        PaymentIdempotencyOperation
                                .COD_COLLECTION,
                        KEY
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode
                                .IDEMPOTENCY_KEY_REUSED
                )
        );
    }
}