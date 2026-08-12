package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.catalog.domain.ProductSearchSlice;
import com.lul.shop.catalog.domain.ProductSearchWindow;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductSearchTrigramContractIntegrationTest
        extends PostgresIntegrationTest {

    private static final int ROW_COUNT = 1000;
    private static final long SEED = 20260806L;

    private static final String BASE_SEED_SCRIPT =
            "benchmark/product-search/seed-products.sql";

    private static final String C1_OVERLAY_SCRIPT =
            "benchmark/product-search/seed-trigram-workloads.sql";

    private static final String MEDIUM_TERM =
            "C1-MEDIUM-INFIX";

    private static final String COMMON_TERM =
            "C1-COMMON-INFIX";

    private static final String ACCENTED_TERM =
            "Thi\u1EBFt b\u1ECB \u0111i\u1EC7n";

    private static final String UNACCENTED_TERM =
            "Thiet bi dien";

    private static final String JAPANESE_TERM =
            "\u691C\u7D22\u57FA\u6E96";

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seedC1Dataset() throws IOException {
        assertThat(countProducts()).isZero();

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
                readClasspathResource(BASE_SEED_SCRIPT)
        );
        jdbcTemplate.execute(
                readClasspathResource(C1_OVERLAY_SCRIPT)
        );

        entityManager.clear();
    }

    @Test
    void shouldPreserveExactPrefixInfixRankingAndDualFieldMembership() {
        PageResult<Product> ranked = searchActive(
                "c1-rank-term",
                ROW_COUNT
        );

        assertIdsInOrder(
                ranked,
                id(101),
                id(104),
                id(102),
                id(103),
                id(106)
        );

        assertIdsInOrder(
                searchActive("sku-middle", ROW_COUNT),
                id(103)
        );
        assertIdsInOrder(
                searchActive("name-middle", ROW_COUNT),
                id(106)
        );
        assertIdsInOrder(
                searchActive("c1-dual-infix", ROW_COUNT),
                id(124),
                id(123)
        );
    }

    @Test
    void shouldPreserveShortCaseAccentAndUnicodeMembership() {
        assertIdsInOrder(
                searchActive("q", ROW_COUNT),
                id(107),
                id(108),
                id(109)
        );
        assertIdsInOrder(
                searchActive("qz", ROW_COUNT),
                id(108),
                id(109)
        );
        assertIdsInOrder(
                searchActive("qzx", ROW_COUNT),
                id(109)
        );

        assertIdsInOrder(
                searchActive("c1 mixed term", ROW_COUNT),
                id(111)
        );
        assertIdsInOrder(
                searchActive(ACCENTED_TERM, ROW_COUNT),
                id(112)
        );
        assertIdsInOrder(
                searchActive(UNACCENTED_TERM, ROW_COUNT),
                id(113)
        );
        assertIdsInOrder(
                searchActive(JAPANESE_TERM, ROW_COUNT),
                id(114)
        );

        assertThat(
                searchActive("dien", ROW_COUNT).content()
        ).extracting(Product::getId)
                .doesNotContain(id(112));
    }

    @Test
    void shouldPreserveDeterministicSelectivityAndAdminCardinality() {
        assertCardinality(
                searchActive(MEDIUM_TERM, ROW_COUNT),
                (ROW_COUNT / 10) - 4
        );

        assertCardinality(
                searchActive(COMMON_TERM, ROW_COUNT),
                (ROW_COUNT * 2 / 5) - 11
        );
        assertCardinality(
                searchAdmin(COMMON_TERM, null),
                (ROW_COUNT / 2) - 13
        );
        assertCardinality(
                searchAdmin(COMMON_TERM, ProductStatus.ACTIVE),
                (ROW_COUNT * 2 / 5) - 11
        );
        assertCardinality(
                searchAdmin(COMMON_TERM, ProductStatus.INACTIVE),
                (ROW_COUNT / 10) - 2
        );

        assertCardinality(
                searchActive("C1-NO-RESULT-NEEDLE", ROW_COUNT),
                0
        );
    }

    @Test
    void shouldPreserveWildcardBackslashAndLongKeywordBehavior() {
        PageResult<Product> percent =
                searchActive("%", ROW_COUNT);
        PageResult<Product> underscore =
                searchActive("_", ROW_COUNT);

        assertCardinality(
                percent,
                ROW_COUNT * 4 / 5
        );
        assertThat(percent.content().get(0).getId())
                .isEqualTo(id(116));

        assertCardinality(
                underscore,
                ROW_COUNT * 4 / 5
        );
        assertThat(underscore.content().get(0).getId())
                .isEqualTo(id(117));

        assertIdsInOrder(
                searchActive("\\", ROW_COUNT),
                id(118)
        );
        assertCardinality(
                searchActive("x".repeat(5000), ROW_COUNT),
                0
        );
    }

    @Test
    void shouldPreservePublicAndAdminVisibilityBoundaries() {
        assertCardinality(
                searchActive("C1-INACTIVE-EXACT", ROW_COUNT),
                0
        );
        assertIdsInOrder(
                searchAdmin("C1-INACTIVE-EXACT", null),
                id(105)
        );
        assertIdsInOrder(
                searchAdmin(
                        "C1-INACTIVE-EXACT",
                        ProductStatus.INACTIVE
                ),
                id(105)
        );
        assertCardinality(
                searchAdmin(
                        "C1-INACTIVE-EXACT",
                        ProductStatus.ACTIVE
                ),
                0
        );

        assertIdsInOrder(
                searchActive("C1-ADMIN-PAIR", ROW_COUNT),
                id(119)
        );
        assertIdsInOrder(
                searchAdmin("C1-ADMIN-PAIR", null),
                id(119),
                id(120)
        );
        assertIdsInOrder(
                searchAdmin(
                        "C1-ADMIN-PAIR",
                        ProductStatus.INACTIVE
                ),
                id(120)
        );
    }

    @Test
    void shouldKeepOffsetAndCursorTraversalEquivalent() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(COMMON_TERM);

        List<UUID> offsetIds = traverseOffset(
                criteria,
                37
        );
        List<UUID> cursorIds = traverseCursor(
                criteria,
                37
        );

        assertThat(offsetIds)
                .hasSize((ROW_COUNT * 2 / 5) - 11)
                .doesNotHaveDuplicates();
        assertThat(cursorIds)
                .containsExactlyElementsOf(offsetIds)
                .doesNotHaveDuplicates();
    }

    @Test
    void shouldKeepSimilarityOnlyFixturesOutsideCurrentLikeMembership() {
        assertIdsInOrder(
                searchActive("wireless headset", ROW_COUNT),
                id(121)
        );
        assertCardinality(
                searchActive("wireles headset", ROW_COUNT),
                0
        );
        assertCardinality(
                searchActive("wireless haedset", ROW_COUNT),
                0
        );

        assertIdsInOrder(
                searchActive("mechanical keyboard", ROW_COUNT),
                id(122)
        );
        assertCardinality(
                searchActive("mechanicl keyboard", ROW_COUNT),
                0
        );
        assertCardinality(
                searchActive("mechanical keybaord", ROW_COUNT),
                0
        );
    }

    @Test
    void shouldKeepC1SchemaAtTheNoTrigramBaseline() {
        assertThat(countProducts()).isEqualTo(ROW_COUNT);
        assertThat(queryForLong(
                """
                SELECT count(*)
                FROM products
                WHERE image_key LIKE 'benchmark/c1/%'
                """
        )).isEqualTo((ROW_COUNT * 11L / 20L) + 9L);

        assertThat(queryForLong(
                """
                SELECT count(*)
                FROM products
                WHERE status = 'ACTIVE'
                """
        )).isEqualTo(ROW_COUNT * 4L / 5L);
        assertThat(queryForLong(
                """
                SELECT count(*)
                FROM products
                WHERE status = 'INACTIVE'
                """
        )).isEqualTo(ROW_COUNT / 5L);

        assertThat(queryForLong(
                """
                SELECT count(*)
                FROM pg_extension
                WHERE extname = 'pg_trgm'
                """
        )).isZero();
        assertThat(queryForLong(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                  AND (
                      indexdef ILIKE '%gin_trgm_ops%'
                      OR indexdef ILIKE '%gist_trgm_ops%'
                      OR indexname LIKE 'c1_%'
                      OR indexname LIKE 'c2_%'
                  )
                """
        )).isZero();

        assertThat(jdbcTemplate.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                ORDER BY indexname
                """,
                String.class
        )).containsExactly(
                "idx_products_created_at",
                "idx_products_name_lower",
                "idx_products_sku_lower",
                "idx_products_status",
                "products_pkey"
        );
    }

    private PageResult<Product> searchActive(
            String keyword,
            int size
    ) {
        return productRepository.search(
                ProductSearchCriteria.activeOnly(keyword),
                new PageQuery(0, size)
        );
    }

    private PageResult<Product> searchAdmin(
            String keyword,
            ProductStatus status
    ) {
        return productRepository.search(
                ProductSearchCriteria.withStatus(
                        keyword,
                        status
                ),
                new PageQuery(0, ROW_COUNT)
        );
    }

    private List<UUID> traverseOffset(
            ProductSearchCriteria criteria,
            int size
    ) {
        List<UUID> ids = new ArrayList<>();
        int page = 0;

        while (page < ROW_COUNT) {
            PageResult<Product> result =
                    productRepository.search(
                            criteria,
                            new PageQuery(page, size)
                    );

            ids.addAll(productIds(result.content()));

            if (!result.hasNext()) {
                assertThat(result.totalElements())
                        .isEqualTo(ids.size());
                return ids;
            }

            page++;
        }

        throw new AssertionError(
                "offset traversal did not terminate"
        );
    }

    private List<UUID> traverseCursor(
            ProductSearchCriteria criteria,
            int size
    ) {
        List<UUID> ids = new ArrayList<>();
        ProductSearchWindow window =
                ProductSearchWindow.first(size);

        for (int page = 0; page < ROW_COUNT; page++) {
            ProductSearchSlice<Product> slice =
                    productRepository.search(
                            criteria,
                            window
                    );

            ids.addAll(productIds(slice.content()));

            if (!slice.hasNext()) {
                assertThat(slice.nextPosition()).isEmpty();
                return ids;
            }

            ProductSearchPosition position =
                    slice.nextPosition().orElseThrow();

            window = ProductSearchWindow.after(
                    size,
                    position
            );
        }

        throw new AssertionError(
                "cursor traversal did not terminate"
        );
    }

    private List<UUID> productIds(List<Product> products) {
        return products.stream()
                .map(Product::getId)
                .toList();
    }

    private void assertIdsInOrder(
            PageResult<Product> result,
            UUID... expectedIds
    ) {
        assertThat(result.content())
                .extracting(Product::getId)
                .containsExactly(expectedIds);
        assertThat(result.totalElements())
                .isEqualTo(expectedIds.length);
    }

    private void assertCardinality(
            PageResult<Product> result,
            long expected
    ) {
        assertThat(result.totalElements()).isEqualTo(expected);
        assertThat(result.content()).hasSize((int) expected);
    }

    private long countProducts() {
        return queryForLong(
                "SELECT count(*) FROM products"
        );
    }

    private long queryForLong(String sql) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                Long.class
        );

        assertThat(value).isNotNull();
        return value;
    }

    private String readClasspathResource(String path)
            throws IOException {
        ClassPathResource resource =
                new ClassPathResource(path);

        try (InputStream input = resource.getInputStream()) {
            return new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private UUID id(long sequence) {
        String source = SEED
                + ":product:"
                + sequence;

        try {
            byte[] digest = MessageDigest
                    .getInstance("MD5")
                    .digest(source.getBytes(StandardCharsets.UTF_8));

            ByteBuffer bytes = ByteBuffer.wrap(digest);

            return new UUID(
                    bytes.getLong(),
                    bytes.getLong()
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "MD5 is required to reproduce benchmark IDs",
                    exception
            );
        }
    }
}
