package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderLifecycleService;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.dto.OrderPaymentTransitionResult;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.PayableOrderTransitionSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderingPayableOrderAdapterTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    @Test
    void shouldMapNewlyPaidTransition() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.markPaidByPayment(
                USER_ID,
                ORDER_ID
        )).thenReturn(new OrderPaymentTransitionResult(
                ORDER_ID,
                USER_ID,
                new BigDecimal("398000.00"),
                OrderPaymentTransitionResult.Outcome.NEWLY_PAID
        ));

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(lifecycleService);

        PayableOrderTransitionSnapshot snapshot =
                adapter.transitionToPaid(USER_ID, ORDER_ID);

        assertThat(snapshot.orderId()).isEqualTo(ORDER_ID);
        assertThat(snapshot.userId()).isEqualTo(USER_ID);
        assertThat(snapshot.totalAmount())
                .isEqualByComparingTo("398000.00");
        assertThat(snapshot.outcome()).isEqualTo(
                PayableOrderTransitionSnapshot.Outcome.NEWLY_PAID
        );

        verify(lifecycleService)
                .markPaidByPayment(USER_ID, ORDER_ID);
    }

    @Test
    void shouldMapAlreadyPaidTransition() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.markPaidByPayment(
                USER_ID,
                ORDER_ID
        )).thenReturn(new OrderPaymentTransitionResult(
                ORDER_ID,
                USER_ID,
                new BigDecimal("398000.00"),
                OrderPaymentTransitionResult.Outcome.ALREADY_PAID
        ));

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(lifecycleService);

        PayableOrderTransitionSnapshot snapshot =
                adapter.transitionToPaid(USER_ID, ORDER_ID);

        assertThat(snapshot.outcome()).isEqualTo(
                PayableOrderTransitionSnapshot.Outcome.ALREADY_PAID
        );
    }

    @Test
    void shouldTranslateOrderingNotPayableError() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.markPaidByPayment(
                USER_ID,
                ORDER_ID
        )).thenThrow(new BusinessException(
                OrderingErrorCode.ORDER_NOT_PAYABLE
        ));

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(lifecycleService);

        assertThatThrownBy(() ->
                adapter.transitionToPaid(USER_ID, ORDER_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_NOT_PAYABLE)
        );
    }

    @Test
    void shouldTranslateOrderingNotFoundError() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.markPaidByPayment(
                USER_ID,
                ORDER_ID
        )).thenThrow(new BusinessException(
                OrderingErrorCode.ORDER_NOT_FOUND
        ));

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(lifecycleService);

        assertThatThrownBy(() ->
                adapter.transitionToPaid(USER_ID, ORDER_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_NOT_FOUND)
        );
    }
}