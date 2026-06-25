package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.PayableOrderSnapshot;
import com.lul.shop.payment.application.port.PayableOrderClient;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderingPayableOrderAdapter implements PayableOrderClient {


    private final OrderingService orderingService;

    public OrderingPayableOrderAdapter(OrderingService orderingService) {
        this.orderingService = orderingService;
    }

    @Override
    public PayableOrderSnapshot getPayableOrder(UUID userId, UUID orderId) {
        try {
            OrderResult order = orderingService.getOrder(userId, orderId);

            return new PayableOrderSnapshot(
                    order.id(),
                    order.userId(),
                    order.totalAmount(),
                    order.status() == OrderStatus.PENDING_PAYMENT
            );
        } catch (BusinessException ex) {
            throw translateOrderingException(ex);
        }
    }

    @Override
    public void markOrderAsPaid(UUID userId, UUID orderId) {
        try {
            orderingService.markOrderAsPaid(userId, orderId);
        } catch (BusinessException ex) {
            throw translateOrderingException(ex);
        }
    }

    private BusinessException translateOrderingException(BusinessException ex) {
        if (ex.getErrorCode() == OrderingErrorCode.ORDER_NOT_FOUND) {
            return new BusinessException(PaymentErrorCode.ORDER_NOT_FOUND);
        }

        if (ex.getErrorCode() == OrderingErrorCode.ORDER_NOT_PAYABLE) {
            return new BusinessException(PaymentErrorCode.ORDER_NOT_PAYABLE);
        }

        return ex;
    }

}
