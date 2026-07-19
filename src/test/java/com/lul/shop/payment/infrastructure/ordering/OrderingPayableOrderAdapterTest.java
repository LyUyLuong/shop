package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderLifecycleService;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.OrderingService;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.PayableOrderSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderingPayableOrderAdapterTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    @Test
    void shouldMapOrderSnapshotForPayment() {
        OrderingService orderingService =
                mock(OrderingService.class);
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(
                orderingService.getOrder(
                        USER_ID,
                        ORDER_ID
                )
        ).thenReturn(
                new OrderResult(
                        ORDER_ID,
                        USER_ID,
                        OrderStatus.PENDING_PAYMENT,
                        new BigDecimal("398000.00"),
                        List.of(),
                        null,
                        null
                )
        );

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(
                        orderingService,
                        lifecycleService
                );

        PayableOrderSnapshot snapshot =
                adapter.getPayableOrder(
                        USER_ID,
                        ORDER_ID
                );

        assertThat(snapshot.orderId())
                .isEqualTo(ORDER_ID);
        assertThat(snapshot.userId())
                .isEqualTo(USER_ID);
        assertThat(snapshot.totalAmount())
                .isEqualByComparingTo("398000.00");
        assertThat(snapshot.payable()).isTrue();

        verify(orderingService)
                .getOrder(USER_ID, ORDER_ID);
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void shouldDelegatePaymentTransitionToLifecycleService() {
        OrderingService orderingService =
                mock(OrderingService.class);
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(
                        orderingService,
                        lifecycleService
                );

        adapter.markOrderAsPaid(
                USER_ID,
                ORDER_ID
        );

        verify(lifecycleService)
                .markPaidByPayment(USER_ID, ORDER_ID);
        verifyNoInteractions(orderingService);
    }

    @Test
    void shouldTranslateOrderingNotPayableError() {
        OrderingService orderingService =
                mock(OrderingService.class);
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(
                lifecycleService.markPaidByPayment(
                        USER_ID,
                        ORDER_ID
                )
        ).thenThrow(
                new BusinessException(
                        OrderingErrorCode.ORDER_NOT_PAYABLE
                )
        );

        OrderingPayableOrderAdapter adapter =
                new OrderingPayableOrderAdapter(
                        orderingService,
                        lifecycleService
                );

        assertThatThrownBy(
                () -> adapter.markOrderAsPaid(
                        USER_ID,
                        ORDER_ID
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                PaymentErrorCode.ORDER_NOT_PAYABLE
                        )
                );
    }
}