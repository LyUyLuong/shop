package com.lul.shop.ordering.application;

import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderPlacementIdempotencyServiceTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID CART_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID CLAIM_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-21T02:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String KEY =
            "checkout-request-001";

    private OrderIdempotencyRepository repository;
    private OrderPlacementIdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderIdempotencyRepository.class);

        service = new OrderPlacementIdempotencyService(
                repository,
                CLOCK
        );
    }

    @Test
    void shouldOwnNewClaimWithStableRequestFingerprint() {
        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(true);

        OrderPlacementIdempotencyService.Decision decision =
                service.begin(
                        USER_ID,
                        CART_ID,
                        4L,
                        KEY
                );

        assertThat(decision.isReplay()).isFalse();
        assertThat(decision.claimId()).isNotNull();
        assertThat(decision.replayOrderId()).isNull();

        ArgumentCaptor<OrderIdempotencyRecord> captor =
                ArgumentCaptor.forClass(
                        OrderIdempotencyRecord.class
                );

        verify(repository).insertIfAbsent(captor.capture());

        OrderIdempotencyRecord claim = captor.getValue();

        assertThat(claim.id()).isEqualTo(decision.claimId());
        assertThat(claim.userId()).isEqualTo(USER_ID);
        assertThat(claim.idempotencyKey()).isEqualTo(KEY);
        assertThat(claim.requestFingerprint()).isEqualTo(
                "16e89e8272a400a404f3d4c16cfbeac5"
                        + "efd9dcd35196e23e50f710d2bd43e39c"
        );
        assertThat(claim.status()).isEqualTo(
                OrderIdempotencyRecord.Status.PROCESSING
        );
        assertThat(claim.orderId()).isNull();
        assertThat(claim.createdAt()).isEqualTo(NOW);
        assertThat(claim.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldReplayCompletedOrderForSameRequest() {
        String fingerprint =
                service.fingerprint(CART_ID, 4L);

        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(
                        completedRecord(fingerprint)
                ));

        OrderPlacementIdempotencyService.Decision decision =
                service.begin(
                        USER_ID,
                        CART_ID,
                        4L,
                        KEY
                );

        assertThat(decision.isReplay()).isTrue();
        assertThat(decision.claimId()).isNull();
        assertThat(decision.replayOrderId())
                .isEqualTo(ORDER_ID);
    }

    @Test
    void shouldRejectKeyReusedForDifferentCartVersion() {
        String previousFingerprint =
                service.fingerprint(CART_ID, 3L);

        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(
                        completedRecord(previousFingerprint)
                ));

        assertThatThrownBy(() ->
                service.begin(
                        USER_ID,
                        CART_ID,
                        4L,
                        KEY
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode
                                        .IDEMPOTENCY_KEY_REUSED
                        )
                );
    }

    @Test
    void shouldRejectUnfinishedConflictingClaim() {
        String fingerprint =
                service.fingerprint(CART_ID, 4L);

        OrderIdempotencyRecord processing =
                new OrderIdempotencyRecord(
                        CLAIM_ID,
                        USER_ID,
                        KEY,
                        fingerprint,
                        OrderIdempotencyRecord.Status.PROCESSING,
                        null,
                        NOW,
                        NOW
                );

        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(processing));

        assertInvalidState(() ->
                service.begin(
                        USER_ID,
                        CART_ID,
                        4L,
                        KEY
                )
        );
    }

    @Test
    void shouldRejectMissingRecordAfterInsertConflict() {
        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.empty());

        assertInvalidState(() ->
                service.begin(
                        USER_ID,
                        CART_ID,
                        4L,
                        KEY
                )
        );
    }

    @Test
    void shouldRejectInvalidKeyBeforeCallingRepository() {
        assertThatThrownBy(() ->
                service.begin(
                        USER_ID,
                        CART_ID,
                        4L,
                        "short"
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode
                                        .INVALID_IDEMPOTENCY_KEY
                        )
                );

        verifyNoInteractions(repository);
    }

    @Test
    void shouldCompleteOwnedClaim() {
        when(repository.complete(
                CLAIM_ID,
                ORDER_ID,
                NOW
        )).thenReturn(true);

        service.complete(CLAIM_ID, ORDER_ID);

        verify(repository).complete(
                CLAIM_ID,
                ORDER_ID,
                NOW
        );
    }

    @Test
    void shouldRejectClaimThatCannotBeCompleted() {
        when(repository.complete(
                CLAIM_ID,
                ORDER_ID,
                NOW
        )).thenReturn(false);

        assertInvalidState(() ->
                service.complete(CLAIM_ID, ORDER_ID)
        );
    }

    private OrderIdempotencyRecord completedRecord(
            String fingerprint
    ) {
        return new OrderIdempotencyRecord(
                CLAIM_ID,
                USER_ID,
                KEY,
                fingerprint,
                OrderIdempotencyRecord.Status.COMPLETED,
                ORDER_ID,
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
                                OrderingErrorCode
                                        .ORDER_IDEMPOTENCY_STATE_INVALID
                        )
                );
    }
}