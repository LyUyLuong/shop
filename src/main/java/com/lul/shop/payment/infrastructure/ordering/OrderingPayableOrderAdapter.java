package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderLifecycleService;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.payment.application.port.PayableOrderSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderingPayableOrderAdapter
        implements PayableOrderClient {

    private final OrderingService orderingService;
    private final OrderLifecycleService lifecycleService;

    public OrderingPayableOrderAdapter(
            OrderingService orderingService,
            OrderLifecycleService lifecycleService
    ) {
        this.orderingService = orderingService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public PayableOrderSnapshot getPayableOrder(
            UUID userId,
            UUID orderId
    ) {
        try {
            OrderResult order =
                    orderingService.getOrder(
                            userId,
                            orderId
                    );

            return new PayableOrderSnapshot(
                    order.id(),
                    order.userId(),
                    order.totalAmount(),
                    order.status()
                            == OrderStatus.PENDING_PAYMENT
            );
        } catch (BusinessException exception) {
            throw translateOrderingException(exception);
        }
    }

    @Override
    public void markOrderAsPaid(
            UUID userId,
            UUID orderId
    ) {
        try {
            lifecycleService.markPaidByPayment(
                    userId,
                    orderId
            );
        } catch (BusinessException exception) {
            throw translateOrderingException(exception);
        }
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