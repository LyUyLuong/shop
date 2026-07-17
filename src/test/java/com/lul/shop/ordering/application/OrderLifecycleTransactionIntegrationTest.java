package com.lul.shop.ordering.application;

import com.lul.shop.ordering.application.dto.ChangeOrderStatusCommand;
import com.lul.shop.ordering.domain.Order;
import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.ordering.domain.OrderStatusHistoryRepository;
import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.payment.application.PaymentService;
import com.lul.shop.payment.application.dto.PayOrderCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class OrderLifecycleTransactionIntegrationTest extends PostgresIntegrationTest {

    private static final int INITIAL_STOCK = 10;
    private static final int ORDER_QUANTITY = 2;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("100000.00");
    private static final BigDecimal ORDER_TOTAL = new BigDecimal("200000.00");

    @Autowired
    private OrderLifecycleService lifecycleService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OrderStatusHistoryRepository historyRepository;

    @MockitoSpyBean
    private OutboxService outboxService;

    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "delete from outbox_events where aggregate_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from payments where order_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from order_status_history where order_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from order_items where order_id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from orders where id = ?",
                    fixture.orderId()
            );
            jdbcTemplate.update(
                    "delete from products where id = ?",
                    fixture.productId()
            );
            jdbcTemplate.update(
                    "delete from users where id in (?, ?)",
                    fixture.ownerId(),
                    fixture.adminId()
            );
        }
    }

    @Test
    void shouldCommitAdminCancellationAtomically() {
        Fixture fixture = seedPendingOrder(false);

        Order result = lifecycleService.changeStatusAsAdmin(
                new ChangeOrderStatusCommand(
                        fixture.orderId(),
                        fixture.adminId(),
                        OrderStatus.CANCELLED,
                        "Customer requested cancellation"
                )
        );

        OrderState state = loadOrderState(fixture.orderId());

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.getInventoryReleasedAt()).isNotNull();
        assertThat(state.status()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(state.inventoryReleasedAt()).isNotNull();
        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK + ORDER_QUANTITY);
        assertThat(historyCount(fixture.orderId())).isEqualTo(1);
        assertThat(historyActor(fixture.orderId()))
                .isEqualTo(OrderStatusChangeActorType.ADMIN.name());
    }

    @Test
    void shouldRollbackAdminCancellationWhenHistoryFails() {
        Fixture fixture = seedPendingOrder(false);
        failHistorySave(
                OrderStatusChangeActorType.ADMIN,
                "forced admin history failure"
        );

        assertThatThrownBy(() -> lifecycleService.changeStatusAsAdmin(
                new ChangeOrderStatusCommand(
                        fixture.orderId(),
                        fixture.adminId(),
                        OrderStatus.CANCELLED,
                        "Customer requested cancellation"
                )
        ))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("forced admin history failure");

        assertUnchangedPendingOrder(fixture);
        assertThat(historyCount(fixture.orderId())).isZero();
    }

    @Test
    void shouldCommitPaymentOrderHistoryAndOutboxAtomically() {
        Fixture fixture = seedPendingOrder(false);

        PaymentResult result = paymentService.payMock(
                new PayOrderCommand(
                        fixture.ownerId(),
                        fixture.orderId()
                )
        );

        OrderState state = loadOrderState(fixture.orderId());

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.amount()).isEqualByComparingTo(ORDER_TOTAL);
        assertThat(state.status()).isEqualTo(OrderStatus.PAID.name());
        assertThat(state.inventoryReleasedAt()).isNull();
        assertThat(loadStock(fixture.productId())).isEqualTo(INITIAL_STOCK);
        assertThat(paymentCount(fixture.orderId())).isEqualTo(1);
        assertThat(historyCount(fixture.orderId())).isEqualTo(1);
        assertThat(historyActor(fixture.orderId()))
                .isEqualTo(OrderStatusChangeActorType.PAYMENT.name());
        assertThat(outboxCount(fixture.orderId())).isEqualTo(1);
    }

    @Test
    void shouldRollbackPaymentOrderAndHistoryWhenOutboxFails() {
        Fixture fixture = seedPendingOrder(false);

        doThrow(new IllegalStateException("forced outbox failure"))
                .when(outboxService)
                .recordOrderPaid(
                        eq(fixture.orderId()),
                        any(UUID.class),
                        eq(fixture.ownerId())
                );

        assertThatThrownBy(() -> paymentService.payMock(
                new PayOrderCommand(
                        fixture.ownerId(),
                        fixture.orderId()
                )
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced outbox failure");

        assertUnchangedPendingOrder(fixture);
        assertThat(paymentCount(fixture.orderId())).isZero();
        assertThat(historyCount(fixture.orderId())).isZero();
        assertThat(outboxCount(fixture.orderId())).isZero();
    }

    @Test
    void shouldCommitSystemExpiryAtomically() {
        Fixture fixture = seedPendingOrder(true);

        Order result = lifecycleService.expireBySystem(
                fixture.orderId()
        );

        OrderState state = loadOrderState(fixture.orderId());

        assertThat(result.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(result.getInventoryReleasedAt()).isNotNull();
        assertThat(state.status()).isEqualTo(OrderStatus.EXPIRED.name());
        assertThat(state.inventoryReleasedAt()).isNotNull();
        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK + ORDER_QUANTITY);
        assertThat(historyCount(fixture.orderId())).isEqualTo(1);
        assertThat(historyActor(fixture.orderId()))
                .isEqualTo(OrderStatusChangeActorType.SYSTEM.name());
    }

    @Test
    void shouldRollbackSystemExpiryWhenHistoryFails() {
        Fixture fixture = seedPendingOrder(true);
        failHistorySave(
                OrderStatusChangeActorType.SYSTEM,
                "forced system history failure"
        );

        assertThatThrownBy(
                () -> lifecycleService.expireBySystem(fixture.orderId())
        )
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("forced system history failure");

        assertUnchangedPendingOrder(fixture);
        assertThat(historyCount(fixture.orderId())).isZero();
    }

    private Fixture seedPendingOrder(boolean expired) {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        fixtures.add(fixture);

        insertUser(
                fixture.ownerId(),
                "lifecycle-owner-" + fixture.ownerId() + "@example.com"
        );
        insertUser(
                fixture.adminId(),
                "lifecycle-admin-" + fixture.adminId() + "@example.com"
        );
        insertProduct(fixture.productId());

        Instant now = Instant.now();
        Instant createdAt = expired
                ? now.minusSeconds(3600)
                : now.minusSeconds(60);
        Instant expiresAt = expired
                ? now.minusSeconds(60)
                : now.plusSeconds(1800);

        insertOrder(
                fixture.orderId(),
                fixture.ownerId(),
                createdAt,
                expiresAt
        );
        insertOrderItem(
                fixture.orderId(),
                fixture.productId(),
                createdAt
        );

        return fixture;
    }

    private void insertUser(UUID userId, String email) {
        jdbcTemplate.update(
                """
                insert into users (
                    id, email, name, password_hash,
                    enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, true, now(), now())
                """,
                userId,
                email,
                "Lifecycle Test User",
                "password-hash"
        );
    }

    private void insertProduct(UUID productId) {
        jdbcTemplate.update(
                """
                insert into products (
                    id, sku, name, description, price,
                    stock_quantity, status,
                    image_key, image_url,
                    created_at, updated_at
                )
                values (
                    ?, ?, ?, ?, ?,
                    ?, 'ACTIVE',
                    null, null,
                    now(), now()
                )
                """,
                productId,
                "LIFECYCLE-" + productId,
                "Lifecycle Test Product",
                "Product used by lifecycle transaction tests",
                UNIT_PRICE,
                INITIAL_STOCK
        );
    }

    private void insertOrder(
            UUID orderId,
            UUID ownerId,
            Instant createdAt,
            Instant expiresAt
    ) {
        jdbcTemplate.update(
                """
                insert into orders (
                    id, user_id, status, total_amount,
                    expires_at, inventory_released_at,
                    created_at, updated_at
                )
                values (
                    ?, ?, 'PENDING_PAYMENT', ?,
                    ?, null,
                    ?, ?
                )
                """,
                orderId,
                ownerId,
                ORDER_TOTAL,
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private void insertOrderItem(
            UUID orderId,
            UUID productId,
            Instant createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into order_items (
                    id, order_id, product_id,
                    product_sku, product_name,
                    unit_price, quantity, line_total,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                orderId,
                productId,
                "LIFECYCLE-SNAPSHOT-" + productId,
                "Lifecycle Snapshot Product",
                UNIT_PRICE,
                ORDER_QUANTITY,
                ORDER_TOTAL,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
    }

    private void failHistorySave(
            OrderStatusChangeActorType actorType,
            String message
    ) {
        doThrow(new IllegalStateException(message))
                .when(historyRepository)
                .save(argThat(history ->
                        history != null
                                && history.getActorType() == actorType
                ));
    }

    private void assertUnchangedPendingOrder(Fixture fixture) {
        OrderState state = loadOrderState(fixture.orderId());

        assertThat(state.status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(state.inventoryReleasedAt()).isNull();
        assertThat(loadStock(fixture.productId()))
                .isEqualTo(INITIAL_STOCK);
    }

    private OrderState loadOrderState(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                select status, inventory_released_at
                from orders
                where id = ?
                """,
                (resultSet, rowNumber) -> {
                    Timestamp releasedAt =
                            resultSet.getTimestamp(
                                    "inventory_released_at"
                            );

                    return new OrderState(
                            resultSet.getString("status"),
                            releasedAt == null
                                    ? null
                                    : releasedAt.toInstant()
                    );
                },
                orderId
        );
    }

    private int loadStock(UUID productId) {
        return jdbcTemplate.queryForObject(
                """
                select stock_quantity
                from products
                where id = ?
                """,
                Integer.class,
                productId
        );
    }

    private int historyCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                from order_status_history
                where order_id = ?
                """,
                Integer.class,
                orderId
        );
    }

    private String historyActor(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                select actor_type
                from order_status_history
                where order_id = ?
                """,
                String.class,
                orderId
        );
    }

    private int paymentCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                from payments
                where order_id = ?
                """,
                Integer.class,
                orderId
        );
    }

    private int outboxCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                from outbox_events
                where aggregate_id = ?
                """,
                Integer.class,
                orderId
        );
    }

    private record Fixture(
            UUID ownerId,
            UUID adminId,
            UUID productId,
            UUID orderId
    ) {
    }

    private record OrderState(
            String status,
            Instant inventoryReleasedAt
    ) {
    }
}