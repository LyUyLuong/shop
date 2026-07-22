package com.lul.shop.payment.application.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayOrderCommandTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final String KEY = "Payment-Key_001";

    @Test
    void shouldPreserveExactIdempotencyKey() {
        PayOrderCommand command =
                new PayOrderCommand(USER_ID, ORDER_ID, KEY);

        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.orderId()).isEqualTo(ORDER_ID);
        assertThat(command.idempotencyKey()).isEqualTo(KEY);
    }

    @Test
    void shouldRejectMissingRequiredFields() {
        assertThatThrownBy(() ->
                new PayOrderCommand(null, ORDER_ID, KEY)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("userId must not be null");

        assertThatThrownBy(() ->
                new PayOrderCommand(USER_ID, null, KEY)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("orderId must not be null");

        assertThatThrownBy(() ->
                new PayOrderCommand(USER_ID, ORDER_ID, null)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("idempotencyKey must not be null");
    }
}