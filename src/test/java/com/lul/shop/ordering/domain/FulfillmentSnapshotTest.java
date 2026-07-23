package com.lul.shop.ordering.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulfillmentSnapshotTest {

    @Test
    void shouldCanonicalizeFulfillmentInput() {
        FulfillmentSnapshot snapshot =
                new FulfillmentSnapshot(
                        "  Nguyen   Van  A  ",
                        " +84 (912) 345-678 ",
                        "  123   Nguyen Trai,\nWard 2  ",
                        ShippingMethod.STANDARD
                );

        assertThat(snapshot.recipientName())
                .isEqualTo("Nguyen Van A");

        assertThat(snapshot.recipientPhone())
                .isEqualTo("+84912345678");

        assertThat(snapshot.shippingAddress())
                .isEqualTo(
                        "123 Nguyen Trai, Ward 2"
                );

        assertThat(snapshot.shippingMethod())
                .isEqualTo(ShippingMethod.STANDARD);
    }

    @Test
    void shouldRejectInvalidNameAndAddress() {
        assertThatThrownBy(() ->
                new FulfillmentSnapshot(
                        "A",
                        "0912345678",
                        "123 Nguyen Trai, Ward 2",
                        ShippingMethod.STANDARD
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "recipientName must contain "
                                + "between 2 and 100 characters"
                );

        assertThatThrownBy(() ->
                new FulfillmentSnapshot(
                        "Nguyen Van A",
                        "0912345678",
                        "Short",
                        ShippingMethod.STANDARD
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "shippingAddress must contain "
                                + "between 10 and 500 characters"
                );
    }

    @Test
    void shouldRejectInvalidPhone() {
        assertThatThrownBy(() ->
                new FulfillmentSnapshot(
                        "Nguyen Van A",
                        "0912-ABC-678",
                        "123 Nguyen Trai, Ward 2",
                        ShippingMethod.STANDARD
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage("recipientPhone is invalid");

        assertThatThrownBy(() ->
                new FulfillmentSnapshot(
                        "Nguyen Van A",
                        "1234567",
                        "123 Nguyen Trai, Ward 2",
                        ShippingMethod.STANDARD
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage("recipientPhone is invalid");
    }

    @Test
    void shouldRejectMissingShippingMethod() {
        assertThatThrownBy(() ->
                new FulfillmentSnapshot(
                        "Nguyen Van A",
                        "0912345678",
                        "123 Nguyen Trai, Ward 2",
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage(
                        "shippingMethod must not be null"
                );
    }
}