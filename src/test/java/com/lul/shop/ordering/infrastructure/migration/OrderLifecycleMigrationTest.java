package com.lul.shop.ordering.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLifecycleMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("shop_migration_test")
                    .withUsername("shop_test")
                    .withPassword("shop_test");

    static {
        POSTGRES.start();
    }

    @Test
    void shouldMigrateV9DataToV10WithoutChangingBusinessState() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );

        migrate(dataSource, MigrationVersion.fromVersion("9"));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertV9Fixtures(jdbc);

        migrate(dataSource, null);

        assertLegacyOrder(
                jdbc,
                "11111111-1111-4111-8111-111111111111",
                "PENDING_PAYMENT",
                Instant.parse("2026-01-01T00:30:00Z")
        );

        assertLegacyOrder(
                jdbc,
                "22222222-2222-4222-8222-222222222222",
                "PAID",
                Instant.parse("2026-01-02T00:30:00Z")
        );

        assertLegacyOrder(
                jdbc,
                "33333333-3333-4333-8333-333333333333",
                "CANCELLED",
                Instant.parse("2026-01-03T00:30:00Z")
        );

        assertThat(jdbc.queryForObject(
                "select stock_quantity from products where id = ?",
                Integer.class,
                UUID.fromString("88888888-8888-4888-8888-888888888888")
        )).isEqualTo(17);

        verifyOldWriterDefault(jdbc);
        verifyPaymentHistoryActor(jdbc);
        verifyPendingExpiryIndex(jdbc);
    }

    private static void migrate(
            DataSource dataSource,
            MigrationVersion target
    ) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");

        if (target != null) {
            configuration.target(target);
        }

        configuration.load().migrate();
    }

    private static void insertV9Fixtures(JdbcTemplate jdbc) {
        jdbc.execute("""
                insert into users (
                    id, email, name, password_hash, enabled,
                    created_at, updated_at
                )
                values (
                    '99999999-9999-4999-8999-999999999999',
                    'migration@example.com',
                    'Migration User',
                    'password-hash',
                    true,
                    now(),
                    now()
                );

                insert into products (
                    id, sku, name, description, price, stock_quantity,
                    status, image_key, image_url, created_at, updated_at
                )
                values (
                    '88888888-8888-4888-8888-888888888888',
                    'MIGRATION-SKU',
                    'Migration Product',
                    'Migration fixture',
                    100000.00,
                    17,
                    'ACTIVE',
                    null,
                    null,
                    now(),
                    now()
                );

                insert into orders (
                    id, user_id, status, total_amount,
                    created_at, updated_at
                )
                values
                    (
                        '11111111-1111-4111-8111-111111111111',
                        '99999999-9999-4999-8999-999999999999',
                        'PENDING_PAYMENT',
                        100000.00,
                        '2026-01-01T00:00:00Z',
                        '2026-01-01T00:00:00Z'
                    ),
                    (
                        '22222222-2222-4222-8222-222222222222',
                        '99999999-9999-4999-8999-999999999999',
                        'PAID',
                        100000.00,
                        '2026-01-02T00:00:00Z',
                        '2026-01-02T00:00:00Z'
                    ),
                    (
                        '33333333-3333-4333-8333-333333333333',
                        '99999999-9999-4999-8999-999999999999',
                        'CANCELLED',
                        200000.00,
                        '2026-01-03T00:00:00Z',
                        '2026-01-03T00:00:00Z'
                    );
                """);
    }

    private static void assertLegacyOrder(
            JdbcTemplate jdbc,
            String orderId,
            String expectedStatus,
            Instant expectedExpiry
    ) {
        LifecycleRow row = jdbc.queryForObject(
                """
                select status, expires_at, inventory_released_at
                from orders
                where id = ?
                """,
                (resultSet, rowNumber) -> {
                    Timestamp releasedAt =
                            resultSet.getTimestamp("inventory_released_at");

                    return new LifecycleRow(
                            resultSet.getString("status"),
                            resultSet.getTimestamp("expires_at").toInstant(),
                            releasedAt == null
                                    ? null
                                    : releasedAt.toInstant()
                    );
                },
                UUID.fromString(orderId)
        );

        assertThat(row).isNotNull();
        assertThat(row.status()).isEqualTo(expectedStatus);
        assertThat(row.expiresAt()).isEqualTo(expectedExpiry);
        assertThat(row.inventoryReleasedAt()).isNull();
    }

    private static void verifyOldWriterDefault(JdbcTemplate jdbc) {
        UUID orderId = UUID.fromString(
                "44444444-4444-4444-8444-444444444444"
        );

        jdbc.update(
                """
                insert into orders (
                    id, user_id, status, total_amount,
                    created_at, updated_at
                )
                values (?, ?, 'PENDING_PAYMENT', 50000.00, now(), now())
                """,
                orderId,
                UUID.fromString(
                        "99999999-9999-4999-8999-999999999999"
                )
        );

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

        assertThat(expirySeconds).isEqualTo(
                Duration.ofMinutes(30).toSeconds()
        );
    }

    private static void verifyPaymentHistoryActor(JdbcTemplate jdbc) {
        jdbc.update(
                """
                insert into order_status_history (
                    id, order_id, from_status, to_status,
                    actor_type, actor_user_id, reason, created_at
                )
                values (?, ?, 'PENDING_PAYMENT', 'PAID',
                        'PAYMENT', null, 'Payment succeeded', now())
                """,
                UUID.fromString(
                        "55555555-5555-4555-8555-555555555555"
                ),
                UUID.fromString(
                        "11111111-1111-4111-8111-111111111111"
                )
        );

        assertThat(jdbc.queryForObject(
                """
                select actor_type
                from order_status_history
                where id = ?
                """,
                String.class,
                UUID.fromString(
                        "55555555-5555-4555-8555-555555555555"
                )
        )).isEqualTo("PAYMENT");
    }

    private static void verifyPendingExpiryIndex(JdbcTemplate jdbc) {
        String indexDefinition = jdbc.queryForObject(
                """
                select indexdef
                from pg_indexes
                where schemaname = 'public'
                  and indexname =
                      'idx_orders_pending_payment_expires_at'
                """,
                String.class
        );

        assertThat(indexDefinition)
                .contains("expires_at")
                .contains("PENDING_PAYMENT");
    }

    private record LifecycleRow(
            String status,
            Instant expiresAt,
            Instant inventoryReleasedAt
    ) {
    }
}