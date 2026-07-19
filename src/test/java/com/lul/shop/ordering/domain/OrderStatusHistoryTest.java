package com.lul.shop.ordering.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusHistoryTest {

    private static final UUID ORDER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID ADMIN_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID HISTORY_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void shouldRecordAdminChangeWithAdminIdentity() {
        OrderStatusHistory history =
                OrderStatusHistory.recordAdminChange(
                        ORDER_ID,
                        ADMIN_ID,
                        OrderStatus.PAID,
                        OrderStatus.PACKING,
                        "  Start packing  "
                );

        assertThat(history.getId()).isNotNull();
        assertThat(history.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(history.getActorType())
                .isEqualTo(OrderStatusChangeActorType.ADMIN);
        assertThat(history.getActorUserId()).isEqualTo(ADMIN_ID);
        assertThat(history.getFromStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.PACKING);
        assertThat(history.getReason()).isEqualTo("Start packing");
        assertThat(history.getCreatedAt()).isNull();
    }

    @Test
    void shouldRecordPaymentChangeWithoutUserIdentity() {
        OrderStatusHistory history =
                OrderStatusHistory.recordPaymentChange(
                        ORDER_ID,
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PAID,
                        "Payment succeeded"
                );

        assertThat(history.getActorType())
                .isEqualTo(OrderStatusChangeActorType.PAYMENT);
        assertThat(history.getActorUserId()).isNull();
        assertThat(history.getFromStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void shouldRecordSystemExpiryWithoutUserIdentity() {
        OrderStatusHistory history =
                OrderStatusHistory.recordSystemChange(
                        ORDER_ID,
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.EXPIRED,
                        "Payment deadline expired"
                );

        assertThat(history.getActorType())
                .isEqualTo(OrderStatusChangeActorType.SYSTEM);
        assertThat(history.getActorUserId()).isNull();
        assertThat(history.getFromStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(history.getToStatus())
                .isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void shouldRejectUnexpectedPaymentTransition() {
        assertThatThrownBy(
                () -> OrderStatusHistory.recordPaymentChange(
                        ORDER_ID,
                        OrderStatus.PAID,
                        OrderStatus.PACKING,
                        "Invalid payment transition"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING_PAYMENT -> PAID");
    }

    @Test
    void shouldRejectUnexpectedSystemTransition() {
        assertThatThrownBy(
                () -> OrderStatusHistory.recordSystemChange(
                        ORDER_ID,
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.CANCELLED,
                        "Invalid system transition"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING_PAYMENT -> EXPIRED");
    }

    @Test
    void shouldReconstitutePersistedAdminHistory() {
        OrderStatusHistory history =
                OrderStatusHistory.reconstitute(
                        HISTORY_ID,
                        ORDER_ID,
                        OrderStatus.PAID,
                        OrderStatus.PACKING,
                        OrderStatusChangeActorType.ADMIN,
                        ADMIN_ID,
                        "Start packing",
                        CREATED_AT
                );

        assertThat(history.getId()).isEqualTo(HISTORY_ID);
        assertThat(history.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(history.getActorType())
                .isEqualTo(OrderStatusChangeActorType.ADMIN);
        assertThat(history.getActorUserId()).isEqualTo(ADMIN_ID);
        assertThat(history.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldRejectReconstitutedAdminWithoutUserIdentity() {
        assertThatThrownBy(
                () -> OrderStatusHistory.reconstitute(
                        HISTORY_ID,
                        ORDER_ID,
                        OrderStatus.PAID,
                        OrderStatus.PACKING,
                        OrderStatusChangeActorType.ADMIN,
                        null,
                        "Start packing",
                        CREATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "actorUserId must not be null"
                );
    }

    @Test
    void shouldRejectReconstitutedMachineActorWithUserIdentity() {
        assertThatThrownBy(
                () -> OrderStatusHistory.reconstitute(
                        HISTORY_ID,
                        ORDER_ID,
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PAID,
                        OrderStatusChangeActorType.PAYMENT,
                        ADMIN_ID,
                        "Payment succeeded",
                        CREATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "actorUserId must be null"
                );
    }

    @Test
    void shouldRejectReconstitutedInvalidPaymentTransition() {
        assertThatThrownBy(
                () -> OrderStatusHistory.reconstitute(
                        HISTORY_ID,
                        ORDER_ID,
                        OrderStatus.PAID,
                        OrderStatus.PACKING,
                        OrderStatusChangeActorType.PAYMENT,
                        null,
                        "Invalid payment history",
                        CREATED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING_PAYMENT -> PAID");
    }

    @Test
    void shouldRequireCreatedAtWhenReconstituting() {
        assertThatThrownBy(
                () -> OrderStatusHistory.reconstitute(
                        HISTORY_ID,
                        ORDER_ID,
                        OrderStatus.PAID,
                        OrderStatus.PACKING,
                        OrderStatusChangeActorType.ADMIN,
                        ADMIN_ID,
                        "Start packing",
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt must not be null");
    }
}