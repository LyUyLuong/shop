package com.lul.shop.payment.application;

import com.lul.shop.ordering.domain.OrderStatus;
import com.lul.shop.ordering.domain.OrderStatusChangeActorType;
import com.lul.shop.outbox.application.OutboxService;
import com.lul.shop.payment.application.dto.CollectCodCommand;
import com.lul.shop.payment.application.dto.PaymentResult;
import com.lul.shop.payment.application.port.PaymentProviderRequest;
import com.lul.shop.payment.application.port.PaymentProviderResult;
import com.lul.shop.payment.domain.PaymentIdempotencyRepository;
import com.lul.shop.payment.domain.PaymentMethod;
import com.lul.shop.payment.domain.PaymentStatus;
import com.lul.shop.payment.infrastructure.provider.CodPaymentProvider;
import com.lul.shop.shared.exception.BusinessException;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

class CodCollectionTransactionIntegrationTest
        extends PostgresIntegrationTest {

    private static final int INITIAL_STOCK = 8;
    private static final int ORDER_QUANTITY = 2;

    private static final BigDecimal UNIT_PRICE =
            new BigDecimal("100000.00");

    private static final BigDecimal SUBTOTAL =
            new BigDecimal("200000.00");

    private static final BigDecimal SHIPPING_FEE =
            new BigDecimal("30000.00");

    private static final BigDecimal TOTAL =
            new BigDecimal("230000.00");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private CodPaymentProvider codPaymentProvider;

    @MockitoSpyBean
    private OutboxService outboxService;

    @MockitoSpyBean
    private PaymentIdempotencyRepository
            paymentIdempotencyRepository;

    private PaymentIdempotencyRepository
            paymentIdempotencyRepositoryTarget;

    private final List<Fixture> fixtures =
            new ArrayList<>();

    @BeforeEach
    void unwrapIdempotencyRepositorySpy() {
        paymentIdempotencyRepositoryTarget =
                AopTestUtils.getUltimateTargetObject(
                        paymentIdempotencyRepository
                );
    }

    @AfterEach
    void cleanDatabase() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update(
                    "delete from outbox_events where aggregate_id = ?",
                    fixture.orderId()
            );

            jdbcTemplate.update(
                    """
                    delete from payment_idempotency_records
                    where user_id in (?, ?)
                    """,
                    fixture.customerId(),
                    fixture.adminId()
            );

            jdbcTemplate.update(
                    "delete from payments where order_id = ?",
                    fixture.orderId()
            );

            jdbcTemplate.update(
                    """
                    delete from order_status_history
                    where order_id = ?
                    """,
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
                    fixture.customerId(),
                    fixture.adminId()
            );
        }
    }

    @Test
    void shouldCommitCodCollectionAtomically() {
        Fixture fixture = seedShippedCodOrder();

        PaymentResult result =
                paymentService.collectCod(command(fixture));

        assertThat(result.orderId())
                .isEqualTo(fixture.orderId());
        assertThat(result.userId())
                .isEqualTo(fixture.customerId());
        assertThat(result.method())
                .isEqualTo(PaymentMethod.COD);
        assertThat(result.status())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.amount())
                .isEqualByComparingTo(TOTAL);
        assertThat(result.paidAt()).isNotNull();

        assertCommittedState(fixture, result);
    }

    @Test
    void shouldRollbackCodCollectionWhenProviderRejects() {
        Fixture fixture = seedShippedCodOrder();

        doReturn(PaymentProviderResult.rejected(
                "forced COD provider rejection"
        )).when(codPaymentProvider).process(
                any(PaymentProviderRequest.class)
        );

        assertThatThrownBy(() ->
                paymentService.collectCod(command(fixture))
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode
                                .PAYMENT_PROVIDER_REJECTED
                )
        );

        assertRolledBackState(fixture);
    }

    @Test
    void shouldRollbackCodCollectionWhenOutboxFails() {
        Fixture fixture = seedShippedCodOrder();

        doThrow(new IllegalStateException(
                "forced COD outbox failure"
        )).when(outboxService).recordOrderPaid(
                eq(fixture.orderId()),
                any(UUID.class),
                eq(fixture.customerId())
        );

        assertThatThrownBy(() ->
                paymentService.collectCod(command(fixture))
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced COD outbox failure");

        assertRolledBackState(fixture);
    }

    @Test
    void shouldRollbackCodCollectionWhenClaimCompletionFails() {
        Fixture fixture = seedShippedCodOrder();

        doAnswer(invocation -> {
            entityManager.flush();
            return false;
        }).when(paymentIdempotencyRepositoryTarget)
                .complete(
                        any(UUID.class),
                        any(UUID.class),
                        any(Instant.class)
                );

        assertThatThrownBy(() ->
                paymentService.collectCod(command(fixture))
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        PaymentErrorCode
                                .PAYMENT_IDEMPOTENCY_STATE_INVALID
                )
        );

        assertRolledBackState(fixture);
    }

    private void assertCommittedState(
            Fixture fixture,
            PaymentResult result
    ) {
        Map<String, Object> order = jdbcTemplate.queryForMap(
                """
                select status, inventory_released_at
                from orders
                where id = ?
                """,
                fixture.orderId()
        );

        assertThat(order.get("status"))
                .isEqualTo(OrderStatus.COMPLETED.name());
        assertThat(order.get("inventory_released_at"))
                .isNull();
        assertThat(loadStock(fixture))
                .isEqualTo(INITIAL_STOCK);

        Map<String, Object> payment =
                jdbcTemplate.queryForMap(
                        """
                        select id, user_id, method,
                               status, amount, paid_at
                        from payments
                        where order_id = ?
                        """,
                        fixture.orderId()
                );

        assertThat(payment.get("id"))
                .isEqualTo(result.id());
        assertThat(payment.get("user_id"))
                .isEqualTo(fixture.customerId());
        assertThat(payment.get("method"))
                .isEqualTo(PaymentMethod.COD.name());
        assertThat(payment.get("status"))
                .isEqualTo(PaymentStatus.SUCCEEDED.name());
        assertThat((BigDecimal) payment.get("amount"))
                .isEqualByComparingTo(TOTAL);
        assertThat(payment.get("paid_at")).isNotNull();

        Map<String, Object> history =
                jdbcTemplate.queryForMap(
                        """
                        select from_status, to_status,
                               actor_type, actor_user_id,
                               reason
                        from order_status_history
                        where order_id = ?
                        """,
                        fixture.orderId()
                );

        assertThat(history.get("from_status"))
                .isEqualTo(OrderStatus.SHIPPED.name());
        assertThat(history.get("to_status"))
                .isEqualTo(OrderStatus.COMPLETED.name());
        assertThat(history.get("actor_type"))
                .isEqualTo(
                        OrderStatusChangeActorType.ADMIN.name()
                );
        assertThat(history.get("actor_user_id"))
                .isEqualTo(fixture.adminId());
        assertThat(history.get("reason"))
                .isEqualTo("Cash on delivery collected");

        Map<String, Object> outbox =
                jdbcTemplate.queryForMap(
                        """
                        select event_type, aggregate_type,
                               aggregate_id, payload
                        from outbox_events
                        where aggregate_id = ?
                        """,
                        fixture.orderId()
                );

        assertThat(outbox.get("event_type"))
                .isEqualTo("ORDER_PAID");
        assertThat(outbox.get("aggregate_type"))
                .isEqualTo("ORDER");
        assertThat(outbox.get("aggregate_id"))
                .isEqualTo(fixture.orderId());

        String payload = (String) outbox.get("payload");

        assertThat(payload)
                .contains(fixture.orderId().toString())
                .contains(result.id().toString())
                .contains(fixture.customerId().toString());

        Map<String, Object> claim =
                jdbcTemplate.queryForMap(
                        """
                        select status, payment_id
                        from payment_idempotency_records
                        where user_id = ?
                          and idempotency_key = ?
                        """,
                        fixture.adminId(),
                        idempotencyKey(fixture)
                );

        assertThat(claim.get("status"))
                .isEqualTo("COMPLETED");
        assertThat(claim.get("payment_id"))
                .isEqualTo(result.id());

        assertThat(idempotencyCount(
                fixture.customerId(),
                fixture
        )).isZero();
    }

    private void assertRolledBackState(Fixture fixture) {
        Map<String, Object> order = jdbcTemplate.queryForMap(
                """
                select status, inventory_released_at
                from orders
                where id = ?
                """,
                fixture.orderId()
        );

        assertThat(order.get("status"))
                .isEqualTo(OrderStatus.SHIPPED.name());
        assertThat(order.get("inventory_released_at"))
                .isNull();
        assertThat(loadStock(fixture))
                .isEqualTo(INITIAL_STOCK);

        assertThat(count(
                "select count(*) from payments where order_id = ?",
                fixture.orderId()
        )).isZero();

        assertThat(count(
                """
                select count(*)
                from order_status_history
                where order_id = ?
                """,
                fixture.orderId()
        )).isZero();

        assertThat(count(
                """
                select count(*)
                from outbox_events
                where aggregate_id = ?
                """,
                fixture.orderId()
        )).isZero();

        assertThat(idempotencyCount(
                fixture.adminId(),
                fixture
        )).isZero();

        assertThat(idempotencyCount(
                fixture.customerId(),
                fixture
        )).isZero();
    }

    private Fixture seedShippedCodOrder() {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        fixtures.add(fixture);

        insertUser(
                fixture.customerId(),
                "cod-customer-" + fixture.customerId()
                        + "@example.com"
        );

        insertUser(
                fixture.adminId(),
                "cod-admin-" + fixture.adminId()
                        + "@example.com"
        );

        insertProduct(fixture.productId());

        Instant createdAt =
                Instant.now().minusSeconds(600);

        jdbcTemplate.update(
                """
                insert into orders (
                    id, user_id, status, total_amount,
                    expires_at, inventory_released_at,
                    recipient_name, recipient_phone,
                    shipping_address, shipping_method,
                    payment_mode, subtotal_amount,
                    shipping_fee, created_at, updated_at
                )
                values (
                    ?, ?, 'SHIPPED', ?,
                    null, null,
                    'Nguyen Van A', '+84901234567',
                    '123 Nguyen Trai, Ho Chi Minh City',
                    'STANDARD',
                    'COD', ?, ?, ?, ?
                )
                """,
                fixture.orderId(),
                fixture.customerId(),
                TOTAL,
                SUBTOTAL,
                SHIPPING_FEE,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

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
                fixture.orderId(),
                fixture.productId(),
                "COD-SNAPSHOT-" + fixture.productId(),
                "COD Transaction Product",
                UNIT_PRICE,
                ORDER_QUANTITY,
                SUBTOTAL,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        return fixture;
    }

    private void insertUser(
            UUID userId,
            String email
    ) {
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
                "COD Transaction User",
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
                "COD-" + productId,
                "COD Transaction Product",
                "Product used by COD transaction tests",
                UNIT_PRICE,
                INITIAL_STOCK
        );
    }

    private int loadStock(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                select stock_quantity
                from products
                where id = ?
                """,
                Integer.class,
                fixture.productId()
        );
    }

    private int idempotencyCount(
            UUID principalId,
            Fixture fixture
    ) {
        return count(
                """
                select count(*)
                from payment_idempotency_records
                where user_id = ?
                  and idempotency_key = ?
                """,
                principalId,
                idempotencyKey(fixture)
        );
    }

    private int count(
            String sql,
            Object... arguments
    ) {
        return jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                arguments
        );
    }

    private CollectCodCommand command(Fixture fixture) {
        return new CollectCodCommand(
                fixture.adminId(),
                fixture.orderId(),
                idempotencyKey(fixture)
        );
    }

    private static String idempotencyKey(
            Fixture fixture
    ) {
        return "cod-collection-" + fixture.orderId();
    }

    private record Fixture(
            UUID customerId,
            UUID adminId,
            UUID productId,
            UUID orderId
    ) {
    }
}