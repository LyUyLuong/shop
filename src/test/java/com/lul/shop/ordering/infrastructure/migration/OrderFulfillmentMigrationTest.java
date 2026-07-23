package com.lul.shop.ordering.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderFulfillmentMigrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID LEGACY_PENDING_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final UUID LEGACY_PAID_ID =
            UUID.fromString(
                    "33333333-3333-4333-8333-333333333333"
            );

    private static final UUID LEGACY_PAYMENT_ID =
            UUID.fromString(
                    "44444444-4444-4444-8444-444444444444"
            );

    private static final UUID COD_ORDER_ID =
            UUID.fromString(
                    "55555555-5555-4555-8555-555555555555"
            );

    private static final UUID MOCK_ORDER_ID =
            UUID.fromString(
                    "66666666-6666-4666-8666-666666666666"
            );

    private static final UUID COD_PAYMENT_ID =
            UUID.fromString(
                    "77777777-7777-4777-8777-777777777777"
            );

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-23T00:00:00Z");

    private static final Instant PAYMENT_DEADLINE =
            Instant.parse("2026-07-23T00:30:00Z");

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName(
                            "shop_v12_migration_test"
                    )
                    .withUsername("shop_test")
                    .withPassword("shop_test");

    static {
        POSTGRES.start();
    }

    @Test
    void shouldMigrateV11DataAndEnforceV12Foundation() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                );

        migrate(
                dataSource,
                MigrationVersion.fromVersion("11")
        );

        JdbcTemplate jdbc =
                new JdbcTemplate(dataSource);

        insertV11Fixtures(jdbc);

        migrate(
                dataSource,
                MigrationVersion.fromVersion("12")
        );

        assertLegacyOrderBackfilled(
                jdbc,
                LEGACY_PENDING_ID,
                "PENDING_PAYMENT",
                new BigDecimal("120000.00")
        );

        assertLegacyOrderBackfilled(
                jdbc,
                LEGACY_PAID_ID,
                "PAID",
                new BigDecimal("100000.00")
        );

        assertThat(jdbc.queryForObject(
                """
                select method
                from payments
                where id = ?
                """,
                String.class,
                LEGACY_PAYMENT_ID
        )).isEqualTo("MOCK");

        verifyRollbackCompatibleOldWriter(jdbc);
        verifyCompleteNewRows(jdbc);
        verifyInvalidRowsAreRejected(jdbc);
        verifyCustomerHistoryIndex(jdbc);
    }

    private static void migrate(
            DataSource dataSource,
            MigrationVersion target
    ) {
        FluentConfiguration configuration =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations(
                                "classpath:db/migration"
                        )
                        .target(target);

        configuration.load().migrate();
    }

    private static void insertV11Fixtures(
            JdbcTemplate jdbc
    ) {
        jdbc.execute("""
                insert into users (
                    id,
                    email,
                    name,
                    password_hash,
                    enabled,
                    created_at,
                    updated_at
                )
                values (
                    '11111111-1111-4111-8111-111111111111',
                    'v12-migration@example.com',
                    'V12 Migration User',
                    'password-hash',
                    true,
                    now(),
                    now()
                );

                insert into orders (
                    id,
                    user_id,
                    status,
                    total_amount,
                    created_at,
                    updated_at,
                    expires_at
                )
                values
                    (
                        '22222222-2222-4222-8222-222222222222',
                        '11111111-1111-4111-8111-111111111111',
                        'PENDING_PAYMENT',
                        120000.00,
                        '2026-07-23T00:00:00Z',
                        '2026-07-23T00:00:00Z',
                        '2026-07-23T00:30:00Z'
                    ),
                    (
                        '33333333-3333-4333-8333-333333333333',
                        '11111111-1111-4111-8111-111111111111',
                        'PAID',
                        100000.00,
                        '2026-07-23T00:00:00Z',
                        '2026-07-23T00:05:00Z',
                        '2026-07-23T00:30:00Z'
                    );

                insert into payments (
                    id,
                    order_id,
                    user_id,
                    method,
                    status,
                    amount,
                    paid_at,
                    failure_reason,
                    created_at,
                    updated_at
                )
                values (
                    '44444444-4444-4444-8444-444444444444',
                    '33333333-3333-4333-8333-333333333333',
                    '11111111-1111-4111-8111-111111111111',
                    'MOCK',
                    'SUCCEEDED',
                    100000.00,
                    '2026-07-23T00:05:00Z',
                    null,
                    '2026-07-23T00:05:00Z',
                    '2026-07-23T00:05:00Z'
                );
                """);
    }

    private static void assertLegacyOrderBackfilled(
            JdbcTemplate jdbc,
            UUID orderId,
            String expectedStatus,
            BigDecimal expectedTotal
    ) {
        OrderSnapshotRow row =
                readOrder(jdbc, orderId);

        assertThat(row.status())
                .isEqualTo(expectedStatus);

        assertThat(row.paymentMode())
                .isEqualTo("MOCK");

        assertThat(row.totalAmount())
                .isEqualByComparingTo(expectedTotal);

        assertThat(row.subtotalAmount())
                .isEqualByComparingTo(expectedTotal);

        assertThat(row.shippingFee())
                .isEqualByComparingTo("0.00");

        assertThat(row.recipientName()).isNull();
        assertThat(row.recipientPhone()).isNull();
        assertThat(row.shippingAddress()).isNull();
        assertThat(row.shippingMethod()).isNull();
        assertThat(row.expiresAt()).isNotNull();
    }

    private static void verifyRollbackCompatibleOldWriter(
            JdbcTemplate jdbc
    ) {
        UUID orderId = UUID.fromString(
                "88888888-8888-4888-8888-888888888888"
        );

        jdbc.update(
                """
                insert into orders (
                    id,
                    user_id,
                    status,
                    total_amount,
                    created_at,
                    updated_at
                )
                values (
                    ?,
                    ?,
                    'PENDING_PAYMENT',
                    50000.00,
                    now(),
                    now()
                )
                """,
                orderId,
                USER_ID
        );

        OrderSnapshotRow row =
                readOrder(jdbc, orderId);

        assertThat(row.paymentMode()).isNull();
        assertThat(row.subtotalAmount()).isNull();
        assertThat(row.shippingFee()).isNull();
        assertThat(row.recipientName()).isNull();
        assertThat(row.expiresAt()).isNotNull();

        Long expirySeconds = jdbc.queryForObject(
                """
                select extract(
                    epoch from (expires_at - created_at)
                )::bigint
                from orders
                where id = ?
                """,
                Long.class,
                orderId
        );

        assertThat(expirySeconds).isEqualTo(1800L);
    }

    private static void verifyCompleteNewRows(
            JdbcTemplate jdbc
    ) {
        insertOrder(
                jdbc,
                COD_ORDER_ID,
                "CONFIRMED",
                new BigDecimal("130000.00"),
                null,
                "Nguyen Van A",
                "+84912345678",
                "123 Nguyen Trai, Ward 2",
                "STANDARD",
                "COD",
                new BigDecimal("100000.00"),
                new BigDecimal("30000.00")
        );

        insertOrder(
                jdbc,
                MOCK_ORDER_ID,
                "PENDING_PAYMENT",
                new BigDecimal("130000.00"),
                PAYMENT_DEADLINE,
                "Nguyen Van B",
                "0912345678",
                "456 Le Loi, Ward 3",
                "STANDARD",
                "MOCK",
                new BigDecimal("100000.00"),
                new BigDecimal("30000.00")
        );

        OrderSnapshotRow codOrder =
                readOrder(jdbc, COD_ORDER_ID);

        assertThat(codOrder.status())
                .isEqualTo("CONFIRMED");

        assertThat(codOrder.paymentMode())
                .isEqualTo("COD");

        assertThat(codOrder.totalAmount())
                .isEqualByComparingTo("130000.00");

        assertThat(codOrder.subtotalAmount())
                .isEqualByComparingTo("100000.00");

        assertThat(codOrder.shippingFee())
                .isEqualByComparingTo("30000.00");

        assertThat(codOrder.recipientName())
                .isEqualTo("Nguyen Van A");

        assertThat(codOrder.expiresAt()).isNull();

        OrderSnapshotRow mockOrder =
                readOrder(jdbc, MOCK_ORDER_ID);

        assertThat(mockOrder.paymentMode())
                .isEqualTo("MOCK");

        assertThat(mockOrder.expiresAt())
                .isEqualTo(PAYMENT_DEADLINE);

        jdbc.update(
                """
                insert into payments (
                    id,
                    order_id,
                    user_id,
                    method,
                    status,
                    amount,
                    paid_at,
                    failure_reason,
                    created_at,
                    updated_at
                )
                values (
                    ?,
                    ?,
                    ?,
                    'COD',
                    'SUCCEEDED',
                    130000.00,
                    now(),
                    null,
                    now(),
                    now()
                )
                """,
                COD_PAYMENT_ID,
                COD_ORDER_ID,
                USER_ID
        );

        assertThat(jdbc.queryForObject(
                """
                select method
                from payments
                where id = ?
                """,
                String.class,
                COD_PAYMENT_ID
        )).isEqualTo("COD");
    }

    private static void verifyInvalidRowsAreRejected(
            JdbcTemplate jdbc
    ) {
        assertThatThrownBy(() ->
                insertOrder(
                        jdbc,
                        UUID.randomUUID(),
                        "PENDING_PAYMENT",
                        new BigDecimal("130000.00"),
                        PAYMENT_DEADLINE,
                        "Nguyen Van A",
                        null,
                        "123 Nguyen Trai, Ward 2",
                        "STANDARD",
                        "MOCK",
                        new BigDecimal("100000.00"),
                        new BigDecimal("30000.00")
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );

        assertThatThrownBy(() ->
                insertOrder(
                        jdbc,
                        UUID.randomUUID(),
                        "PENDING_PAYMENT",
                        new BigDecimal("100000.00"),
                        PAYMENT_DEADLINE,
                        "Nguyen Van A",
                        "0912345678",
                        "123 Nguyen Trai, Ward 2",
                        "STANDARD",
                        "MOCK",
                        new BigDecimal("100000.00"),
                        new BigDecimal("30000.00")
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );

        assertThatThrownBy(() ->
                insertOrder(
                        jdbc,
                        UUID.randomUUID(),
                        "CONFIRMED",
                        new BigDecimal("130000.00"),
                        PAYMENT_DEADLINE,
                        "Nguyen Van A",
                        "0912345678",
                        "123 Nguyen Trai, Ward 2",
                        "STANDARD",
                        "COD",
                        new BigDecimal("100000.00"),
                        new BigDecimal("30000.00")
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );

        assertThatThrownBy(() ->
                insertOrder(
                        jdbc,
                        UUID.randomUUID(),
                        "PENDING_PAYMENT",
                        new BigDecimal("130000.00"),
                        null,
                        "Nguyen Van A",
                        "0912345678",
                        "123 Nguyen Trai, Ward 2",
                        "STANDARD",
                        "COD",
                        new BigDecimal("100000.00"),
                        new BigDecimal("30000.00")
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );

        assertThatThrownBy(() ->
                insertOrder(
                        jdbc,
                        UUID.randomUUID(),
                        "CONFIRMED",
                        new BigDecimal("130000.00"),
                        null,
                        "Nguyen Van A",
                        "0912345678",
                        "123 Nguyen Trai, Ward 2",
                        "EXPRESS",
                        "COD",
                        new BigDecimal("100000.00"),
                        new BigDecimal("30000.00")
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );
    }

    private static void insertOrder(
            JdbcTemplate jdbc,
            UUID orderId,
            String status,
            BigDecimal totalAmount,
            Instant expiresAt,
            String recipientName,
            String recipientPhone,
            String shippingAddress,
            String shippingMethod,
            String paymentMode,
            BigDecimal subtotalAmount,
            BigDecimal shippingFee
    ) {
        jdbc.update(
                """
                insert into orders (
                    id,
                    user_id,
                    status,
                    total_amount,
                    created_at,
                    updated_at,
                    expires_at,
                    recipient_name,
                    recipient_phone,
                    shipping_address,
                    shipping_method,
                    payment_mode,
                    subtotal_amount,
                    shipping_fee
                )
                values (
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?
                )
                """,
                orderId,
                USER_ID,
                status,
                totalAmount,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT),
                expiresAt == null
                        ? null
                        : Timestamp.from(expiresAt),
                recipientName,
                recipientPhone,
                shippingAddress,
                shippingMethod,
                paymentMode,
                subtotalAmount,
                shippingFee
        );
    }

    private static OrderSnapshotRow readOrder(
            JdbcTemplate jdbc,
            UUID orderId
    ) {
        return jdbc.queryForObject(
                """
                select
                    status,
                    payment_mode,
                    total_amount,
                    subtotal_amount,
                    shipping_fee,
                    recipient_name,
                    recipient_phone,
                    shipping_address,
                    shipping_method,
                    expires_at
                from orders
                where id = ?
                """,
                (resultSet, rowNumber) -> {
                    Timestamp expiresAt =
                            resultSet.getTimestamp(
                                    "expires_at"
                            );

                    return new OrderSnapshotRow(
                            resultSet.getString("status"),
                            resultSet.getString(
                                    "payment_mode"
                            ),
                            resultSet.getBigDecimal(
                                    "total_amount"
                            ),
                            resultSet.getBigDecimal(
                                    "subtotal_amount"
                            ),
                            resultSet.getBigDecimal(
                                    "shipping_fee"
                            ),
                            resultSet.getString(
                                    "recipient_name"
                            ),
                            resultSet.getString(
                                    "recipient_phone"
                            ),
                            resultSet.getString(
                                    "shipping_address"
                            ),
                            resultSet.getString(
                                    "shipping_method"
                            ),
                            expiresAt == null
                                    ? null
                                    : expiresAt.toInstant()
                    );
                },
                orderId
        );
    }

    private static void verifyCustomerHistoryIndex(
            JdbcTemplate jdbc
    ) {
        String indexDefinition =
                jdbc.queryForObject(
                        """
                        select indexdef
                        from pg_indexes
                        where schemaname = 'public'
                          and indexname =
                            'idx_orders_user_created_at_id_desc'
                        """,
                        String.class
                );

        assertThat(indexDefinition)
                .contains("user_id")
                .contains("created_at DESC")
                .contains("id DESC");
    }

    private record OrderSnapshotRow(
            String status,
            String paymentMode,
            BigDecimal totalAmount,
            BigDecimal subtotalAmount,
            BigDecimal shippingFee,
            String recipientName,
            String recipientPhone,
            String shippingAddress,
            String shippingMethod,
            Instant expiresAt
    ) {
    }
}