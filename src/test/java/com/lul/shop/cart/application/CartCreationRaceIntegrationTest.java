package com.lul.shop.cart.application;

import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CartCreationRaceIntegrationTest
        extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "c1111111-1111-4111-8111-111111111111"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void insertUser() {
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
                USER_ID,
                "cart-creation-race@example.com",
                "Cart Creation Race User",
                "password-hash"
        );
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update(
                "delete from carts where user_id = ?",
                USER_ID
        );
        jdbcTemplate.update(
                "delete from users where id = ?",
                USER_ID
        );
    }

    @Test
    void shouldCreateOneCartForConcurrentFirstReads()
            throws Exception {
        CountDownLatch initialReads =
                new CountDownLatch(2);
        CountDownLatch releaseInitialReads =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<Cart> firstRequest =
                executor.submit(() ->
                        createCartAfterSynchronizedMiss(
                                initialReads,
                                releaseInitialReads
                        )
                );
        Future<Cart> secondRequest =
                executor.submit(() ->
                        createCartAfterSynchronizedMiss(
                                initialReads,
                                releaseInitialReads
                        )
                );

        try {
            await(
                    initialReads,
                    "both initial cart reads"
            );

            releaseInitialReads.countDown();

            Cart firstResult =
                    firstRequest.get(
                            20,
                            TimeUnit.SECONDS
                    );
            Cart secondResult =
                    secondRequest.get(
                            20,
                            TimeUnit.SECONDS
                    );

            assertThat(firstResult.getId())
                    .isEqualTo(secondResult.getId());
            assertThat(firstResult.getVersion()).isZero();
            assertThat(secondResult.getVersion()).isZero();

            List<CartState> persistedCarts =
                    jdbcTemplate.query(
                            """
                            select id, version
                            from carts
                            where user_id = ?
                            """,
                            (resultSet, rowNumber) ->
                                    new CartState(
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class
                                            ),
                                            resultSet.getLong(
                                                    "version"
                                            )
                                    ),
                            USER_ID
                    );

            assertThat(persistedCarts)
                    .containsExactly(
                            new CartState(
                                    firstResult.getId(),
                                    0L
                            )
                    );
        } finally {
            releaseInitialReads.countDown();
            shutdown(executor);
        }
    }

    private Cart createCartAfterSynchronizedMiss(
            CountDownLatch initialReads,
            CountDownLatch releaseInitialReads
    ) {
        TransactionTemplate transactions =
                new TransactionTemplate(transactionManager);

        return transactions.execute(status -> {
            Optional<Cart> initialCart =
                    cartRepository.findByUserId(USER_ID);

            if (initialCart.isPresent()) {
                throw new AssertionError(
                        "Initial cart read must be empty"
                );
            }

            initialReads.countDown();
            awaitWithinTransaction(
                    releaseInitialReads,
                    "release initial cart reads"
            );

            cartRepository.lockCreationByUserId(USER_ID);

            return cartRepository
                    .findByUserId(USER_ID)
                    .orElseGet(() ->
                            cartRepository.save(
                                    Cart.create(USER_ID)
                            )
                    );
        });
    }

    private void awaitWithinTransaction(
            CountDownLatch latch,
            String description
    ) {
        try {
            await(latch, description);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for "
                            + description,
                    exception
            );
        }
    }

    private void await(
            CountDownLatch latch,
            String description
    ) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError(
                    "Timed out waiting for " + description
            );
        }
    }

    private void shutdown(ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();

        if (!executor.awaitTermination(
                10,
                TimeUnit.SECONDS
        )) {
            throw new AssertionError(
                    "Executor did not terminate"
            );
        }
    }

    private record CartState(
            UUID id,
            long version
    ) {
    }
}
