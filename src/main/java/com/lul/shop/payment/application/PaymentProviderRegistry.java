package com.lul.shop.payment.application;

import com.lul.shop.payment.application.port.PaymentProvider;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class PaymentProviderRegistry {

    private final Map<PaymentMethod, PaymentProvider>
            providersByMethod;

    public PaymentProviderRegistry(
            List<PaymentProvider> providers
    ) {
        Objects.requireNonNull(
                providers,
                "providers must not be null"
        );

        Map<PaymentMethod, PaymentProvider> indexed =
                new EnumMap<>(PaymentMethod.class);

        for (PaymentProvider provider : providers) {
            Objects.requireNonNull(
                    provider,
                    "provider must not be null"
            );

            PaymentMethod method = Objects.requireNonNull(
                    provider.method(),
                    "provider method must not be null"
            );

            PaymentProvider duplicate =
                    indexed.putIfAbsent(method, provider);

            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate payment provider for method "
                                + method
                );
            }
        }

        this.providersByMethod = Map.copyOf(indexed);
    }

    public PaymentProvider resolve(PaymentMethod method) {
        Objects.requireNonNull(
                method,
                "method must not be null"
        );

        PaymentProvider provider =
                providersByMethod.get(method);

        if (provider == null) {
            throw new BusinessException(
                    PaymentErrorCode
                            .PAYMENT_PROVIDER_UNAVAILABLE
            );
        }

        return provider;
    }
}