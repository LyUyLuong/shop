package com.lul.shop.payment.infrastructure.persistence.repository;

import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class PaymentRepositoryImplTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ORDER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHER_ORDER_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant PAID_AT = Instant.parse("2026-06-30T08:00:00Z");

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindSucceededPaymentById() {
        insertUser(USER_ID, "payment-user@example.com");
        insertProduct(PRODUCT_ID);
        insertOrder(ORDER_ID, USER_ID, "398000.00");

        Payment payment = Payment.createSucceededMock(
                ORDER_ID,
                USER_ID,
                new BigDecimal("398000.00"),
                PAID_AT
        );

        Payment saved = paymentRepository.save(payment);

        flushAndClear();

        Payment found = paymentRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(found.getUserId()).isEqualTo(USER_ID);
        assertThat(found.getMethod()).isEqualTo(PaymentMethod.MOCK);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(found.getAmount()).isEqualByComparingTo("398000.00");
        assertThat(found.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(found.getFailureReason()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldFindPaymentOnlyWhenItBelongsToUser() {
        insertUser(USER_ID, "payment-owner@example.com");
        insertUser(OTHER_USER_ID, "payment-other@example.com");
        insertProduct(PRODUCT_ID);
        insertOrder(ORDER_ID, USER_ID, "199000.00");

        Payment saved = paymentRepository.save(Payment.createSucceededMock(
                ORDER_ID,
                USER_ID,
                new BigDecimal("199000.00"),
                PAID_AT
        ));

        flushAndClear();

        assertThat(paymentRepository.findByIdAndUserId(saved.getId(), USER_ID)).isPresent();
        assertThat(paymentRepository.findByIdAndUserId(saved.getId(), OTHER_USER_ID)).isEmpty();
    }

    @Test
    void shouldFindPaymentByOrderId() {
        insertUser(USER_ID, "payment-order@example.com");
        insertProduct(PRODUCT_ID);
        insertOrder(ORDER_ID, USER_ID, "100000.00");
        insertOrder(OTHER_ORDER_ID, USER_ID, "50000.00");

        Payment saved = paymentRepository.save(Payment.createSucceededMock(
                ORDER_ID,
                USER_ID,
                new BigDecimal("100000.00"),
                PAID_AT
        ));

        flushAndClear();

        Payment found = paymentRepository.findByOrderId(ORDER_ID).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void shouldRejectSecondPaymentForSameOrder() {
        insertUser(USER_ID, "payment-duplicate@example.com");
        insertProduct(PRODUCT_ID);
        insertOrder(ORDER_ID, USER_ID, "100000.00");

        paymentRepository.save(Payment.createSucceededMock(
                ORDER_ID,
                USER_ID,
                new BigDecimal("100000.00"),
                PAID_AT
        ));

        flushAndClear();

        Payment duplicatePayment = Payment.createSucceededMock(
                ORDER_ID,
                USER_ID,
                new BigDecimal("100000.00"),
                PAID_AT.plusSeconds(60)
        );

        paymentRepository.save(duplicatePayment);

        assertThatThrownBy(this::flushAndClear)
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uq_payments_order_id");
    }

    @Test
    void shouldSaveAndFindFailedPayment() {
        insertUser(USER_ID, "payment-failed@example.com");
        insertProduct(PRODUCT_ID);
        insertOrder(ORDER_ID, USER_ID, "100000.00");

        Payment payment = Payment.createPending(
                ORDER_ID,
                USER_ID,
                new BigDecimal("100000.00"),
                PaymentMethod.MOCK
        );
        payment.fail("Mock payment failed");

        Payment saved = paymentRepository.save(payment);

        flushAndClear();

        Payment found = paymentRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(found.getPaidAt()).isNull();
        assertThat(found.getFailureReason()).isEqualTo("Mock payment failed");
    }

    private void insertUser(UUID userId, String email) {
        jdbcTemplate.update(
                """
                insert into users (id, email, name, password_hash, enabled, created_at, updated_at)
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                email,
                "Test User",
                "password-hash"
        );
    }

    private void insertProduct(UUID productId) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, sku, name, description, price, stock_quantity, status,
                    image_key, image_url, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', null, null, now(), now())
                """,
                productId,
                "PAYMENT-SKU-" + productId.toString().substring(0, 8),
                "Payment Test Product",
                "Product used by payment repository integration test",
                new BigDecimal("100000.00"),
                10
        );
    }

    private void insertOrder(UUID orderId, UUID userId, String totalAmount) {
        jdbcTemplate.update(
                """
                insert into orders (id, user_id, status, total_amount, created_at, updated_at)
                values (?, ?, 'PENDING_PAYMENT', ?, now(), now())
                """,
                orderId,
                userId,
                new BigDecimal(totalAmount)
        );

        jdbcTemplate.update(
                """
                insert into order_items (
                    id, order_id, product_id, product_sku, product_name,
                    unit_price, quantity, line_total, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """,
                UUID.randomUUID(),
                orderId,
                PRODUCT_ID,
                "PAYMENT-SNAPSHOT-SKU",
                "Payment Snapshot Product",
                new BigDecimal(totalAmount),
                1,
                new BigDecimal(totalAmount)
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}