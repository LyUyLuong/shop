package com.lul.shop.payment.application;

import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.PayableOrderSnapshot;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PayableOrderClient payableOrderClient;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository,
                          PayableOrderClient payableOrderClient,
                          Clock clock) {
        this.paymentRepository = paymentRepository;
        this.payableOrderClient = payableOrderClient;
        this.clock = clock;
    }

    @Transactional
    public PaymentResult payMock(PayOrderCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        if (paymentRepository.existsByOrderId(command.orderId())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_EXISTS);
        }

        PayableOrderSnapshot order = payableOrderClient.getPayableOrder(
                command.userId(),
                command.orderId()
        );

        if (!order.payable()) {
            throw new BusinessException(PaymentErrorCode.ORDER_NOT_PAYABLE);
        }

        Payment payment = Payment.createSucceededMock(
                order.orderId(),
                order.userId(),
                order.totalAmount(),
                Instant.now(clock)
        );

        Payment savedPayment = paymentRepository.save(payment);

        payableOrderClient.markOrderAsPaid(command.userId(), command.orderId());

        return toResult(savedPayment);
    }

    public PaymentResult getPayment(UUID userId, UUID paymentId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");

        Payment payment = paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return toResult(payment);
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