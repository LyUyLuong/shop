package com.lul.shop.cart.infrastructure.persistence.repository;

import com.lul.shop.cart.domain.Cart;
import com.lul.shop.cart.domain.CartRepository;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CartRepositoryImplTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "91111111-1111-4111-8111-111111111111"
    );
    private static final UUID PRODUCT_ID = UUID.fromString(
            "92222222-2222-4222-8222-222222222222"
    );

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void insertFixtures() {
        jdbc.update(
                """
                insert into users (
                    id, email, name, password_hash, enabled,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                USER_ID,
                "cart-version@example.com",
                "Cart Version User",
                "password-hash"
        );

        jdbc.update(
                """
                insert into products (
                    id, sku, name, price, stock_quantity, status,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, 'ACTIVE', now(), now())
                """,
                PRODUCT_ID,
                "CART-VERSION-PRODUCT",
                "Cart Version Product",
                100000,
                20
        );
    }

    @Test
    void shouldIncrementVersionWhenItemQuantityChanges() {
        Cart created = saveCartWithItem(1);

        assertThat(created.getVersion()).isZero();

        clearPersistenceContext();

        Cart loaded = cartRepository.findByUserId(USER_ID)
                .orElseThrow();

        UUID itemId = loaded.getItems().get(0).getId();
        loaded.updateItemQuantity(itemId, 2);

        Cart updated = cartRepository.save(loaded);

        assertThat(updated.getVersion()).isEqualTo(1L);

        clearPersistenceContext();

        Cart reloaded = cartRepository.findByUserId(USER_ID)
                .orElseThrow();

        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getItems().get(0).getQuantity())
                .isEqualTo(2);
    }

    @Test
    void shouldRejectStaleCartAggregate() {
        saveCartWithItem(1);
        clearPersistenceContext();

        Cart firstSnapshot = cartRepository.findByUserId(USER_ID)
                .orElseThrow();

        clearPersistenceContext();

        Cart staleSnapshot = cartRepository.findByUserId(USER_ID)
                .orElseThrow();

        clearPersistenceContext();

        UUID itemId = firstSnapshot.getItems().get(0).getId();

        firstSnapshot.updateItemQuantity(itemId, 2);

        Cart accepted = cartRepository.save(firstSnapshot);

        assertThat(accepted.getVersion()).isEqualTo(1L);

        clearPersistenceContext();

        staleSnapshot.updateItemQuantity(itemId, 3);

        assertThatThrownBy(
                () -> cartRepository.save(staleSnapshot)
        ).isInstanceOf(OptimisticLockingFailureException.class);
    }

    private Cart saveCartWithItem(int quantity) {
        Cart cart = Cart.create(USER_ID);
        cart.addItem(PRODUCT_ID, quantity);

        return cartRepository.save(cart);
    }

    private void clearPersistenceContext() {
        entityManager.clear();
    }
}
