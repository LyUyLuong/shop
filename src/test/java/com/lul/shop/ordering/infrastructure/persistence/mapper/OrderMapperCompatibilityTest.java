package com.lul.shop.ordering.infrastructure.persistence.mapper;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderAmounts;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderItemJpaEntity;
import com.lul.shop.ordering.infrastructure.persistence.entity.OrderJpaEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.lul.shop.ordering.support.OrderingTestFixtures.fulfillment;
import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperCompatibilityTest {

    private static final UUID ORDER_ID = UUID.fromString(
            "91111111-1111-4111-8111-111111111111"
    );

    private static final UUID USER_ID = UUID.fromString(
            "92222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "93333333-3333-4333-8333-333333333333"
    );

    private static final UUID ITEM_ID = UUID.fromString(
            "94444444-4444-4444-8444-444444444444"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-23T01:00:00Z");

    private final OrderMapper mapper = new OrderMapperImpl();

    @Test
    void shouldRoundTripCompleteCodSnapshot() {
        Order original = Order.create(
                USER_ID,
                List.of(domainItem()),
                fulfillment(),
                OrderPaymentMode.COD,
                OrderAmounts.calculate(
                        new BigDecimal("199000.00"),
                        new BigDecimal("30000.00")
                ),
                NOW
        );

        OrderJpaEntity entity = mapper.toEntity(original);
        Order restored = mapper.toDomain(entity);

        assertThat(entity.getRecipientName())
                .isEqualTo(fulfillment().recipientName());
        assertThat(entity.getRecipientPhone())
                .isEqualTo(fulfillment().recipientPhone());
        assertThat(entity.getShippingAddress())
                .isEqualTo(fulfillment().shippingAddress());
        assertThat(entity.getShippingMethod())
                .isEqualTo(fulfillment().shippingMethod());
        assertThat(entity.getPaymentMode())
                .isEqualTo(OrderPaymentMode.COD);
        assertThat(entity.getSubtotalAmount())
                .isEqualByComparingTo("199000.00");
        assertThat(entity.getShippingFee())
                .isEqualByComparingTo("30000.00");
        assertThat(entity.getTotalAmount())
                .isEqualByComparingTo("229000.00");
        assertThat(entity.getExpiresAt()).isNull();

        assertThat(restored.getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(restored.getPaymentMode())
                .isEqualTo(OrderPaymentMode.COD);
        assertThat(restored.getFulfillment())
                .isEqualTo(fulfillment());
        assertThat(restored.getAmounts())
                .isEqualTo(original.getAmounts());
        assertThat(restored.getItems()).hasSize(1);
    }

    @Test
    void shouldRoundTripCompleteMockSnapshot() {
        Order original = Order.create(
                USER_ID,
                List.of(domainItem()),
                fulfillment(),
                OrderPaymentMode.MOCK,
                OrderAmounts.calculate(
                        new BigDecimal("199000.00"),
                        BigDecimal.ZERO
                ),
                NOW
        );

        Order restored = mapper.toDomain(
                mapper.toEntity(original)
        );

        assertThat(restored.getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(restored.getPaymentMode())
                .isEqualTo(OrderPaymentMode.MOCK);
        assertThat(restored.getExpiresAt())
                .isEqualTo(NOW.plusSeconds(30 * 60));
        assertThat(restored.getFulfillment())
                .isEqualTo(fulfillment());
    }

    @Test
    void shouldNormalizeRollbackShapeWithoutNewColumns() {
        OrderJpaEntity legacy = new OrderJpaEntity();
        legacy.setId(ORDER_ID);
        legacy.setUserId(USER_ID);
        legacy.setStatus(OrderStatus.PAID);
        legacy.setTotalAmount(
                new BigDecimal("199000.00")
        );
        legacy.setExpiresAt(
                NOW.plusSeconds(30 * 60)
        );
        legacy.setCreatedAt(NOW);
        legacy.setUpdatedAt(NOW);
        legacy.attachItem(legacyItem());

        Order restored = mapper.toDomain(legacy);

        assertThat(restored.getId()).isEqualTo(ORDER_ID);
        assertThat(restored.getPaymentMode())
                .isEqualTo(OrderPaymentMode.MOCK);
        assertThat(restored.getSubtotalAmount())
                .isEqualByComparingTo("199000.00");
        assertThat(restored.getShippingFee())
                .isEqualByComparingTo("0.00");
        assertThat(restored.getTotalAmount())
                .isEqualByComparingTo("199000.00");
        assertThat(restored.getFulfillment()).isNull();
    }

    private OrderItem domainItem() {
        return OrderItem.create(
                PRODUCT_ID,
                "MAPPER-SKU-001",
                "Mapper Product",
                "products/mapper/product.jpg",
                new BigDecimal("199000.00"),
                1
        );
    }

    private OrderItemJpaEntity legacyItem() {
        OrderItemJpaEntity item = new OrderItemJpaEntity();
        item.setId(ITEM_ID);
        item.setProductId(PRODUCT_ID);
        item.setProductSku("LEGACY-SKU-001");
        item.setProductName("Legacy Product");
        item.setUnitPrice(new BigDecimal("199000.00"));
        item.setQuantity(1);
        item.setLineTotal(new BigDecimal("199000.00"));
        item.setCreatedAt(NOW);
        item.setUpdatedAt(NOW);
        return item;
    }
}
