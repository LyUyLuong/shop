package com.lul.shop.payment.application;

import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.payment.application.dto.CollectCodCommand;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.*;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final CodCollectionOrderClient codCollectionOrderClient;
    private final PaymentIdempotencyService idempotencyService;
    private final PaymentProviderRegistry providerRegistry;
    private final OutboxService outboxService;
    private final Clock clock;

    public PaymentService(
            PaymentRepository paymentRepository,
            PayableOrderClient payableOrderClient,
            CodCollectionOrderClient codCollectionOrderClient,
            PaymentIdempotencyService idempotencyService,
            PaymentProviderRegistry providerRegistry,
            OutboxService outboxService,
            Clock clock
    ) {
        this.paymentRepository = paymentRepository;
        this.payableOrderClient = payableOrderClient;
        this.codCollectionOrderClient =
                codCollectionOrderClient;
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
                    loadMockReplayPayment(
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
                            order.orderId(),
                            order.userId(),
                            order.totalAmount(),
                            PaymentMethod.MOCK
                    );
            case ALREADY_PAID ->
                    loadExistingPayment(
                            order.orderId(),
                            order.userId(),
                            order.totalAmount(),
                            PaymentMethod.MOCK,
                            "ALREADY_PAID"
                    );
        };

        idempotencyService.complete(
                decision.claimId(),
                payment.getId()
        );

        return toResult(payment);
    }

    @Transactional
    public PaymentResult collectCod(
            CollectCodCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        PaymentIdempotencyService.Decision decision =
                idempotencyService.begin(
                        command.adminUserId(),
                        command.orderId(),
                        PaymentIdempotencyOperation
                                .COD_COLLECTION,
                        command.idempotencyKey()
                );

        if (decision.isReplay()) {
            return toResult(
                    loadCodReplayPayment(
                            command,
                            decision.replayPaymentId()
                    )
            );
        }

        CodCollectionTransitionSnapshot order =
                codCollectionOrderClient.collect(
                        command.adminUserId(),
                        command.orderId()
                );

        requireMatchingTransition(command, order);

        Payment payment = switch (order.outcome()) {
            case NEWLY_COLLECTED ->
                    createSucceededPayment(
                            order.orderId(),
                            order.userId(),
                            order.totalAmount(),
                            PaymentMethod.COD
                    );
            case ALREADY_COLLECTED ->
                    loadExistingPayment(
                            order.orderId(),
                            order.userId(),
                            order.totalAmount(),
                            PaymentMethod.COD,
                            "ALREADY_COLLECTED"
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
                                PaymentErrorCode
                                        .PAYMENT_NOT_FOUND
                        )
                );

        return toResult(payment);
    }

    private Payment loadMockReplayPayment(
            PayOrderCommand command,
            UUID paymentId
    ) {
        Payment payment = loadReplayPayment(
                command.orderId(),
                paymentId,
                PaymentMethod.MOCK
        );

        requirePaymentOwner(
                payment,
                command.userId()
        );

        return payment;
    }

    private Payment loadCodReplayPayment(
            CollectCodCommand command,
            UUID paymentId
    ) {
        return loadReplayPayment(
                command.orderId(),
                paymentId,
                PaymentMethod.COD
        );
    }

    private Payment loadReplayPayment(
            UUID orderId,
            UUID paymentId,
            PaymentMethod method
    ) {
        Payment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(this::invalidIdempotencyState);

        requireSucceededPayment(
                payment,
                orderId,
                method
        );

        log.info(
                "action=payment.replayed "
                        + "userId={} orderId={} paymentId={} "
                        + "method={} result=success",
                payment.getUserId(),
                payment.getOrderId(),
                payment.getId(),
                payment.getMethod()
        );

        return payment;
    }

    private Payment createSucceededPayment(
            UUID orderId,
            UUID userId,
            BigDecimal totalAmount,
            PaymentMethod method
    ) {
        Instant requestedAt = clock.instant();

        Payment payment = Payment.createPending(
                orderId,
                userId,
                totalAmount,
                method
        );

        PaymentProvider provider =
                providerRegistry.resolve(method);

        PaymentProviderResult providerResult =
                Objects.requireNonNull(
                        provider.process(
                                new PaymentProviderRequest(
                                        payment.getId(),
                                        orderId,
                                        userId,
                                        totalAmount,
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

        payment.succeed(
                providerResult.processedAt()
        );

        Payment savedPayment =
                paymentRepository.save(payment);

        outboxService.recordOrderPaid(
                orderId,
                savedPayment.getId(),
                userId
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
                userId,
                orderId,
                savedPayment.getId()
        );

        return savedPayment;
    }

    private Payment loadExistingPayment(
            UUID orderId,
            UUID userId,
            BigDecimal totalAmount,
            PaymentMethod method,
            String outcome
    ) {
        Payment payment = paymentRepository
                .findByOrderId(orderId)
                .orElseThrow(this::invalidIdempotencyState);

        requireSucceededPayment(
                payment,
                orderId,
                method
        );

        requirePaymentOwner(payment, userId);

        if (payment.getAmount()
                .compareTo(totalAmount) != 0) {
            throw invalidIdempotencyState();
        }

        log.info(
                "action=payment.existing_reused "
                        + "userId={} orderId={} paymentId={} "
                        + "method={} outcome={} result=success",
                payment.getUserId(),
                payment.getOrderId(),
                payment.getId(),
                payment.getMethod(),
                outcome
        );

        return payment;
    }

    private void requireMatchingTransition(
            PayOrderCommand command,
            PayableOrderTransitionSnapshot order
    ) {
        if (
                !order.orderId().equals(command.orderId())
                        || !order.userId()
                        .equals(command.userId())
        ) {
            throw invalidIdempotencyState();
        }
    }

    private void requireMatchingTransition(
            CollectCodCommand command,
            CodCollectionTransitionSnapshot order
    ) {
        if (!order.orderId()
                .equals(command.orderId())) {
            throw invalidIdempotencyState();
        }
    }

    private void requireSucceededPayment(
            Payment payment,
            UUID orderId,
            PaymentMethod method
    ) {
        if (
                !payment.getOrderId().equals(orderId)
                        || payment.getMethod() != method
                        || payment.getStatus()
                        != PaymentStatus.SUCCEEDED
        ) {
            throw invalidIdempotencyState();
        }
    }

    private void requirePaymentOwner(
            Payment payment,
            UUID userId
    ) {
        if (!payment.getUserId().equals(userId)) {
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