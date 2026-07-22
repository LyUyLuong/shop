package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderLifecycleService;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.dto.OrderPaymentTransitionResult;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.application.port.PayableOrderTransitionSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderingPayableOrderAdapter
        implements PayableOrderClient {

    private final OrderLifecycleService lifecycleService;

    public OrderingPayableOrderAdapter(
            OrderLifecycleService lifecycleService
    ) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    public PayableOrderTransitionSnapshot transitionToPaid(
            UUID userId,
            UUID orderId
    ) {
        try {
            OrderPaymentTransitionResult result =
                    lifecycleService.markPaidByPayment(
                            userId,
                            orderId
                    );

            return new PayableOrderTransitionSnapshot(
                    result.orderId(),
                    result.userId(),
                    result.totalAmount(),
                    mapOutcome(result.outcome())
            );
        } catch (BusinessException exception) {
            throw translateOrderingException(exception);
        }
    }

    private PayableOrderTransitionSnapshot.Outcome mapOutcome(
            OrderPaymentTransitionResult.Outcome outcome
    ) {
        return switch (outcome) {
            case NEWLY_PAID ->
                    PayableOrderTransitionSnapshot
                            .Outcome.NEWLY_PAID;
            case ALREADY_PAID ->
                    PayableOrderTransitionSnapshot
                            .Outcome.ALREADY_PAID;
        };
    }

    private BusinessException translateOrderingException(
            BusinessException exception
    ) {
        if (exception.getErrorCode()
                == OrderingErrorCode.ORDER_NOT_FOUND) {
            return new BusinessException(
                    PaymentErrorCode.ORDER_NOT_FOUND
            );
        }

        if (exception.getErrorCode()
                == OrderingErrorCode.ORDER_NOT_PAYABLE) {
            return new BusinessException(
                    PaymentErrorCode.ORDER_NOT_PAYABLE
            );
        }

        return exception;
    }
}