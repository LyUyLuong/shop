package com.lul.shop.payment.infrastructure.provider;

import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.infrastructure.checkout.ConfiguredCheckoutPaymentModePolicy;
import com.lul.shop.ordering.infrastructure.checkout.OrderingCheckoutProperties;
import com.lul.shop.payment.application.PaymentErrorCode;
import com.lul.shop.payment.application.PaymentProviderRegistry;
import com.lul.shop.payment.application.PaymentService;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.presentation.AdminCodCollectionController;
import com.lul.shop.payment.presentation.MockPaymentController;
import com.lul.shop.payment.presentation.PaymentController;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PaymentRuntimeProfileContractTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(
                            new ConfigDataApplicationContextInitializer()
                    )
                    .withUserConfiguration(
                            RuntimeProfileConfiguration.class
                    );

    @Test
    void shouldRemoveMockPaymentSurfaceFromProduction() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "ORDER_COD_ENABLED=true"
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .doesNotHaveBean(
                                    MockPaymentProvider.class
                            )
                            .doesNotHaveBean(
                                    MockPaymentController.class
                            )
                            .hasSingleBean(
                                    CodPaymentProvider.class
                            )
                            .hasSingleBean(
                                    PaymentController.class
                            )
                            .hasSingleBean(
                                    AdminCodCollectionController.class
                            );

                    ConfiguredCheckoutPaymentModePolicy policy =
                            context.getBean(
                                    ConfiguredCheckoutPaymentModePolicy.class
                            );

                    assertThat(policy.isEnabled(
                            OrderPaymentMode.MOCK
                    )).isFalse();

                    assertThat(policy.isEnabled(
                            OrderPaymentMode.COD
                    )).isTrue();

                    PaymentProviderRegistry registry =
                            context.getBean(
                                    PaymentProviderRegistry.class
                            );

                    assertThat(registry.resolve(
                            PaymentMethod.COD
                    )).isInstanceOf(
                            CodPaymentProvider.class
                    );

                    assertThatThrownBy(() ->
                            registry.resolve(PaymentMethod.MOCK)
                    ).isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(
                                    exception.getErrorCode()
                            ).isEqualTo(
                                    PaymentErrorCode
                                            .PAYMENT_PROVIDER_UNAVAILABLE
                            )
                    );
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void shouldRetainMockPaymentSurfaceOutsideProduction(
            String profile
    ) {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=" + profile
                )
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    MockPaymentProvider.class
                            )
                            .hasSingleBean(
                                    MockPaymentController.class
                            )
                            .hasSingleBean(
                                    CodPaymentProvider.class
                            )
                            .hasSingleBean(
                                    PaymentController.class
                            )
                            .hasSingleBean(
                                    AdminCodCollectionController.class
                            );

                    ConfiguredCheckoutPaymentModePolicy policy =
                            context.getBean(
                                    ConfiguredCheckoutPaymentModePolicy.class
                            );

                    assertThat(policy.isEnabled(
                            OrderPaymentMode.MOCK
                    )).isTrue();

                    PaymentProviderRegistry registry =
                            context.getBean(
                                    PaymentProviderRegistry.class
                            );

                    assertThat(registry.resolve(
                            PaymentMethod.MOCK
                    )).isInstanceOf(
                            MockPaymentProvider.class
                    );

                    assertThat(registry.resolve(
                            PaymentMethod.COD
                    )).isInstanceOf(
                            CodPaymentProvider.class
                    );
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
            OrderingCheckoutProperties.class
    )
    @Import({
            ConfiguredCheckoutPaymentModePolicy.class,
            PaymentProviderRegistry.class,
            MockPaymentProvider.class,
            CodPaymentProvider.class,
            MockPaymentController.class,
            PaymentController.class,
            AdminCodCollectionController.class
    })
    static class RuntimeProfileConfiguration {

        @Bean
        PaymentService paymentService() {
            return mock(PaymentService.class);
        }
    }
}