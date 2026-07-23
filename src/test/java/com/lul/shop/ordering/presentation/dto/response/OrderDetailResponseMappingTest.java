package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.AdminOrderDetailResult;
import com.lul.shop.ordering.application.dto.OrderFulfillmentResult;
import com.lul.shop.ordering.application.dto.OrderItemResult;
import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.ShippingMethod;
import com.lul.shop.ordering.presentation.OrderItemImageUrlResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDetailResponseMappingTest {

    private static final UUID ORDER_ID = UUID.fromString(
            "a1111111-1111-4111-8111-111111111111"
    );

    private static final UUID USER_ID = UUID.fromString(
            "a2222222-2222-4222-8222-222222222222"
    );

    private static final UUID ITEM_ID = UUID.fromString(
            "a3333333-3333-4333-8333-333333333333"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "a4444444-4444-4444-8444-444444444444"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-23T03:00:00Z");

    private final OrderItemImageUrlResolver imageUrlResolver =
            new OrderItemImageUrlResolver(
                    "https://shop.example.com/"
            );

    @Test
    void shouldExposeCustomerCheckoutSnapshotAndAmounts() {
        OrderResponse response = OrderResponse.from(
                new OrderResult(
                        ORDER_ID,
                        USER_ID,
                        OrderStatus.PENDING_PAYMENT,
                        OrderPaymentMode.MOCK,
                        new BigDecimal("199000.00"),
                        new BigDecimal("30000.00"),
                        new BigDecimal("229000.00"),
                        fulfillment(),
                        List.of(item()),
                        NOW,
                        NOW
                ),
                imageUrlResolver
        );

        assertThat(response.paymentMode()).isEqualTo("MOCK");
        assertThat(response.subtotalAmount())
                .isEqualByComparingTo("199000.00");
        assertThat(response.shippingFee())
                .isEqualByComparingTo("30000.00");
        assertThat(response.totalAmount())
                .isEqualByComparingTo("229000.00");
        assertThat(response.fulfillment())
                .isEqualTo(new OrderFulfillmentResponse(
                        "Nguyen Van A",
                        "+84901234567",
                        "123 Nguyen Trai, Ho Chi Minh City",
                        "STANDARD"
                ));
        assertThat(response.items().get(0).imageUrl())
                .isEqualTo(
                        "https://shop.example.com/api/v1/orders/"
                                + ORDER_ID
                                + "/items/"
                                + ITEM_ID
                                + "/image"
                );
    }

    @Test
    void shouldExposeAdminSnapshotAndKeepLegacyFulfillmentNullable() {
        AdminOrderDetailResponse adminResponse =
                AdminOrderDetailResponse.from(
                        new AdminOrderDetailResult(
                                ORDER_ID,
                                USER_ID,
                                OrderStatus.CONFIRMED,
                                OrderPaymentMode.COD,
                                new BigDecimal("199000.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("199000.00"),
                                fulfillment(),
                                List.of(item()),
                                NOW,
                                NOW
                        ),
                        imageUrlResolver
                );

        OrderResponse legacyResponse = OrderResponse.from(
                new OrderResult(
                        ORDER_ID,
                        USER_ID,
                        OrderStatus.PAID,
                        OrderPaymentMode.MOCK,
                        new BigDecimal("199000.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("199000.00"),
                        null,
                        List.of(item()),
                        NOW,
                        NOW
                ),
                imageUrlResolver
        );

        assertThat(adminResponse.paymentMode())
                .isEqualTo("COD");
        assertThat(adminResponse.fulfillment().shippingMethod())
                .isEqualTo("STANDARD");
        assertThat(adminResponse.items().get(0).imageUrl())
                .isEqualTo(
                        "https://shop.example.com/api/v1/admin/orders/"
                                + ORDER_ID
                                + "/items/"
                                + ITEM_ID
                                + "/image"
                );
        assertThat(legacyResponse.fulfillment()).isNull();
    }

    private OrderFulfillmentResult fulfillment() {
        return new OrderFulfillmentResult(
                "Nguyen Van A",
                "+84901234567",
                "123 Nguyen Trai, Ho Chi Minh City",
                ShippingMethod.STANDARD
        );
    }

    private OrderItemResult item() {
        return new OrderItemResult(
                ITEM_ID,
                PRODUCT_ID,
                "SKU-001",
                "Snapshot Product",
                "products/snapshot-product.jpg",
                new BigDecimal("199000.00"),
                1,
                new BigDecimal("199000.00")
        );
    }
}
