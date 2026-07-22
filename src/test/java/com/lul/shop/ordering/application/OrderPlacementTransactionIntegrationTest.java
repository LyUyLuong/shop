package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.application.dto.PlaceOrderCommand;
import com.lul.shop.ordering.domain.OrderIdempotencyRepository;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.AopTestUtils;

class OrderPlacementTransactionIntegrationTest
        extends PostgresIntegrationTest {

    private static final long CART_VERSION = 0L;
    private static final int FIRST_INITIAL_STOCK = 10;
    private static final BigDecimal FIRST_PRICE =
            new BigDecimal("100.00");
    private static final BigDecimal SECOND_PRICE =
            new BigDecimal("250.00");

    @Autowired
    private OrderingService orderingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private OrderIdempotencyRepository idempotencyRepository;

    private OrderIdempotencyRepository idempotencyRepositorySpy;

    @BeforeEach
    void unwrapRepositorySpy() {
        idempotencyRepositorySpy =
                AopTestUtils.getUltimateTargetObject(
                        idempotencyRepository
                );
    }

    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "delete from order_idempotency_records where user_id = ?",
                    fixture.userId()
            );
            jdbcTemplate.update(
                    """
                    delete from order_items
                    where order_id in (
                        select id from orders where user_id = ?
                    )
                    """,
                    fixture.userId()
            );
            jdbcTemplate.update(
                    "delete from orders where user_id = ?",
                    fixture.userId()
            );
            jdbcTemplate.update(
                    "delete from carts where id = ?",
                    fixture.cartId()
            );
            jdbcTemplate.update(
                    "delete from products where id in (?, ?)",
                    fixture.firstProductId(),
                    fixture.secondProductId()
            );
            jdbcTemplate.update(
                    "delete from users where id = ?",
                    fixture.userId()
            );
        }
    }

    @Test
    void shouldCommitCheckoutAndIdempotencyCompletionAtomically() {
        Fixture fixture = seedFixture(10, 2, 3);

        OrderResult result = orderingService.placeOrder(
                command(fixture)
        );

        assertThat(result.status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.totalAmount())
                .isEqualByComparingTo("950.00");
        assertThat(result.items())
                .extracting(item -> item.productId())
                .containsExactly(
                        fixture.firstProductId(),
                        fixture.secondProductId()
                );

        assertThat(orderCount(fixture.userId())).isEqualTo(1);
        assertThat(orderItemCount(result.id())).isEqualTo(2);
        assertThat(loadIdempotency(fixture))
                .isEqualTo(new IdempotencyState(
                        "COMPLETED",
                        result.id()
                ));
        assertCartState(fixture, 1L, 0);
        assertProductState(
                fixture.firstProductId(),
                8,
                1L
        );
        assertProductState(
                fixture.secondProductId(),
                7,
                1L
        );
    }

    @Test
    void shouldRollbackAfterAStockUpdateWhenLaterStockIsInsufficient() {
        Fixture fixture = seedFixture(1, 2, 2);

        assertThatThrownBy(() ->
                orderingService.placeOrder(command(fixture))
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                OrderingErrorCode.INSUFFICIENT_STOCK
                        )
        );

        assertRolledBack(fixture);
    }

    @Test
    void shouldRollbackFlushedWorkWhenIdempotencyCompletionFails() {
        Fixture fixture = seedFixture(10, 2, 1);

        doAnswer(invocation -> {
            entityManager.flush();
            return false;
        }).when(idempotencyRepositorySpy).complete(
                any(UUID.class),
                any(UUID.class),
                any(Instant.class)
        );

        assertThatThrownBy(() ->
                orderingService.placeOrder(command(fixture))
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                OrderingErrorCode
                                        .ORDER_IDEMPOTENCY_STATE_INVALID
                        )
        );

        assertRolledBack(fixture);
    }

    private Fixture seedFixture(
            int secondInitialStock,
            int firstQuantity,
            int secondQuantity
    ) {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new UUID(
                        0x1000000000000000L,
                        UUID.randomUUID().getLeastSignificantBits()
                ),
                new UUID(
                        0x2000000000000000L,
                        UUID.randomUUID().getLeastSignificantBits()
                ),
                UUID.randomUUID(),
                UUID.randomUUID(),
                firstQuantity,
                secondQuantity,
                secondInitialStock,
                "order-" + UUID.randomUUID()
        );

        fixtures.add(fixture);
        insertUser(fixture);
        insertProduct(
                fixture.firstProductId(),
                FIRST_PRICE,
                FIRST_INITIAL_STOCK,
                "FIRST"
        );
        insertProduct(
                fixture.secondProductId(),
                SECOND_PRICE,
                secondInitialStock,
                "SECOND"
        );
        insertCart(fixture);

        return fixture;
    }

    private void insertUser(Fixture fixture) {
        jdbcTemplate.update(
                """
                insert into users (
                    id, email, name, password_hash,
                    enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                fixture.userId(),
                "order-tx-" + fixture.userId() + "@example.com",
                "Order Transaction Test User",
                "password-hash"
        );
    }

    private void insertProduct(
            UUID productId,
            BigDecimal price,
            int stock,
            String label
    ) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, version, sku, name, description,
                    price, stock_quantity, status,
                    created_at, updated_at
                )
                values (?, 0, ?, ?, ?, ?, ?, 'ACTIVE', now(), now())
                """,
                productId,
                "ORDER-TX-" + label + "-" + productId,
                "Order Transaction " + label,
                "Product used by order transaction integration test",
                price,
                stock
        );
    }

    private void insertCart(Fixture fixture) {
        jdbcTemplate.update(
                """
                insert into carts (
                    id, user_id, version, created_at, updated_at
                )
                values (?, ?, ?, now(), now())
                """,
                fixture.cartId(),
                fixture.userId(),
                CART_VERSION
        );

        insertCartItem(
                fixture.firstItemId(),
                fixture.cartId(),
                fixture.firstProductId(),
                fixture.firstQuantity()
        );
        insertCartItem(
                fixture.secondItemId(),
                fixture.cartId(),
                fixture.secondProductId(),
                fixture.secondQuantity()
        );
    }

    private void insertCartItem(
            UUID itemId,
            UUID cartId,
            UUID productId,
            int quantity
    ) {
        jdbcTemplate.update(
                """
                insert into cart_items (
                    id, cart_id, product_id,
                    quantity, created_at, updated_at
                )
                values (?, ?, ?, ?, now(), now())
                """,
                itemId,
                cartId,
                productId,
                quantity
        );
    }

    private PlaceOrderCommand command(Fixture fixture) {
        return new PlaceOrderCommand(
                fixture.userId(),
                fixture.cartId(),
                CART_VERSION,
                fixture.idempotencyKey()
        );
    }

    private void assertRolledBack(Fixture fixture) {
        assertThat(orderCount(fixture.userId())).isZero();
        assertThat(idempotencyCount(fixture)).isZero();
        assertCartState(fixture, CART_VERSION, 2);
        assertProductState(
                fixture.firstProductId(),
                FIRST_INITIAL_STOCK,
                0L
        );
        assertProductState(
                fixture.secondProductId(),
                fixture.secondInitialStock(),
                0L
        );
    }

    private void assertCartState(
            Fixture fixture,
            long expectedVersion,
            int expectedItemCount
    ) {
        Long version = jdbcTemplate.queryForObject(
                "select version from carts where id = ?",
                Long.class,
                fixture.cartId()
        );

        assertThat(Objects.requireNonNull(version))
                .isEqualTo(expectedVersion);
        assertThat(cartItemCount(fixture.cartId()))
                .isEqualTo(expectedItemCount);
    }

    private void assertProductState(
            UUID productId,
            int expectedStock,
            long expectedVersion
    ) {
        ProductState state = jdbcTemplate.queryForObject(
                """
                select stock_quantity, version
                from products
                where id = ?
                """,
                (resultSet, rowNumber) -> new ProductState(
                        resultSet.getInt("stock_quantity"),
                        resultSet.getLong("version")
                ),
                productId
        );

        assertThat(state).isEqualTo(
                new ProductState(
                        expectedStock,
                        expectedVersion
                )
        );
    }

    private IdempotencyState loadIdempotency(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                select status, order_id
                from order_idempotency_records
                where user_id = ? and idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new IdempotencyState(
                        resultSet.getString("status"),
                        resultSet.getObject("order_id", UUID.class)
                ),
                fixture.userId(),
                fixture.idempotencyKey()
        );
    }

    private int orderCount(UUID userId) {
        return count(
                "select count(*) from orders where user_id = ?",
                userId
        );
    }

    private int orderItemCount(UUID orderId) {
        return count(
                "select count(*) from order_items where order_id = ?",
                orderId
        );
    }

    private int cartItemCount(UUID cartId) {
        return count(
                "select count(*) from cart_items where cart_id = ?",
                cartId
        );
    }

    private int idempotencyCount(Fixture fixture) {
        return count(
                """
                select count(*)
                from order_idempotency_records
                where user_id = ? and idempotency_key = ?
                """,
                fixture.userId(),
                fixture.idempotencyKey()
        );
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                arguments
        );

        return Objects.requireNonNull(result);
    }

    private record Fixture(
            UUID userId,
            UUID cartId,
            UUID firstProductId,
            UUID secondProductId,
            UUID firstItemId,
            UUID secondItemId,
            int firstQuantity,
            int secondQuantity,
            int secondInitialStock,
            String idempotencyKey
    ) {
    }

    private record ProductState(
            int stock,
            long version
    ) {
    }

    private record IdempotencyState(
            String status,
            UUID orderId
    ) {
    }
}
