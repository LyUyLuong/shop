package com.lul.shop.payment.application.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectCodCommandTest {

    private static final UUID ADMIN_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final String KEY =
            "cod-collection-001";

    @Test
    void shouldPreserveExactCommandValues() {
        CollectCodCommand command =
                new CollectCodCommand(
                        ADMIN_ID,
                        ORDER_ID,
                        KEY
                );

        assertThat(command.adminUserId())
                .isEqualTo(ADMIN_ID);
        assertThat(command.orderId())
                .isEqualTo(ORDER_ID);
        assertThat(command.idempotencyKey())
                .isEqualTo(KEY);
    }

    @Test
    void shouldRejectMissingRequiredValues() {
        assertThatThrownBy(() ->
                new CollectCodCommand(
                        null,
                        ORDER_ID,
                        KEY
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "adminUserId must not be null"
                );

        assertThatThrownBy(() ->
                new CollectCodCommand(
                        ADMIN_ID,
                        null,
                        KEY
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "orderId must not be null"
                );

        assertThatThrownBy(() ->
                new CollectCodCommand(
                        ADMIN_ID,
                        ORDER_ID,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "idempotencyKey must not be null"
                );
    }
}