package com.lul.shop.ordering.infrastructure.persistence.repository;

import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderIdempotencyRecord;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
import com.lul.shop.ordering.domain.OrderItem;
import com.lul.shop.ordering.domain.OrderRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIdempotencyRepositoryImplTest
        extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "91111111-1111-4111-8111-111111111111"
    );

    private static final UUID OTHER_USER_ID = UUID.fromString(
            "92222222-2222-4222-8222-222222222222"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "93333333-3333-4333-8333-333333333333"
    );

    private static final Instant NOW =
            Instant.parse("2026-07-21T03:00:00Z");

    private static final String KEY =
            "checkout-request-001";

    private static final String FINGERPRINT =
            "a".repeat(64);

    @Autowired
    private OrderIdempotencyRepository repository;

    @Autowired
    private OrderRepository orderRepository;

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
                "idempotency-owner@example.com"
        );
        insertUser(
                OTHER_USER_ID,
                "idempotency-other@example.com"
        );
        insertProduct();
    }

    @AfterEach
    void tearDown() {
        cleanDatabaseRows();
    }

    @Test
    void shouldRequireAnExistingTransaction() {
        OrderIdempotencyRecord claim =
                processingClaim(USER_ID);

        assertThatThrownBy(() ->
                repository.insertIfAbsent(claim)
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );

        assertThatThrownBy(() ->
                repository.findByUserIdAndKey(
                        USER_ID,
                        KEY
                )
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
        OrderIdempotencyRecord firstClaim =
                processingClaim(USER_ID);

        Boolean firstInserted = transactions.execute(
                status -> repository.insertIfAbsent(
                        firstClaim
                )
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
                from order_idempotency_records
                where idempotency_key = ?
                """,
                Integer.class,
                KEY
        );

        assertThat(rowCount).isEqualTo(2);
    }

    @Test
    void shouldFlushPendingOrderBeforeCompletingClaim() {
        OrderIdempotencyRecord claim =
                processingClaim(USER_ID);

        UUID orderId = transactions.execute(status -> {
            assertThat(
                    repository.insertIfAbsent(claim)
            ).isTrue();

            Order order = Order.create(
                    USER_ID,
                    List.of(OrderItem.create(
                            PRODUCT_ID,
                            "IDEMPOTENCY-SKU-001",
                            "Idempotency Product",
                            null,
                            new BigDecimal("100000.00"),
                            1
                    )),
                    NOW
            );

            Order saved = orderRepository.save(order);

            assertThat(repository.complete(
                    claim.id(),
                    saved.getId(),
                    NOW.plusSeconds(1)
            )).isTrue();

            return saved.getId();
        });

        OrderIdempotencyRecord completed =
                transactions.execute(status ->
                        repository
                                .findByUserIdAndKey(
                                        USER_ID,
                                        KEY
                                )
                                .orElseThrow()
                );

        assertThat(completed).isNotNull();
        assertThat(completed.status()).isEqualTo(
                OrderIdempotencyRecord.Status.COMPLETED
        );
        assertThat(completed.orderId()).isEqualTo(orderId);
        assertThat(completed.updatedAt())
                .isEqualTo(NOW.plusSeconds(1));

        Boolean secondCompletion = transactions.execute(
                status -> repository.complete(
                        claim.id(),
                        orderId,
                        NOW.plusSeconds(2)
                )
        );

        assertThat(secondCompletion).isFalse();
    }

    @Test
    void shouldRollbackClaimWithItsTransaction() {
        OrderIdempotencyRecord claim =
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
                from order_idempotency_records
                where id = ?
                """,
                Integer.class,
                claim.id()
        );

        assertThat(rowCount).isZero();
    }

    private OrderIdempotencyRecord processingClaim(
            UUID userId
    ) {
        return OrderIdempotencyRecord.processing(
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
                "Idempotency Test User",
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
                "IDEMPOTENCY-SKU-001",
                "Idempotency Product",
                "Product used by idempotency integration test",
                new BigDecimal("100000.00"),
                10
        );
    }

    private void cleanDatabaseRows() {
        jdbcTemplate.update(
                """
                delete from order_idempotency_records
                where user_id in (?, ?)
                """,
                USER_ID,
                OTHER_USER_ID
        );

        jdbcTemplate.update(
                """
                delete from orders
                where user_id in (?, ?)
                """,
                USER_ID,
                OTHER_USER_ID
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