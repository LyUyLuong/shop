package com.lul.shop.payment.infrastructure.ordering;

import com.lul.shop.ordering.application.OrderLifecycleService;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.dto.OrderCodCollectionTransitionResult;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.port.CodCollectionTransitionSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderingCodCollectionAdapterTest {

    private static final UUID ADMIN_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID USER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    @Test
    void shouldMapNewlyCollectedTransition() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.collectCodByAdmin(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(new OrderCodCollectionTransitionResult(
                ORDER_ID,
                USER_ID,
                new BigDecimal("230000.00"),
                OrderCodCollectionTransitionResult
                        .Outcome.NEWLY_COLLECTED
        ));

        OrderingCodCollectionAdapter adapter =
                new OrderingCodCollectionAdapter(
                        lifecycleService
                );

        CodCollectionTransitionSnapshot snapshot =
                adapter.collect(ADMIN_ID, ORDER_ID);

        assertThat(snapshot.orderId())
                .isEqualTo(ORDER_ID);
        assertThat(snapshot.userId())
                .isEqualTo(USER_ID);
        assertThat(snapshot.totalAmount())
                .isEqualByComparingTo("230000.00");
        assertThat(snapshot.outcome()).isEqualTo(
                CodCollectionTransitionSnapshot
                        .Outcome.NEWLY_COLLECTED
        );

        verify(lifecycleService)
                .collectCodByAdmin(ADMIN_ID, ORDER_ID);
    }

    @Test
    void shouldMapAlreadyCollectedTransition() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.collectCodByAdmin(
                ADMIN_ID,
                ORDER_ID
        )).thenReturn(new OrderCodCollectionTransitionResult(
                ORDER_ID,
                USER_ID,
                new BigDecimal("230000.00"),
                OrderCodCollectionTransitionResult
                        .Outcome.ALREADY_COLLECTED
        ));

        OrderingCodCollectionAdapter adapter =
                new OrderingCodCollectionAdapter(
                        lifecycleService
                );

        CodCollectionTransitionSnapshot snapshot =
                adapter.collect(ADMIN_ID, ORDER_ID);

        assertThat(snapshot.outcome()).isEqualTo(
                CodCollectionTransitionSnapshot
                        .Outcome.ALREADY_COLLECTED
        );
    }

    @Test
    void shouldTranslateOrderingNotFoundError() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.collectCodByAdmin(
                ADMIN_ID,
                ORDER_ID
        )).thenThrow(new BusinessException(
                OrderingErrorCode.ORDER_NOT_FOUND
        ));

        OrderingCodCollectionAdapter adapter =
                new OrderingCodCollectionAdapter(
                        lifecycleService
                );

        assertThatThrownBy(() ->
                adapter.collect(ADMIN_ID, ORDER_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode.ORDER_NOT_FOUND
                )
        );
    }

    @Test
    void shouldTranslateCollectionNotAllowedError() {
        OrderLifecycleService lifecycleService =
                mock(OrderLifecycleService.class);

        when(lifecycleService.collectCodByAdmin(
                ADMIN_ID,
                ORDER_ID
        )).thenThrow(new BusinessException(
                OrderingErrorCode
                        .COD_COLLECTION_NOT_ALLOWED
        ));

        OrderingCodCollectionAdapter adapter =
                new OrderingCodCollectionAdapter(
                        lifecycleService
                );

        assertThatThrownBy(() ->
                adapter.collect(ADMIN_ID, ORDER_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode
                                .COD_COLLECTION_NOT_ALLOWED
                )
        );
    }
}