package com.lul.shop.ordering.application.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceOrderCommandTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID CART_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final String KEY =
            "Checkout.Request:ABC-001";

    @Test
    void shouldPreserveCheckoutIdentityVersionAndKey() {
        PlaceOrderCommand command =
                new PlaceOrderCommand(
                        USER_ID,
                        CART_ID,
                        4L,
                        KEY
                );

        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.cartId()).isEqualTo(CART_ID);
        assertThat(command.cartVersion()).isEqualTo(4L);
        assertThat(command.idempotencyKey()).isEqualTo(KEY);
    }

    @Test
    void shouldRejectMissingIdentityAndNegativeVersion() {
        assertThatThrownBy(() ->
                new PlaceOrderCommand(
                        null,
                        CART_ID,
                        4L,
                        KEY
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userId must not be null");

        assertThatThrownBy(() ->
                new PlaceOrderCommand(
                        USER_ID,
                        null,
                        4L,
                        KEY
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("cartId must not be null");

        assertThatThrownBy(() ->
                new PlaceOrderCommand(
                        USER_ID,
                        CART_ID,
                        -1L,
                        KEY
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "cartVersion must not be negative"
                );
    }
}