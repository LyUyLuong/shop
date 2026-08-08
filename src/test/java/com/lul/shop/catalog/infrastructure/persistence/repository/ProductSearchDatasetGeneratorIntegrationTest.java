package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductSearchDatasetGeneratorIntegrationTest
        extends PostgresIntegrationTest {

    private static final int ROW_COUNT = 1000;
    private static final long SEED = 20260806L;

    private static final String DESCRIPTION_MARKER =
            "V2-PS-A deterministic dataset seed " + SEED;

    private static final String SEED_SCRIPT =
            "benchmark/product-search/seed-products.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void shouldRecreateDeterministicDatasetAndPreserveDistribution()
            throws IOException {

        jdbcTemplate.execute(
                "SET LOCAL shop_benchmark.row_count = '"
                        + ROW_COUNT
                        + "'"
        );
        jdbcTemplate.execute(
                "SET LOCAL shop_benchmark.seed = '"
                        + SEED
                        + "'"
        );

        jdbcTemplate.execute(
                readClasspathResource(SEED_SCRIPT)
        );

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(ROW_COUNT);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND status = 'ACTIVE'
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(800);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND status = 'INACTIVE'
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(200);

        assertThat(count(
                """
                SELECT count(DISTINCT lower(sku))
                FROM products
                WHERE description = ?
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(ROW_COUNT);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND lower(name) LIKE '%rare orchid%'
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(1);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND lower(name) LIKE '%medium cedar%'
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(100);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND lower(name) LIKE '%common market%'
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(899);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND name LIKE ?
                """,
                DESCRIPTION_MARKER,
                "%Điện Thoại%"
        )).isEqualTo(20);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND name LIKE ?
                """,
                DESCRIPTION_MARKER,
                "%日本語%"
        )).isEqualTo(20);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND name LIKE ?
                """,
                DESCRIPTION_MARKER,
                "%MiXeD CaSe%"
        )).isEqualTo(20);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND char_length(sku) = 100
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(1);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND char_length(name) = 255
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(1);

        assertThat(count(
                """
                SELECT count(*)
                FROM products
                WHERE description = ?
                  AND (
                      price < 0
                      OR stock_quantity < 0
                      OR version <> 0
                  )
                """,
                DESCRIPTION_MARKER
        )).isZero();

        assertThat(count(
                """
                SELECT count(*)
                FROM (
                    SELECT created_at
                    FROM products
                    WHERE description = ?
                    GROUP BY created_at
                    HAVING count(*) = 100
                ) AS timestamp_groups
                """,
                DESCRIPTION_MARKER
        )).isEqualTo(10);

        PageResult<Product> rareMatches =
                productRepository.search(
                        ProductSearchCriteria.activeOnly(
                                "Rare Orchid Product 00000007"
                        ),
                        new PageQuery(0, 20)
                );

        assertThat(rareMatches.totalElements()).isEqualTo(1);
        assertThat(rareMatches.content()).hasSize(1);

        Product rareProduct = rareMatches.content().get(0);

        assertThat(rareProduct.getId()).isEqualTo(
                UUID.fromString(
                        "d324987d-666f-e78a-23c2-8d3f4e3efa56"
                )
        );
        assertThat(rareProduct.getSku()).isEqualTo(
                "DELTA-20260806-00000007-GREEN"
        );
        assertThat(rareProduct.getName()).isEqualTo(
                "Rare Orchid Product 00000007"
        );
        assertThat(rareProduct.getPrice())
                .isEqualByComparingTo(
                        new BigDecimal("19.59")
                );
        assertThat(rareProduct.getStockQuantity())
                .isEqualTo(119);
        assertThat(rareProduct.getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
        assertThat(rareProduct.getVersion()).isZero();
        assertThat(rareProduct.getCreatedAt()).isEqualTo(
                Instant.parse("2026-01-01T00:00:04Z")
        );
        assertThat(rareProduct.getUpdatedAt()).isEqualTo(
                Instant.parse("2026-01-01T01:00:04Z")
        );
    }

    private long count(
            String sql,
            Object... parameters
    ) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                parameters
        );

        return Objects.requireNonNull(
                value,
                "count query must return a value"
        );
    }

    private String readClasspathResource(
            String path
    ) throws IOException {
        ClassPathResource resource =
                new ClassPathResource(path);

        try (InputStream inputStream =
                     resource.getInputStream()) {
            return new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }
    }
}