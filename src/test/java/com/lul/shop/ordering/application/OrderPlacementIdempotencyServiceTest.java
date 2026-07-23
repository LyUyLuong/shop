package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.domain.FulfillmentSnapshot;
import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.ShippingMethod;
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

import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    void shouldOwnNewClaimWithStableVersionedFingerprint() {
        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(true);

        OrderPlacementIdempotencyService.Decision decision =
                service.begin(command());

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
                "ccb0efedbfa5973e103e3fe66a78f119"
                        + "0f693e39869016d0f33a730ebc31b86d"
        );
        assertThat(claim.status()).isEqualTo(
                OrderIdempotencyRecord.Status.PROCESSING
        );
        assertThat(claim.orderId()).isNull();
        assertThat(claim.createdAt()).isEqualTo(NOW);
        assertThat(claim.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldReplayCompletedOrderForSameCanonicalRequest() {
        String fingerprint = service.fingerprint(command());

        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(
                        completedRecord(fingerprint)
                ));

        OrderPlacementIdempotencyService.Decision decision =
                service.begin(command());

        assertThat(decision.isReplay()).isTrue();
        assertThat(decision.claimId()).isNull();
        assertThat(decision.replayOrderId())
                .isEqualTo(ORDER_ID);
    }

    @Test
    void shouldRejectKeyReusedForDifferentCartVersion() {
        String previousFingerprint =
                service.fingerprint(command(3L, KEY));

        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.of(
                        completedRecord(previousFingerprint)
                ));

        assertThatThrownBy(() -> service.begin(command()))
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
        String fingerprint = service.fingerprint(command());

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

        assertInvalidState(() -> service.begin(command()));
    }

    @Test
    void shouldRejectMissingRecordAfterInsertConflict() {
        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(false);

        when(repository.findByUserIdAndKey(USER_ID, KEY))
                .thenReturn(Optional.empty());

        assertInvalidState(() -> service.begin(command()));
    }

    @Test
    void shouldAcceptExactKeyBoundariesWithoutNormalization() {
        String minimumKey = "Ab1._:-x";
        String maximumKey = "A".repeat(100);

        when(repository.insertIfAbsent(
                any(OrderIdempotencyRecord.class)
        )).thenReturn(true);

        service.begin(command(4L, minimumKey));
        service.begin(command(4L, maximumKey));

        ArgumentCaptor<OrderIdempotencyRecord> captor =
                ArgumentCaptor.forClass(
                        OrderIdempotencyRecord.class
                );

        verify(repository, times(2))
                .insertIfAbsent(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(
                        OrderIdempotencyRecord::idempotencyKey
                )
                .containsExactly(
                        minimumKey,
                        maximumKey
                );
    }

    @Test
    void shouldRejectMissingBoundaryAndSyntaxViolations() {
        assertInvalidKey(null);

        List.of(
                "",
                "A".repeat(7),
                "A".repeat(101),
                "checkout request",
                "checkout/request",
                "checkout@request",
                "\u0111at-hang-001",
                "checkout-request-001 "
        ).forEach(this::assertInvalidKey);

        verifyNoInteractions(repository);
    }

    @Test
    void shouldFingerprintTheCompleteCanonicalCheckoutRequest() {
        UUID anotherCartId = UUID.fromString(
                "55555555-5555-4555-8555-555555555555"
        );

        PlaceOrderCommand original = command();
        String originalFingerprint =
                service.fingerprint(original);

        FulfillmentSnapshot equivalentFulfillment =
                new FulfillmentSnapshot(
                        "  Nguyen   Van A  ",
                        "+84 (90) 123-4567",
                        "  123  Nguyen Trai,  Ho Chi Minh City  ",
                        ShippingMethod.STANDARD
                );

        assertThat(service.fingerprint(new PlaceOrderCommand(
                USER_ID,
                CART_ID,
                4L,
                KEY,
                equivalentFulfillment,
                OrderPaymentMode.MOCK
        ))).isEqualTo(originalFingerprint);

        assertThat(service.fingerprint(new PlaceOrderCommand(
                USER_ID,
                anotherCartId,
                4L,
                KEY,
                fulfillment(),
                OrderPaymentMode.MOCK
        ))).isNotEqualTo(originalFingerprint);

        assertThat(service.fingerprint(command(5L, KEY)))
                .isNotEqualTo(originalFingerprint);

        assertThat(service.fingerprint(new PlaceOrderCommand(
                USER_ID,
                CART_ID,
                4L,
                KEY,
                new FulfillmentSnapshot(
                        "Tran Van B",
                        fulfillment().recipientPhone(),
                        fulfillment().shippingAddress(),
                        ShippingMethod.STANDARD
                ),
                OrderPaymentMode.MOCK
        ))).isNotEqualTo(originalFingerprint);

        assertThat(service.fingerprint(new PlaceOrderCommand(
                USER_ID,
                CART_ID,
                4L,
                KEY,
                fulfillment(),
                OrderPaymentMode.COD
        ))).isNotEqualTo(originalFingerprint);
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

    private void assertInvalidKey(String idempotencyKey) {
        assertThatThrownBy(() ->
                service.begin(command(4L, idempotencyKey))
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
                                OrderingErrorCode
                                        .INVALID_IDEMPOTENCY_KEY
                        )
                );
    }

    private PlaceOrderCommand command() {
        return command(4L, KEY);
    }

    private PlaceOrderCommand command(
            long cartVersion,
            String idempotencyKey
    ) {
        return new PlaceOrderCommand(
                USER_ID,
                CART_ID,
                cartVersion,
                idempotencyKey,
                fulfillment(),
                OrderPaymentMode.MOCK
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
