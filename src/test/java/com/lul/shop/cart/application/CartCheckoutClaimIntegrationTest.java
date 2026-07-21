package com.lul.shop.cart.application;

import com.lul.shop.cart.application.dto.CartResult;
import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CartCheckoutClaimIntegrationTest
        extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "b1111111-1111-4111-8111-111111111111"
    );

    private static final UUID OTHER_USER_ID = UUID.fromString(
            "b2222222-2222-4222-8222-222222222222"
    );

    private static final UUID CART_ID = UUID.fromString(
            "b3333333-3333-4333-8333-333333333333"
    );

    private static final UUID PRODUCT_ID = UUID.fromString(
            "b4444444-4444-4444-8444-444444444444"
    );

    private static final UUID ITEM_ID = UUID.fromString(
            "b5555555-5555-4555-8555-555555555555"
    );

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void insertFixtures() {
        insertUser(
                USER_ID,
                "checkout-owner@example.com"
        );
        insertUser(
                OTHER_USER_ID,
                "checkout-other@example.com"
        );

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
                    created_at,
                    updated_at
                )
                values (
                    ?, ?, ?, ?, ?, ?,
                    'ACTIVE',
                    now(),
                    now()
                )
                """,
                PRODUCT_ID,
                "CHECKOUT-LOCK-SKU",
                "Checkout Lock Product",
                "Product used by cart checkout lock test",
                100000,
                20
        );

        jdbcTemplate.update(
                """
                insert into carts (
                    id,
                    user_id,
                    version,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, now(), now())
                """,
                CART_ID,
                USER_ID,
                7L
        );

        jdbcTemplate.update(
                """
                insert into cart_items (
                    id,
                    cart_id,
                    product_id,
                    quantity,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, now(), now())
                """,
                ITEM_ID,
                CART_ID,
                PRODUCT_ID,
                2
        );
    }

    @Test
    void shouldRequireExistingTransactionForLockAndClaim() {
        TransactionTemplate withoutTransaction =
                new TransactionTemplate(transactionManager);

        withoutTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        );

        assertThatThrownBy(() ->
                withoutTransaction.execute(status -> {
                    cartRepository
                            .findByIdAndUserIdForUpdate(
                                    CART_ID,
                                    USER_ID
                            );

                    return null;
                })
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );

        assertThatThrownBy(() ->
                withoutTransaction.execute(status -> {
                    cartService.claimForCheckout(
                            USER_ID,
                            CART_ID,
                            7L
                    );

                    return null;
                })
        ).isInstanceOf(
                IllegalTransactionStateException.class
        );
    }

    @Test
    void shouldLoadOwnedLockedAggregateWithTwoSelects() {
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();

        boolean previouslyEnabled =
                statistics.isStatisticsEnabled();

        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();

            Cart cart = cartRepository
                    .findByIdAndUserIdForUpdate(
                            CART_ID,
                            USER_ID
                    )
                    .orElseThrow();

            long statementCount =
                    statistics.getPrepareStatementCount();

            assertThat(cart.getId()).isEqualTo(CART_ID);
            assertThat(cart.getUserId()).isEqualTo(USER_ID);
            assertThat(cart.getVersion()).isEqualTo(7L);
            assertThat(cart.getItems()).hasSize(1);
            assertThat(cart.getItems().get(0).getProductId())
                    .isEqualTo(PRODUCT_ID);
            assertThat(statementCount).isEqualTo(2L);

            Optional<Cart> otherUserResult =
                    cartRepository
                            .findByIdAndUserIdForUpdate(
                                    CART_ID,
                                    OTHER_USER_ID
                            );

            assertThat(otherUserResult).isEmpty();
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(
                    previouslyEnabled
            );
        }
    }

    @Test
    void shouldReturnPreClearSnapshotAndPersistClear() {
        CartResult snapshot =
                cartService.claimForCheckout(
                        USER_ID,
                        CART_ID,
                        7L
                );

        assertThat(snapshot.id()).isEqualTo(CART_ID);
        assertThat(snapshot.userId()).isEqualTo(USER_ID);
        assertThat(snapshot.version()).isEqualTo(7L);
        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.items().get(0).productId())
                .isEqualTo(PRODUCT_ID);
        assertThat(snapshot.items().get(0).quantity())
                .isEqualTo(2);

        Long persistedVersion = jdbcTemplate.queryForObject(
                """
                select version
                from carts
                where id = ?
                """,
                Long.class,
                CART_ID
        );

        Integer persistedItemCount =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from cart_items
                        where cart_id = ?
                        """,
                        Integer.class,
                        CART_ID
                );

        assertThat(
                Objects.requireNonNull(persistedVersion)
        ).isEqualTo(8L);

        assertThat(
                Objects.requireNonNull(persistedItemCount)
        ).isZero();
    }

    @Test
    void shouldRejectStaleCartVersionWithoutClearingCart() {
        assertCheckoutConflict(() ->
                cartService.claimForCheckout(
                        USER_ID,
                        CART_ID,
                        6L
                )
        );

        assertCartState(7L, 1);
    }

    @Test
    void shouldRejectCartOwnedByAnotherUser() {
        assertCheckoutConflict(() ->
                cartService.claimForCheckout(
                        OTHER_USER_ID,
                        CART_ID,
                        7L
                )
        );
    }

    @Test
    void shouldReturnEmptySnapshotWithoutChangingVersion() {
        jdbcTemplate.update(
                "delete from cart_items where cart_id = ?",
                CART_ID
        );

        CartResult snapshot =
                cartService.claimForCheckout(
                        USER_ID,
                        CART_ID,
                        7L
                );

        assertThat(snapshot.version()).isEqualTo(7L);
        assertThat(snapshot.items()).isEmpty();

        assertCartState(7L, 0);
    }

    private void assertCheckoutConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                CartErrorCode
                                        .CART_CHECKOUT_CONFLICT
                        )
                );
    }

    private void assertCartState(
            long expectedVersion,
            int expectedItemCount
    ) {
        Long version = jdbcTemplate.queryForObject(
                "select version from carts where id = ?",
                Long.class,
                CART_ID
        );

        Integer itemCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from cart_items
                where cart_id = ?
                """,
                Integer.class,
                CART_ID
        );

        assertThat(Objects.requireNonNull(version))
                .isEqualTo(expectedVersion);
        assertThat(Objects.requireNonNull(itemCount))
                .isEqualTo(expectedItemCount);
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
                "Checkout Test User",
                "password-hash"
        );
    }
}