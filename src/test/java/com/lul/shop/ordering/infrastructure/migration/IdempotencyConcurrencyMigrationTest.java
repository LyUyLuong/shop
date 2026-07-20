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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyConcurrencyMigrationTest {

    private static final UUID USER_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );
    private static final UUID ORDER_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );
    private static final UUID PAYMENT_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );
    private static final String FINGERPRINT = "a".repeat(64);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("shop_v11_migration_test")
                    .withUsername("shop_test")
                    .withPassword("shop_test");

    static {
        POSTGRES.start();
    }

    @Test
    void shouldMigrateV10DataAndEnforceV11Foundation() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );

        migrate(dataSource, MigrationVersion.fromVersion("10"));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertV10Fixtures(jdbc);

        Map<String, Long> countsBefore = readBusinessCounts(jdbc);

        migrate(dataSource, null);

        assertThat(readBusinessCounts(jdbc)).isEqualTo(countsBefore);
        assertExistingRowsReceiveVersionZero(jdbc);
        verifyLegacyWriterDefaults(jdbc);
        verifyOrderIdempotencyConstraints(jdbc);
        verifyPaymentIdempotencyConstraints(jdbc);
        verifySupportingIndexes(jdbc);
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

    private static void insertV10Fixtures(JdbcTemplate jdbc) {
        jdbc.execute("""
                insert into users (
                    id, email, name, password_hash, enabled,
                    created_at, updated_at
                )
                values (
                    '11111111-1111-4111-8111-111111111111',
                    'v11-migration@example.com',
                    'V11 Migration User',
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
                    '44444444-4444-4444-8444-444444444444',
                    'V11-PRODUCT',
                    'V11 Product',
                    'Migration fixture',
                    100000.00,
                    12,
                    'ACTIVE',
                    null,
                    null,
                    now(),
                    now()
                );

                insert into carts (
                    id, user_id, created_at, updated_at
                )
                values (
                    '55555555-5555-4555-8555-555555555555',
                    '11111111-1111-4111-8111-111111111111',
                    now(),
                    now()
                );

                insert into orders (
                    id, user_id, status, total_amount,
                    created_at, updated_at, expires_at
                )
                values (
                    '22222222-2222-4222-8222-222222222222',
                    '11111111-1111-4111-8111-111111111111',
                    'PAID',
                    100000.00,
                    '2026-01-01T00:00:00Z',
                    '2026-01-01T00:00:00Z',
                    '2026-01-01T00:30:00Z'
                );

                insert into payments (
                    id, order_id, user_id, method, status, amount,
                    paid_at, failure_reason, created_at, updated_at
                )
                values (
                    '33333333-3333-4333-8333-333333333333',
                    '22222222-2222-4222-8222-222222222222',
                    '11111111-1111-4111-8111-111111111111',
                    'MOCK',
                    'SUCCEEDED',
                    100000.00,
                    '2026-01-01T00:05:00Z',
                    null,
                    '2026-01-01T00:05:00Z',
                    '2026-01-01T00:05:00Z'
                );
                """);
    }

    private static Map<String, Long> readBusinessCounts(
            JdbcTemplate jdbc
    ) {
        return Map.of(
                "users",
                jdbc.queryForObject(
                        "select count(*) from users",
                        Long.class
                ),
                "products",
                jdbc.queryForObject(
                        "select count(*) from products",
                        Long.class
                ),
                "carts",
                jdbc.queryForObject(
                        "select count(*) from carts",
                        Long.class
                ),
                "orders",
                jdbc.queryForObject(
                        "select count(*) from orders",
                        Long.class
                ),
                "payments",
                jdbc.queryForObject(
                        "select count(*) from payments",
                        Long.class
                )
        );
    }

    private static void assertExistingRowsReceiveVersionZero(
            JdbcTemplate jdbc
    ) {
        assertThat(jdbc.queryForObject(
                """
                select version
                from products
                where sku = 'V11-PRODUCT'
                """,
                Long.class
        )).isZero();

        assertThat(jdbc.queryForObject(
                """
                select version
                from carts
                where user_id = ?
                """,
                Long.class,
                USER_ID
        )).isZero();
    }

    private static void verifyLegacyWriterDefaults(
            JdbcTemplate jdbc
    ) {
        jdbc.execute("""
                insert into users (
                    id, email, name, password_hash, enabled,
                    created_at, updated_at
                )
                values (
                    '66666666-6666-4666-8666-666666666666',
                    'legacy-writer@example.com',
                    'Legacy Writer',
                    'password-hash',
                    true,
                    now(),
                    now()
                );

                insert into products (
                    id, sku, name, price, stock_quantity, status,
                    created_at, updated_at
                )
                values (
                    '77777777-7777-4777-8777-777777777777',
                    'LEGACY-WRITER',
                    'Legacy Product',
                    50000.00,
                    5,
                    'ACTIVE',
                    now(),
                    now()
                );

                insert into carts (
                    id, user_id, created_at, updated_at
                )
                values (
                    '88888888-8888-4888-8888-888888888888',
                    '66666666-6666-4666-8666-666666666666',
                    now(),
                    now()
                );
                """);

        assertThat(jdbc.queryForObject(
                """
                select version
                from products
                where sku = 'LEGACY-WRITER'
                """,
                Long.class
        )).isZero();

        assertThat(jdbc.queryForObject(
                """
                select version
                from carts
                where id =
                    '88888888-8888-4888-8888-888888888888'
                """,
                Long.class
        )).isZero();
    }

    private static void verifyOrderIdempotencyConstraints(
            JdbcTemplate jdbc
    ) {
        insertOrderRecord(
                jdbc,
                UUID.randomUUID(),
                "order-key-0001",
                FINGERPRINT,
                "PROCESSING",
                null
        );

        assertThatThrownBy(() -> insertOrderRecord(
                jdbc,
                UUID.randomUUID(),
                "order-key-0001",
                FINGERPRINT,
                "PROCESSING",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertOrderRecord(
                jdbc,
                UUID.randomUUID(),
                "order-key-0002",
                FINGERPRINT,
                "PROCESSING",
                ORDER_ID
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void verifyPaymentIdempotencyConstraints(
            JdbcTemplate jdbc
    ) {
        insertPaymentRecord(
                jdbc,
                UUID.randomUUID(),
                "payment-key-0001",
                FINGERPRINT,
                "COMPLETED",
                PAYMENT_ID
        );

        assertThatThrownBy(() -> insertPaymentRecord(
                jdbc,
                UUID.randomUUID(),
                "payment-key-0001",
                FINGERPRINT,
                "COMPLETED",
                PAYMENT_ID
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertPaymentRecord(
                jdbc,
                UUID.randomUUID(),
                "payment-key-0002",
                FINGERPRINT,
                "COMPLETED",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void insertOrderRecord(
            JdbcTemplate jdbc,
            UUID id,
            String key,
            String fingerprint,
            String status,
            UUID orderId
    ) {
        jdbc.update(
                """
                insert into order_idempotency_records (
                    id, user_id, idempotency_key,
                    request_fingerprint, status, order_id,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, now(), now())
                """,
                id,
                USER_ID,
                key,
                fingerprint,
                status,
                orderId
        );
    }

    private static void insertPaymentRecord(
            JdbcTemplate jdbc,
            UUID id,
            String key,
            String fingerprint,
            String status,
            UUID paymentId
    ) {
        jdbc.update(
                """
                insert into payment_idempotency_records (
                    id, user_id, idempotency_key,
                    request_fingerprint, status, payment_id,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, now(), now())
                """,
                id,
                USER_ID,
                key,
                fingerprint,
                status,
                paymentId
        );
    }

    private static void verifySupportingIndexes(
            JdbcTemplate jdbc
    ) {
        assertThat(jdbc.queryForList(
                """
                select indexname
                from pg_indexes
                where schemaname = 'public'
                  and indexname in (
                      'idx_order_idempotency_created_at',
                      'idx_order_idempotency_order_id',
                      'idx_payment_idempotency_created_at',
                      'idx_payment_idempotency_payment_id'
                  )
                """,
                String.class
        )).containsExactlyInAnyOrder(
                "idx_order_idempotency_created_at",
                "idx_order_idempotency_order_id",
                "idx_payment_idempotency_created_at",
                "idx_payment_idempotency_payment_id"
        );
    }
}
