package com.lul.shop.payment.application;

import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.application.port.PayableOrderTransitionSnapshot;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final UUID PAYMENT_ID =
            UUID.fromString(
                    "33333333-3333-4333-8333-333333333333"
            );

    private static final UUID CLAIM_ID =
            UUID.fromString(
                    "44444444-4444-4444-8444-444444444444"
            );

    private static final String IDEMPOTENCY_KEY =
            "payment-request-001";

    private static final BigDecimal AMOUNT =
            new BigDecimal("398000.00");

    private static final Instant PAID_AT =
            Instant.parse("2026-06-29T10:00:00Z");

    private final PaymentRepository paymentRepository =
            mock(PaymentRepository.class);

    private final PayableOrderClient payableOrderClient =
            mock(PayableOrderClient.class);

    private final PaymentIdempotencyService idempotencyService =
            mock(PaymentIdempotencyService.class);

    private final OutboxService outboxService =
            mock(OutboxService.class);

    private final Clock clock =
            Clock.fixed(PAID_AT, ZoneOffset.UTC);

    private final PaymentService service =
            new PaymentService(
                    paymentRepository,
                    payableOrderClient,
                    idempotencyService,
                    outboxService,
                    clock
            );

    @Test
    void shouldCreatePaymentAndOutboxForNewPayment() {
        when(idempotencyService.begin(
                USER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(payableOrderClient.transitionToPaid(
                USER_ID,
                ORDER_ID
        )).thenReturn(transition(
                PayableOrderTransitionSnapshot.Outcome.NEWLY_PAID
        ));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        PaymentResult result = service.payMock(command());

        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.method())
                .isEqualTo(PaymentMethod.MOCK);
        assertThat(result.amount())
                .isEqualByComparingTo(AMOUNT);
        assertThat(result.paidAt()).isEqualTo(PAID_AT);

        ArgumentCaptor<Payment> paymentCaptor =
                ArgumentCaptor.forClass(Payment.class);

        verify(paymentRepository).save(
                paymentCaptor.capture()
        );

        Payment saved = paymentCaptor.getValue();

        verify(payableOrderClient).transitionToPaid(
                USER_ID,
                ORDER_ID
        );
        verify(outboxService).recordOrderPaid(
                ORDER_ID,
                saved.getId(),
                USER_ID
        );
        verify(idempotencyService).complete(
                CLAIM_ID,
                saved.getId()
        );
        verify(paymentRepository, never())
                .findByOrderId(any(UUID.class));
    }

    @Test
    void shouldReplayCompletedPaymentWithoutCallingOrdering() {
        Payment existing = succeededPayment(AMOUNT);

        when(idempotencyService.begin(
                USER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.replay(
                        PAYMENT_ID
                )
        );

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(existing));

        PaymentResult result = service.payMock(command());

        assertThat(result.id()).isEqualTo(PAYMENT_ID);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.status())
                .isEqualTo(PaymentStatus.SUCCEEDED);

        verify(paymentRepository).findById(PAYMENT_ID);
        verifyNoInteractions(payableOrderClient);
        verifyNoInteractions(outboxService);
        verify(paymentRepository, never())
                .save(any(Payment.class));
        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class));
    }

    @Test
    void shouldReuseExistingPaymentWhenOrderAlreadyPaid() {
        Payment existing = succeededPayment(AMOUNT);

        when(idempotencyService.begin(
                USER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(payableOrderClient.transitionToPaid(
                USER_ID,
                ORDER_ID
        )).thenReturn(transition(
                PayableOrderTransitionSnapshot.Outcome.ALREADY_PAID
        ));

        when(paymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(existing));

        PaymentResult result = service.payMock(command());

        assertThat(result.id()).isEqualTo(PAYMENT_ID);

        verify(paymentRepository).findByOrderId(ORDER_ID);
        verify(paymentRepository, never())
                .save(any(Payment.class));
        verifyNoInteractions(outboxService);
        verify(idempotencyService).complete(
                CLAIM_ID,
                PAYMENT_ID
        );
    }

    @Test
    void shouldRejectInconsistentExistingPayment() {
        Payment inconsistent = succeededPayment(
                new BigDecimal("399000.00")
        );

        when(idempotencyService.begin(
                USER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                PaymentIdempotencyService.Decision.owner(
                        CLAIM_ID
                )
        );

        when(payableOrderClient.transitionToPaid(
                USER_ID,
                ORDER_ID
        )).thenReturn(transition(
                PayableOrderTransitionSnapshot.Outcome.ALREADY_PAID
        ));

        when(paymentRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(inconsistent));

        assertThatThrownBy(() -> service.payMock(command()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                PaymentErrorCode
                                        .PAYMENT_IDEMPOTENCY_STATE_INVALID
                        )
                );

        verify(paymentRepository, never())
                .save(any(Payment.class));
        verifyNoInteractions(outboxService);
        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class));
    }

    private PayOrderCommand command() {
        return new PayOrderCommand(
                USER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY
        );
    }

    private PayableOrderTransitionSnapshot transition(
            PayableOrderTransitionSnapshot.Outcome outcome
    ) {
        return new PayableOrderTransitionSnapshot(
                ORDER_ID,
                USER_ID,
                AMOUNT,
                outcome
        );
    }

    private Payment succeededPayment(BigDecimal amount) {
        return new Payment(
                PAYMENT_ID,
                ORDER_ID,
                USER_ID,
                PaymentMethod.MOCK,
                PaymentStatus.SUCCEEDED,
                amount,
                PAID_AT,
                null,
                PAID_AT,
                PAID_AT
        );
    }
}
