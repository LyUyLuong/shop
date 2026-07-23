package com.lul.shop.ordering.presentation.dto.request;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.ShippingMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceOrderRequestValidationTest {

    private static final UUID CART_ID = UUID.fromString(
            "a1111111-1111-4111-8111-111111111111"
    );

    @Test
    void shouldAcceptCompleteCheckoutRequest() {
        assertThat(validate(validRequest())).isEmpty();
    }

    @Test
    void shouldRejectEveryMissingCheckoutField() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(propertyNames(validate(request)))
                .containsExactlyInAnyOrder(
                        "cartId",
                        "cartVersion",
                        "recipientName",
                        "recipientPhone",
                        "shippingAddress",
                        "shippingMethod",
                        "paymentMode"
                );
    }

    @Test
    void shouldRejectInvalidVersionAndTextLengths() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                CART_ID,
                -1L,
                "A",
                "1".repeat(31),
                "Too short",
                ShippingMethod.STANDARD,
                OrderPaymentMode.MOCK
        );

        assertThat(propertyNames(validate(request)))
                .containsExactlyInAnyOrder(
                        "cartVersion",
                        "recipientName",
                        "recipientPhone",
                        "shippingAddress"
                );
    }

    private PlaceOrderRequest validRequest() {
        return new PlaceOrderRequest(
                CART_ID,
                4L,
                "Nguyen Van A",
                "+84901234567",
                "123 Nguyen Trai, Ho Chi Minh City",
                ShippingMethod.STANDARD,
                OrderPaymentMode.MOCK
        );
    }

    private Set<ConstraintViolation<PlaceOrderRequest>> validate(
            PlaceOrderRequest request
    ) {
        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private Set<String> propertyNames(
            Set<ConstraintViolation<PlaceOrderRequest>> violations
    ) {
        return violations.stream()
                .map(violation ->
                        violation.getPropertyPath().toString()
                )
                .collect(java.util.stream.Collectors.toSet());
    }
}
