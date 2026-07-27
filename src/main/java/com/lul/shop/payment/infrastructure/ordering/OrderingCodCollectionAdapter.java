package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderLifecycleService;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.dto.OrderCodCollectionTransitionResult;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.CodCollectionOrderClient;
import com.lul.shop.payment.application.port.CodCollectionTransitionSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderingCodCollectionAdapter
        implements CodCollectionOrderClient {

    private final OrderLifecycleService lifecycleService;

    public OrderingCodCollectionAdapter(
            OrderLifecycleService lifecycleService
    ) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    public CodCollectionTransitionSnapshot collect(
            UUID adminUserId,
            UUID orderId
    ) {
        try {
            OrderCodCollectionTransitionResult result =
                    lifecycleService.collectCodByAdmin(
                            adminUserId,
                            orderId
                    );

            return new CodCollectionTransitionSnapshot(
                    result.orderId(),
                    result.userId(),
                    result.totalAmount(),
                    mapOutcome(result.outcome())
            );
        } catch (BusinessException exception) {
            throw translateOrderingException(exception);
        }
    }

    private CodCollectionTransitionSnapshot.Outcome mapOutcome(
            OrderCodCollectionTransitionResult.Outcome outcome
    ) {
        return switch (outcome) {
            case NEWLY_COLLECTED ->
                    CodCollectionTransitionSnapshot.Outcome.NEWLY_COLLECTED;
            case ALREADY_COLLECTED ->
                    CodCollectionTransitionSnapshot.Outcome.ALREADY_COLLECTED;
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
                == OrderingErrorCode.COD_COLLECTION_NOT_ALLOWED) {
            return new BusinessException(
                    PaymentErrorCode.COD_COLLECTION_NOT_ALLOWED
            );
        }

        return exception;
    }
}