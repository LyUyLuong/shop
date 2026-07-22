package com.lul.shop.payment.infrastructure.persistence.repository;

import com.lul.shop.payment.domain.Payment;
import com.lul.shop.payment.domain.PaymentIdempotencyRecord;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.payment.domain.PaymentRepository;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIdempotencyRepositoryImplTest
        extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "a1111111-1111-4111-8111-111111111111"
    );

    private static final UUID OTHER_USER_ID = UUID.fromString(
            "a2222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "a3333333-3333-4333-8333-333333333333"
    );

    private static final UUID ORDER_ID = UUID.fromString(
            "a4444444-4444-4444-8444-444444444444"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-22T03:00:00Z");

    private static final String KEY =
            "payment-request-001";

    private static final String FINGERPRINT =
            "a".repeat(64);

    @Autowired
    private PaymentIdempotencyRepository repository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions =
                new TransactionTemplate(transactionManager);

        cleanDatabaseRows();

        insertUser(
                USER_ID,
                "payment-idempotency-owner@example.com"
        );
        insertUser(
                OTHER_USER_ID,
                "payment-idempotency-other@example.com"
        );
        insertProduct();
        insertOrder();
    }

    @AfterEach
    void tearDown() {
        cleanDatabaseRows();
    }

    @Test
    void shouldRequireAnExistingTransaction() {
        PaymentIdempotencyRecord claim =
                processingClaim(USER_ID);

        assertThatThrownBy(() ->
                repository.insertIfAbsent(claim)
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );

        assertThatThrownBy(() ->
                repository.findByUserIdAndKey(USER_ID, KEY)
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );

        assertThatThrownBy(() ->
                repository.complete(
                        claim.id(),
                        UUID.randomUUID(),
                        NOW
                )
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );
    }

    @Test
    void shouldClaimKeyOncePerUser() {
        PaymentIdempotencyRecord firstClaim =
                processingClaim(USER_ID);

        Boolean firstInserted = transactions.execute(
                status -> repository.insertIfAbsent(firstClaim)
        );

        Boolean duplicateInserted = transactions.execute(
                status -> repository.insertIfAbsent(
                        processingClaim(USER_ID)
                )
        );

        Boolean otherUserInserted = transactions.execute(
                status -> repository.insertIfAbsent(
                        processingClaim(OTHER_USER_ID)
                )
        );

        assertThat(firstInserted).isTrue();
        assertThat(duplicateInserted).isFalse();
        assertThat(otherUserInserted).isTrue();

        Integer rowCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from payment_idempotency_records
                where idempotency_key = ?
                """,
                Integer.class,
                KEY
        );

        assertThat(rowCount).isEqualTo(2);
    }

    @Test
    void shouldFlushPendingPaymentBeforeCompletingClaim() {
        Instant completedAt = NOW.plusSeconds(1);

        PaymentIdempotencyRecord claim =
                processingClaim(USER_ID);

        UUID paymentId = transactions.execute(status -> {
            assertThat(
                    repository.insertIfAbsent(claim)
            ).isTrue();

            Payment payment = Payment.createSucceededMock(
                    ORDER_ID,
                    USER_ID,
                    new BigDecimal("100000.00"),
                    NOW
            );

            Payment saved = paymentRepository.save(payment);

            assertThat(repository.complete(
                    claim.id(),
                    saved.getId(),
                    completedAt
            )).isTrue();

            return saved.getId();
        });

        PaymentIdempotencyRecord completed =
                transactions.execute(status ->
                        repository
                                .findByUserIdAndKey(USER_ID, KEY)
                                .orElseThrow()
                );

        assertThat(completed).isNotNull();
        assertThat(completed.status()).isEqualTo(
                PaymentIdempotencyRecord.Status.COMPLETED
        );
        assertThat(completed.paymentId()).isEqualTo(paymentId);
        assertThat(completed.updatedAt()).isEqualTo(completedAt);

        Boolean secondCompletion = transactions.execute(
                status -> repository.complete(
                        claim.id(),
                        paymentId,
                        completedAt.plusSeconds(1)
                )
        );

        assertThat(secondCompletion).isFalse();
    }

    @Test
    void shouldRollbackClaimWithItsTransaction() {
        PaymentIdempotencyRecord claim =
                processingClaim(USER_ID);

        transactions.execute(status -> {
            assertThat(
                    repository.insertIfAbsent(claim)
            ).isTrue();

            status.setRollbackOnly();
            return null;
        });

        Integer rowCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from payment_idempotency_records
                where id = ?
                """,
                Integer.class,
                claim.id()
        );

        assertThat(rowCount).isZero();
    }

    private PaymentIdempotencyRecord processingClaim(
            UUID userId
    ) {
        return PaymentIdempotencyRecord.processing(
                userId,
                KEY,
                FINGERPRINT,
                NOW
        );
    }

    private void insertUser(UUID userId, String email) {
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
                "Payment Idempotency Test User",
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
                    ?, ?, ?, ?, ?, ?,
                    'ACTIVE',
                    null,
                    null,
                    now(),
                    now()
                )
                """,
                PRODUCT_ID,
                "PAYMENT-IDEMPOTENCY-SKU-001",
                "Payment Idempotency Product",
                "Product used by payment idempotency test",
                new BigDecimal("100000.00"),
                10
        );
    }

    private void insertOrder() {
        jdbcTemplate.update(
                """
                insert into orders (
                    id,
                    user_id,
                    status,
                    total_amount,
                    created_at,
                    updated_at
                )
                values (?, ?, 'PENDING_PAYMENT', ?, now(), now())
                """,
                ORDER_ID,
                USER_ID,
                new BigDecimal("100000.00")
        );

        jdbcTemplate.update(
                """
                insert into order_items (
                    id,
                    order_id,
                    product_id,
                    product_sku,
                    product_name,
                    unit_price,
                    quantity,
                    line_total,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """,
                UUID.randomUUID(),
                ORDER_ID,
                PRODUCT_ID,
                "PAYMENT-IDEMPOTENCY-SKU-001",
                "Payment Idempotency Product",
                new BigDecimal("100000.00"),
                1,
                new BigDecimal("100000.00")
        );
    }

    private void cleanDatabaseRows() {
        jdbcTemplate.update(
                """
                delete from payment_idempotency_records
                where user_id in (?, ?)
                """,
                USER_ID,
                OTHER_USER_ID
        );

        jdbcTemplate.update(
                """
                delete from order_idempotency_records
                where user_id in (?, ?)
                """,
                USER_ID,
                OTHER_USER_ID
        );

        jdbcTemplate.update(
                "delete from outbox_events where aggregate_id = ?",
                ORDER_ID
        );

        jdbcTemplate.update(
                "delete from payments where order_id = ?",
                ORDER_ID
        );

        jdbcTemplate.update(
                "delete from order_status_history where order_id = ?",
                ORDER_ID
        );

        jdbcTemplate.update(
                "delete from order_items where order_id = ?",
                ORDER_ID
        );

        jdbcTemplate.update(
                "delete from orders where id = ?",
                ORDER_ID
        );

        jdbcTemplate.update(
                "delete from products where id = ?",
                PRODUCT_ID
        );

        jdbcTemplate.update(
                "delete from users where id in (?, ?)",
                USER_ID,
                OTHER_USER_ID
        );
    }
}
