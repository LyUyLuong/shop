package com.lul.shop.ordering.infrastructure.cart;

import com.lul.shop.cart.application.CartErrorCode;
import com.lul.shop.cart.application.CartService;
import com.lul.shop.cart.application.dto.CartItemResult;
import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.port.CheckoutCartSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartCheckoutAdapterTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID CART_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final UUID CART_ITEM_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final UUID PRODUCT_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static final long CART_VERSION = 7L;

    private static final Instant NOW =
            Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void shouldDelegateCheckoutClaimAndMapSnapshot() {
        CartService cartService = mock(CartService.class);

        CartCheckoutAdapter adapter =
                new CartCheckoutAdapter(cartService);

        CartResult cartResult = new CartResult(
                CART_ID,
                USER_ID,
                CART_VERSION,
                List.of(new CartItemResult(
                        CART_ITEM_ID,
                        PRODUCT_ID,
                        2
                )),
                NOW,
                NOW
        );

        when(cartService.claimForCheckout(
                USER_ID,
                CART_ID,
                CART_VERSION
        )).thenReturn(cartResult);

        CheckoutCartSnapshot snapshot =
                adapter.claimForCheckout(
                        USER_ID,
                        CART_ID,
                        CART_VERSION
                );

        assertThat(snapshot.id()).isEqualTo(CART_ID);
        assertThat(snapshot.userId()).isEqualTo(USER_ID);
        assertThat(snapshot.version()).isEqualTo(CART_VERSION);
        assertThat(snapshot.items()).hasSize(1);

        assertThat(snapshot.items().get(0).productId())
                .isEqualTo(PRODUCT_ID);

        assertThat(snapshot.items().get(0).quantity())
                .isEqualTo(2);

        verify(cartService).claimForCheckout(
                USER_ID,
                CART_ID,
                CART_VERSION
        );
    }

    @Test
    void shouldTranslateCartCheckoutConflictToOrderingConflict() {
        CartService cartService = mock(CartService.class);

        CartCheckoutAdapter adapter =
                new CartCheckoutAdapter(cartService);

        when(cartService.claimForCheckout(
                USER_ID,
                CART_ID,
                CART_VERSION
        )).thenThrow(new BusinessException(
                CartErrorCode.CART_CHECKOUT_CONFLICT
        ));

        assertThatThrownBy(() ->
                adapter.claimForCheckout(
                        USER_ID,
                        CART_ID,
                        CART_VERSION
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                OrderingErrorCode.CART_CHECKOUT_CONFLICT
                        )
                );

        verify(cartService).claimForCheckout(
                USER_ID,
                CART_ID,
                CART_VERSION
        );
    }

    @Test
    void shouldPropagateUnexpectedCartBusinessException() {
        CartService cartService = mock(CartService.class);

        CartCheckoutAdapter adapter =
                new CartCheckoutAdapter(cartService);

        BusinessException unexpectedFailure =
                new BusinessException(
                        CartErrorCode.CART_ITEM_NOT_FOUND
                );

        when(cartService.claimForCheckout(
                USER_ID,
                CART_ID,
                CART_VERSION
        )).thenThrow(unexpectedFailure);

        assertThatThrownBy(() ->
                adapter.claimForCheckout(
                        USER_ID,
                        CART_ID,
                        CART_VERSION
                )
        ).isSameAs(unexpectedFailure);

        verify(cartService).claimForCheckout(
                USER_ID,
                CART_ID,
                CART_VERSION
        );
    }
}