package com.lul.shop.catalog.presentation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class UpdateProductRequestValidationTest {

    @ParameterizedTest
    @MethodSource("invalidExpectedVersions")
    void shouldRejectInvalidExpectedVersion(
            Long expectedVersion,
            String expectedMessage
    ) {
        Set<ConstraintViolation<UpdateProductRequest>> violations =
                validate(expectedVersion);

        assertThat(violations)
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("expectedVersion");
                    assertThat(violation.getMessage())
                            .isEqualTo(expectedMessage);
                });
    }

    private static Stream<Arguments> invalidExpectedVersions() {
        return Stream.of(
                arguments(null, "Expected version is required"),
                arguments(-1L, "Expected version must be >= 0")
        );
    }

    private Set<ConstraintViolation<UpdateProductRequest>> validate(
            Long expectedVersion
    ) {
        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(
                    new UpdateProductRequest(
                            "SKU-001",
                            "Running Shoes",
                            "Daily shoes",
                            new BigDecimal("199000.00"),
                            8,
                            expectedVersion
                    )
            );
        }
    }
}
