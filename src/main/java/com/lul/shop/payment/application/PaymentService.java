package com.lul.shop.payment.application;

import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.application.port.PayableOrderTransitionSnapshot;
import com.lul.shop.payment.application.port.PaymentProvider;
import com.lul.shop.payment.application.port.PaymentProviderRequest;
import com.lul.shop.payment.application.port.PaymentProviderResult;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PayableOrderClient payableOrderClient;
    private final PaymentIdempotencyService idempotencyService;
    private final PaymentProviderRegistry providerRegistry;
    private final OutboxService outboxService;
    private final Clock clock;

    public PaymentService(
            PaymentRepository paymentRepository,
            PayableOrderClient payableOrderClient,
            PaymentIdempotencyService idempotencyService,
            PaymentProviderRegistry providerRegistry,
            OutboxService outboxService,
            Clock clock
    ) {
        this.paymentRepository = paymentRepository;
        this.payableOrderClient = payableOrderClient;
        this.idempotencyService = idempotencyService;
        this.providerRegistry = providerRegistry;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Transactional
    public PaymentResult payMock(PayOrderCommand command) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        PaymentIdempotencyService.Decision decision =
                idempotencyService.begin(
                        command.userId(),
                        command.orderId(),
                        command.idempotencyKey()
                );

        if (decision.isReplay()) {
            return toResult(
                    loadReplayPayment(
                            command,
                            decision.replayPaymentId()
                    )
            );
        }

        PayableOrderTransitionSnapshot order =
                payableOrderClient.transitionToPaid(
                        command.userId(),
                        command.orderId()
                );

        requireMatchingTransition(command, order);

        Payment payment = switch (order.outcome()) {
            case NEWLY_PAID ->
                    createSucceededPayment(
                            order,
                            PaymentMethod.MOCK
                    );
            case ALREADY_PAID ->
                    loadExistingPayment(
                            order,
                            PaymentMethod.MOCK
                    );
        };

        idempotencyService.complete(
                decision.claimId(),
                payment.getId()
        );

        return toResult(payment);
    }

    public PaymentResult getPayment(
            UUID userId,
            UUID paymentId
    ) {
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
        Objects.requireNonNull(
                paymentId,
                "paymentId must not be null"
        );

        Payment payment = paymentRepository
                .findByIdAndUserId(paymentId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                PaymentErrorCode.PAYMENT_NOT_FOUND
                        )
                );

        return toResult(payment);
    }

    private Payment loadReplayPayment(
            PayOrderCommand command,
            UUID paymentId
    ) {
        Payment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(this::invalidIdempotencyState);

        requireSucceededPayment(
                payment,
                command.userId(),
                command.orderId(),
                PaymentMethod.MOCK
        );

        log.info(
                "action=payment.replayed "
                        + "userId={} orderId={} paymentId={} "
                        + "result=success",
                payment.getUserId(),
                payment.getOrderId(),
                payment.getId()
        );

        return payment;
    }

    private Payment createSucceededPayment(
            PayableOrderTransitionSnapshot order,
            PaymentMethod method
    ) {
        Instant requestedAt = clock.instant();

        Payment payment = Payment.createPending(
                order.orderId(),
                order.userId(),
                order.totalAmount(),
                method
        );

        PaymentProvider provider =
                providerRegistry.resolve(method);

        PaymentProviderResult providerResult =
                Objects.requireNonNull(
                        provider.process(
                                new PaymentProviderRequest(
                                        payment.getId(),
                                        order.orderId(),
                                        order.userId(),
                                        order.totalAmount(),
                                        requestedAt
                                )
                        ),
                        "provider result must not be null"
                );

        if (!providerResult.isSucceeded()) {
            throw new BusinessException(
                    PaymentErrorCode
                            .PAYMENT_PROVIDER_REJECTED
            );
        }

        payment.succeed(providerResult.processedAt());

        Payment savedPayment =
                paymentRepository.save(payment);

        outboxService.recordOrderPaid(
                order.orderId(),
                savedPayment.getId(),
                order.userId()
        );

        log.info(
                "action=payment.succeeded "
                        + "userId={} orderId={} paymentId={} "
                        + "amount={} method={} status={}",
                savedPayment.getUserId(),
                savedPayment.getOrderId(),
                savedPayment.getId(),
                savedPayment.getAmount(),
                savedPayment.getMethod(),
                savedPayment.getStatus()
        );

        log.info(
                "action=payment.order_paid_event_requested "
                        + "userId={} orderId={} paymentId={}",
                order.userId(),
                order.orderId(),
                savedPayment.getId()
        );

        return savedPayment;
    }

    private Payment loadExistingPayment(
            PayableOrderTransitionSnapshot order,
            PaymentMethod method
    ) {
        Payment payment = paymentRepository
                .findByOrderId(order.orderId())
                .orElseThrow(this::invalidIdempotencyState);

        requireSucceededPayment(
                payment,
                order.userId(),
                order.orderId(),
                method
        );

        if (payment.getAmount()
                .compareTo(order.totalAmount()) != 0) {
            throw invalidIdempotencyState();
        }

        log.info(
                "action=payment.existing_reused "
                        + "userId={} orderId={} paymentId={} "
                        + "outcome=ALREADY_PAID result=success",
                payment.getUserId(),
                payment.getOrderId(),
                payment.getId()
        );

        return payment;
    }

    private void requireMatchingTransition(
            PayOrderCommand command,
            PayableOrderTransitionSnapshot order
    ) {
        if (
                !order.orderId().equals(command.orderId())
                        || !order.userId().equals(command.userId())
        ) {
            throw invalidIdempotencyState();
        }
    }

    private void requireSucceededPayment(
            Payment payment,
            UUID userId,
            UUID orderId,
            PaymentMethod method
    ) {
        if (
                !payment.getUserId().equals(userId)
                        || !payment.getOrderId().equals(orderId)
                        || payment.getMethod() != method
                        || payment.getStatus()
                        != PaymentStatus.SUCCEEDED
        ) {
            throw invalidIdempotencyState();
        }
    }

    private BusinessException invalidIdempotencyState() {
        return new BusinessException(
                PaymentErrorCode
                        .PAYMENT_IDEMPOTENCY_STATE_INVALID
        );
    }

    private PaymentResult toResult(Payment payment) {
        return new PaymentResult(
                payment.getId(),
                payment.getOrderId(),
                payment.getUserId(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getPaidAt(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}