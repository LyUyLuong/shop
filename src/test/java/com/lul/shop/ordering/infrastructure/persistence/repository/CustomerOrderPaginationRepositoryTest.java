package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.CustomerOrderSummary;
import com.lul.shop.ordering.domain.OrderPaymentMode;
import com.lul.shop.ordering.domain.OrderRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class CustomerOrderPaginationRepositoryTest
        extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID OTHER_USER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    private static final UUID LOW_ORDER_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
    );

    private static final UUID MIDDLE_ORDER_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"
    );

    private static final UUID HIGH_ORDER_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-000000000003"
    );

    private static final UUID OTHER_ORDER_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-000000000004"
    );

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-23T08:00:00Z");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void shouldReadOwnedStablePageWithThreeQueriesWithoutAggregateLoads() {
        insertUser(
                USER_ID,
                "pagination-owner@example.com"
        );

        insertUser(
                OTHER_USER_ID,
                "pagination-other@example.com"
        );

        insertProduct();

        insertOrder(
                LOW_ORDER_ID,
                USER_ID,
                OrderStatus.PENDING_PAYMENT,
                OrderPaymentMode.MOCK,
                "100000.00",
                "10000.00"
        );

        insertOrder(
                MIDDLE_ORDER_ID,
                USER_ID,
                OrderStatus.CONFIRMED,
                OrderPaymentMode.COD,
                "200000.00",
                "20000.00"
        );

        // Rollback-compatible legacy row created without V12 fields.
        insertOrder(
                HIGH_ORDER_ID,
                USER_ID,
                OrderStatus.PAID,
                null,
                "300000.00",
                null
        );

        insertOrder(
                OTHER_ORDER_ID,
                OTHER_USER_ID,
                OrderStatus.PAID,
                null,
                "400000.00",
                null
        );

        insertOrderItem(HIGH_ORDER_ID, "HIGH-ITEM-1");
        insertOrderItem(HIGH_ORDER_ID, "HIGH-ITEM-2");
        insertOrderItem(MIDDLE_ORDER_ID, "MIDDLE-ITEM");
        insertOrderItem(LOW_ORDER_ID, "LOW-ITEM");
        insertOrderItem(OTHER_ORDER_ID, "OTHER-ITEM");

        entityManager.clear();

        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();

        boolean previouslyEnabled =
                statistics.isStatisticsEnabled();

        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();

            PageResult<CustomerOrderSummary> result =
                    orderRepository
                            .findCustomerSummariesByUserId(
                                    USER_ID,
                                    new PageQuery(0, 2)
                            );

            assertThat(result.content())
                    .extracting(CustomerOrderSummary::id)
                    .containsExactly(
                            HIGH_ORDER_ID,
                            MIDDLE_ORDER_ID
                    );

            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(2);
            assertThat(result.hasNext()).isTrue();

            CustomerOrderSummary legacy =
                    result.content().get(0);

            assertThat(legacy.status())
                    .isEqualTo(OrderStatus.PAID);
            assertThat(legacy.paymentMode())
                    .isEqualTo(OrderPaymentMode.MOCK);
            assertThat(legacy.totalAmount())
                    .isEqualByComparingTo("300000.00");
            assertThat(legacy.itemCount()).isEqualTo(2);

            CustomerOrderSummary cod =
                    result.content().get(1);

            assertThat(cod.status())
                    .isEqualTo(OrderStatus.CONFIRMED);
            assertThat(cod.paymentMode())
                    .isEqualTo(OrderPaymentMode.COD);
            assertThat(cod.totalAmount())
                    .isEqualByComparingTo("200000.00");
            assertThat(cod.itemCount()).isEqualTo(1);

            assertThat(statistics.getPrepareStatementCount())
                    .isEqualTo(3L);

            assertThat(statistics.getEntityLoadCount())
                    .isZero();

            assertThat(statistics.getCollectionLoadCount())
                    .isZero();
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(
                    previouslyEnabled
            );
        }
    }

    private void insertUser(
            UUID userId,
            String email
    ) {
        jdbcTemplate.update(
                """
                insert into users (
                    id,
                    email,
                    name,
                    password_hash,
                    enabled,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                email,
                "Pagination User",
                "password-hash"
        );
    }

    private void insertProduct() {
        jdbcTemplate.update(
                """
                insert into products (
                    id,
                    sku,
                    name,
                    description,
                    price,
                    stock_quantity,
                    status,
                    image_key,
                    image_url,
                    created_at,
                    updated_at
                )
                values (
                    ?,
                    'PAGE-SKU-001',
                    'Pagination Product',
                    'Product used by pagination test',
                    100000.00,
                    100,
                    'ACTIVE',
                    null,
                    null,
                    now(),
                    now()
                )
                """,
                PRODUCT_ID
        );
    }

    private void insertOrder(
            UUID orderId,
            UUID userId,
            OrderStatus status,
            OrderPaymentMode paymentMode,
            String totalAmountValue,
            String shippingFeeValue
    ) {
        BigDecimal totalAmount =
                new BigDecimal(totalAmountValue);

        BigDecimal shippingFee = paymentMode == null
                ? null
                : new BigDecimal(shippingFeeValue);

        BigDecimal subtotalAmount = paymentMode == null
                ? null
                : totalAmount.subtract(shippingFee);

        Timestamp expiresAt =
                paymentMode == OrderPaymentMode.MOCK
                        ? Timestamp.from(
                        CREATED_AT.plusSeconds(1800)
                )
                        : null;

        boolean legacy = paymentMode == null;

        jdbcTemplate.update(
                """
                insert into orders (
                    id,
                    user_id,
                    status,
                    total_amount,
                    expires_at,
                    inventory_released_at,
                    created_at,
                    updated_at,
                    recipient_name,
                    recipient_phone,
                    shipping_address,
                    shipping_method,
                    payment_mode,
                    subtotal_amount,
                    shipping_fee
                )
                values (
                    ?, ?, ?, ?, ?, null, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?
                )
                """,
                orderId,
                userId,
                status.name(),
                totalAmount,
                expiresAt,
                Timestamp.from(CREATED_AT),
                Timestamp.from(
                        CREATED_AT.plusSeconds(60)
                ),
                legacy ? null : "Nguyen Van A",
                legacy ? null : "+84901234567",
                legacy
                        ? null
                        : "123 Nguyen Trai, Ho Chi Minh City",
                legacy ? null : "STANDARD",
                legacy ? null : paymentMode.name(),
                subtotalAmount,
                shippingFee
        );
    }

    private void insertOrderItem(
            UUID orderId,
            String sku
    ) {
        jdbcTemplate.update(
                """
                insert into order_items (
                    id,
                    order_id,
                    product_id,
                    product_sku,
                    product_name,
                    product_image_key,
                    unit_price,
                    quantity,
                    line_total,
                    created_at,
                    updated_at
                )
                values (
                    ?, ?, ?, ?, ?,
                    null,
                    100000.00,
                    1,
                    100000.00,
                    ?,
                    ?
                )
                """,
                UUID.randomUUID(),
                orderId,
                PRODUCT_ID,
                sku,
                "Pagination Product",
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT)
        );
    }
}