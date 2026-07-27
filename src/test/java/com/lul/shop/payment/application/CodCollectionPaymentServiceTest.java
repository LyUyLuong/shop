package com.lul.shop.payment.application;

import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.payment.application.dto.CollectCodCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.CodCollectionOrderClient;
import com.lul.shop.payment.application.port.CodCollectionTransitionSnapshot;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.application.port.PaymentProvider;
import com.lul.shop.payment.application.port.PaymentProviderRequest;
import com.lul.shop.payment.application.port.PaymentProviderResult;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodCollectionPaymentServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID USER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID OTHER_ORDER_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444"
    );

    private static final UUID CLAIM_ID = UUID.fromString(
            "55555555-5555-4555-8555-555555555555"
    );

    private static final UUID PAYMENT_ID = UUID.fromString(
            "66666666-6666-4666-8666-666666666666"
    );

    private static final BigDecimal AMOUNT =
            new BigDecimal("230000.00");

    private static final Instant PAID_AT =
            Instant.parse("2026-07-28T01:00:00Z");

    private static final String KEY =
            "cod-collection-001";

    private final PaymentRepository paymentRepository =
            mock(PaymentRepository.class);

    private final PayableOrderClient payableOrderClient =
            mock(PayableOrderClient.class);

    private final CodCollectionOrderClient codCollectionOrderClient =
            mock(CodCollectionOrderClient.class);

    private final PaymentIdempotencyService idempotencyService =
            mock(PaymentIdempotencyService.class);

    private final PaymentProviderRegistry providerRegistry =
            mock(PaymentProviderRegistry.class);

    private final PaymentProvider paymentProvider =
            mock(PaymentProvider.class);

    private final OutboxService outboxService =
            mock(OutboxService.class);

    private final PaymentService service =
            new PaymentService(
                    paymentRepository,
                    payableOrderClient,
                    codCollectionOrderClient,
                    idempotencyService,
                    providerRegistry,
                    outboxService,
                    Clock.fixed(PAID_AT, ZoneOffset.UTC)
            );

    @Test
    void shouldCreateCodPaymentOutboxAndCompleteClaimInOrder() {
        when(idempotencyService.begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(codCollectionOrderClient.collect(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(
                transition(
                        ORDER_ID,
                        CodCollectionTransitionSnapshot
                                .Outcome.NEWLY_COLLECTED
                )
        );

        when(providerRegistry.resolve(PaymentMethod.COD))
                .thenReturn(paymentProvider);

        when(paymentProvider.process(
                any(PaymentProviderRequest.class)
        )).thenReturn(
                PaymentProviderResult.succeeded(PAID_AT)
        );

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        PaymentResult result =
                service.collectCod(command());

        InOrder ordered = inOrder(
                idempotencyService,
                codCollectionOrderClient,
                providerRegistry,
                paymentProvider,
                paymentRepository,
                outboxService
        );

        ordered.verify(idempotencyService).begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        );

        ordered.verify(codCollectionOrderClient)
                .collect(ADMIN_ID, ORDER_ID);

        ordered.verify(providerRegistry)
                .resolve(PaymentMethod.COD);

        ordered.verify(paymentProvider).process(
                argThat(request ->
                        request.orderId().equals(ORDER_ID)
                                && request.userId()
                                .equals(USER_ID)
                                && request.amount()
                                .compareTo(AMOUNT) == 0
                                && request.requestedAt()
                                .equals(PAID_AT)
                )
        );

        ArgumentCaptor<Payment> paymentCaptor =
                ArgumentCaptor.forClass(Payment.class);

        ordered.verify(paymentRepository)
                .save(paymentCaptor.capture());

        Payment savedPayment =
                paymentCaptor.getValue();

        ordered.verify(outboxService)
                .recordOrderPaid(
                        ORDER_ID,
                        savedPayment.getId(),
                        USER_ID
                );

        ordered.verify(idempotencyService)
                .complete(
                        CLAIM_ID,
                        savedPayment.getId()
                );

        assertThat(savedPayment.getOrderId())
                .isEqualTo(ORDER_ID);
        assertThat(savedPayment.getUserId())
                .isEqualTo(USER_ID);
        assertThat(savedPayment.getUserId())
                .isNotEqualTo(ADMIN_ID);
        assertThat(savedPayment.getMethod())
                .isEqualTo(PaymentMethod.COD);
        assertThat(savedPayment.getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(savedPayment.getAmount())
                .isEqualByComparingTo(AMOUNT);
        assertThat(savedPayment.getPaidAt())
                .isEqualTo(PAID_AT);

        assertThat(result.id())
                .isEqualTo(savedPayment.getId());
        assertThat(result.userId())
                .isEqualTo(USER_ID);
        assertThat(result.method())
                .isEqualTo(PaymentMethod.COD);

        verifyNoInteractions(payableOrderClient);
    }

    @Test
    void shouldReplayCodPaymentWithoutRelockingOrder() {
        Payment existing =
                succeededPayment(PaymentMethod.COD, AMOUNT);

        when(idempotencyService.begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.replay(
                        PAYMENT_ID
                )
        );

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(existing));

        PaymentResult result =
                service.collectCod(command());

        assertThat(result.id()).isEqualTo(PAYMENT_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.userId()).isNotEqualTo(ADMIN_ID);
        assertThat(result.method())
                .isEqualTo(PaymentMethod.COD);
        assertThat(result.status())
                .isEqualTo(PaymentStatus.SUCCEEDED);

        verify(paymentRepository).findById(PAYMENT_ID);
        verify(paymentRepository, never())
                .save(any(Payment.class));
        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class));

        verifyNoInteractions(
                codCollectionOrderClient,
                payableOrderClient,
                providerRegistry,
                paymentProvider,
                outboxService
        );
    }

    @Test
    void shouldReuseExistingPaymentWhenAlreadyCollected() {
        Payment existing =
                succeededPayment(PaymentMethod.COD, AMOUNT);

        when(idempotencyService.begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(codCollectionOrderClient.collect(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(
                transition(
                        ORDER_ID,
                        CodCollectionTransitionSnapshot
                                .Outcome.ALREADY_COLLECTED
                )
        );

        when(paymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(existing));

        PaymentResult result =
                service.collectCod(command());

        assertThat(result.id()).isEqualTo(PAYMENT_ID);

        verify(paymentRepository)
                .findByOrderId(ORDER_ID);
        verify(paymentRepository, never())
                .save(any(Payment.class));

        verify(idempotencyService)
                .complete(CLAIM_ID, PAYMENT_ID);

        verifyNoInteractions(
                payableOrderClient,
                providerRegistry,
                paymentProvider,
                outboxService
        );
    }

    @Test
    void shouldRejectInconsistentExistingCodPayment() {
        Payment mockPayment =
                succeededPayment(
                        PaymentMethod.MOCK,
                        AMOUNT
                );

        when(idempotencyService.begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(codCollectionOrderClient.collect(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(
                transition(
                        ORDER_ID,
                        CodCollectionTransitionSnapshot
                                .Outcome.ALREADY_COLLECTED
                )
        );

        when(paymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(mockPayment));

        assertInvalidState(() ->
                service.collectCod(command())
        );

        verify(paymentRepository, never())
                .save(any(Payment.class));
        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class));

        verifyNoInteractions(
                payableOrderClient,
                providerRegistry,
                paymentProvider,
                outboxService
        );
    }

    @Test
    void shouldRejectMismatchedOrderingTransition() {
        when(idempotencyService.begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(codCollectionOrderClient.collect(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(
                transition(
                        OTHER_ORDER_ID,
                        CodCollectionTransitionSnapshot
                                .Outcome.NEWLY_COLLECTED
                )
        );

        assertInvalidState(() ->
                service.collectCod(command())
        );

        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class));

        verifyNoInteractions(
                paymentRepository,
                payableOrderClient,
                providerRegistry,
                paymentProvider,
                outboxService
        );
    }

    @Test
    void shouldStopCodCollectionWhenProviderRejects() {
        when(idempotencyService.begin(
                ADMIN_ID,
                ORDER_ID,
                PaymentIdempotencyOperation.COD_COLLECTION,
                KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(codCollectionOrderClient.collect(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(
                transition(
                        ORDER_ID,
                        CodCollectionTransitionSnapshot
                                .Outcome.NEWLY_COLLECTED
                )
        );

        when(providerRegistry.resolve(PaymentMethod.COD))
                .thenReturn(paymentProvider);

        when(paymentProvider.process(
                any(PaymentProviderRequest.class)
        )).thenReturn(
                PaymentProviderResult.rejected(
                        "COD collection rejected"
                )
        );

        assertThatThrownBy(() ->
                service.collectCod(command())
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode
                                .PAYMENT_PROVIDER_REJECTED
                )
        );

        verify(paymentRepository, never())
                .save(any(Payment.class));
        verifyNoInteractions(outboxService);
        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class));
        verifyNoInteractions(payableOrderClient);
    }

    private CollectCodCommand command() {
        return new CollectCodCommand(
                ADMIN_ID,
                ORDER_ID,
                KEY
        );
    }

    private CodCollectionTransitionSnapshot transition(
            UUID orderId,
            CodCollectionTransitionSnapshot.Outcome outcome
    ) {
        return new CodCollectionTransitionSnapshot(
                orderId,
                USER_ID,
                AMOUNT,
                outcome
        );
    }

    private Payment succeededPayment(
            PaymentMethod method,
            BigDecimal amount
    ) {
        return new Payment(
                PAYMENT_ID,
                ORDER_ID,
                USER_ID,
                method,
                PaymentStatus.SUCCEEDED,
                amount,
                PAID_AT,
                null,
                PAID_AT,
                PAID_AT
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