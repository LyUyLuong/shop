package com.lul.shop.payment.application;

import com.lul.shop.payment.application.port.PaymentProvider;
import com.lul.shop.payment.application.port.PaymentProviderRequest;
import com.lul.shop.payment.application.port.PaymentProviderResult;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.infrastructure.provider.CodPaymentProvider;
import com.lul.shop.payment.infrastructure.provider.MockPaymentProvider;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderFoundationTest {

    private static final Instant REQUESTED_AT =
            Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void shouldResolveExactlyOneProviderForEachMethod() {
        PaymentProvider mockProvider =
                new MockPaymentProvider();

        PaymentProvider codProvider =
                new CodPaymentProvider();

        PaymentProviderRegistry registry =
                new PaymentProviderRegistry(
                        List.of(mockProvider, codProvider)
                );

        assertThat(registry.resolve(PaymentMethod.MOCK))
                .isSameAs(mockProvider);

        assertThat(registry.resolve(PaymentMethod.COD))
                .isSameAs(codProvider);
    }

    @Test
    void shouldRejectDuplicateProviderMethod() {
        assertThatThrownBy(() ->
                new PaymentProviderRegistry(
                        List.of(
                                new MockPaymentProvider(),
                                new MockPaymentProvider()
                        )
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Duplicate payment provider for method MOCK"
                );
    }

    @Test
    void shouldReturnStableErrorForUnavailableProvider() {
        PaymentProviderRegistry registry =
                new PaymentProviderRegistry(
                        List.of(new MockPaymentProvider())
                );

        assertThatThrownBy(() ->
                registry.resolve(PaymentMethod.COD)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode
                                .PAYMENT_PROVIDER_UNAVAILABLE
                )
        );
    }

    @Test
    void shouldProcessEachLocalProviderSuccessfully() {
        PaymentProviderRequest request = request();

        for (PaymentProvider provider : List.of(
                new MockPaymentProvider(),
                new CodPaymentProvider()
        )) {
            PaymentProviderResult result =
                    provider.process(request);

            assertThat(result.isSucceeded()).isTrue();
            assertThat(result.processedAt())
                    .isEqualTo(REQUESTED_AT);
            assertThat(result.failureReason()).isNull();
        }
    }

    @Test
    void shouldEnforceProviderContractInvariants() {
        assertThatThrownBy(() ->
                new PaymentProviderRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("-0.01"),
                        REQUESTED_AT
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be >= 0");

        assertThatThrownBy(() ->
                new PaymentProviderResult(
                        PaymentProviderResult.Outcome.SUCCEEDED,
                        REQUESTED_AT,
                        "unexpected failure"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        PaymentProviderResult rejected =
                PaymentProviderResult.rejected(
                        "  payment declined  "
                );

        assertThat(rejected.isSucceeded()).isFalse();
        assertThat(rejected.processedAt()).isNull();
        assertThat(rejected.failureReason())
                .isEqualTo("payment declined");
    }

    private PaymentProviderRequest request() {
        return new PaymentProviderRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("130000.00"),
                REQUESTED_AT
        );
    }
}