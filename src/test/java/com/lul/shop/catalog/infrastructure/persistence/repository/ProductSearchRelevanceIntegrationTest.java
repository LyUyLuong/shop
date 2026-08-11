package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductSearchRelevanceIntegrationTest
        extends PostgresIntegrationTest {

    private static final BigDecimal PRICE =
            new BigDecimal("100000.00");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRankExactSkuThenNamePrefixThenContains() {
        Product exactSku = saveActiveProduct(
                "PSB-RANK-TARGET",
                "PSB-RANK-TARGET Premium"
        );
        Product namePrefix = saveActiveProduct(
                "PSB-RANK-PREFIX",
                "PSB-RANK-TARGET Starter"
        );
        Product contains = saveActiveProduct(
                "PSB-RANK-CONTAINS",
                "Accessory for PSB-RANK-TARGET"
        );

        flushAndClear();

        updateCreatedAt(
                exactSku,
                "2026-03-01T00:00:00Z"
        );
        updateCreatedAt(
                namePrefix,
                "2026-03-02T00:00:00Z"
        );
        updateCreatedAt(
                contains,
                "2026-03-03T00:00:00Z"
        );

        entityManager.clear();

        PageResult<Product> result =
                searchActive("  psb-rank-target  ");

        assertIdsInOrder(
                result,
                exactSku,
                namePrefix,
                contains
        );
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.content())
                .extracting(Product::getId)
                .doesNotHaveDuplicates();
    }

    @Test
    void shouldTreatCombinedWildcardsLiterallyForPrefixPriority() {
        Product literalPrefix = saveActiveProduct(
                "PSB-WILDCARD-LITERAL",
                "50%_off\\ Premium Case"
        );
        Product wildcardOnly = saveActiveProduct(
                "PSB-WILDCARD-PATTERN",
                "50-anyXoff\\ Adapter"
        );
        saveActiveProduct(
                "PSB-WILDCARD-UNRELATED",
                "Ordinary Product"
        );

        flushAndClear();

        updateCreatedAt(
                literalPrefix,
                "2026-03-01T00:00:00Z"
        );
        updateCreatedAt(
                wildcardOnly,
                "2026-03-02T00:00:00Z"
        );

        entityManager.clear();

        PageResult<Product> result =
                searchActive("50%_off\\");

        assertIdsInOrder(
                result,
                literalPrefix,
                wildcardOnly
        );
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void shouldApplyVisibilityBeforeMatchPriority() {
        Product activePrefix = saveActiveProduct(
                "PSB-VISIBILITY-ACTIVE",
                "PSB-VISIBILITY Public Product"
        );
        Product inactiveExact = saveInactiveProduct(
                "PSB-VISIBILITY",
                "Inactive Exact SKU"
        );

        flushAndClear();

        PageResult<Product> publicResult =
                searchActive("psb-visibility");

        PageResult<Product> allAdminResult = search(
                ProductSearchCriteria.withStatus(
                        "psb-visibility",
                        null
                ),
                0,
                20
        );

        PageResult<Product> activeAdminResult = search(
                ProductSearchCriteria.withStatus(
                        "psb-visibility",
                        ProductStatus.ACTIVE
                ),
                0,
                20
        );

        PageResult<Product> inactiveAdminResult = search(
                ProductSearchCriteria.withStatus(
                        "psb-visibility",
                        ProductStatus.INACTIVE
                ),
                0,
                20
        );

        assertIdsInOrder(publicResult, activePrefix);
        assertIdsInOrder(
                allAdminResult,
                inactiveExact,
                activePrefix
        );
        assertIdsInOrder(activeAdminResult, activePrefix);
        assertIdsInOrder(inactiveAdminResult, inactiveExact);
    }

    @Test
    void shouldKeepCreatedAtOrderingForBlankKeyword() {
        Product older = saveActiveProduct(
                "PSB-BLANK-OLDER",
                "Older Product"
        );
        Product newer = saveActiveProduct(
                "PSB-BLANK-NEWER",
                "Newer Product"
        );

        flushAndClear();

        updateCreatedAt(
                older,
                "2026-03-01T00:00:00Z"
        );
        updateCreatedAt(
                newer,
                "2026-03-02T00:00:00Z"
        );

        entityManager.clear();

        PageResult<Product> result =
                searchActive("   ");

        assertIdsInOrder(result, newer, older);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void shouldPaginateAcrossMatchPriorityBoundaries() {
        Product exact = saveActiveProduct(
                "PSB-PAGE-TARGET",
                "Exact SKU Product"
        );
        Product olderPrefix = saveActiveProduct(
                "PSB-PAGE-PREFIX-OLDER",
                "PSB-PAGE-TARGET Basic"
        );
        Product newerPrefix = saveActiveProduct(
                "PSB-PAGE-PREFIX-NEWER",
                "PSB-PAGE-TARGET Pro"
        );
        Product olderContains = saveActiveProduct(
                "PSB-PAGE-CONTAINS-OLDER",
                "Accessory for PSB-PAGE-TARGET A"
        );
        Product newerContains = saveActiveProduct(
                "PSB-PAGE-CONTAINS-NEWER",
                "Accessory for PSB-PAGE-TARGET B"
        );

        flushAndClear();

        updateCreatedAt(exact, "2026-03-01T00:00:00Z");
        updateCreatedAt(
                olderPrefix,
                "2026-03-02T00:00:00Z"
        );
        updateCreatedAt(
                newerPrefix,
                "2026-03-03T00:00:00Z"
        );
        updateCreatedAt(
                olderContains,
                "2026-03-04T00:00:00Z"
        );
        updateCreatedAt(
                newerContains,
                "2026-03-05T00:00:00Z"
        );

        entityManager.clear();

        PageResult<Product> firstPage =
                searchActive("PSB-PAGE-TARGET", 0, 2);
        PageResult<Product> secondPage =
                searchActive("PSB-PAGE-TARGET", 1, 2);
        PageResult<Product> lastPage =
                searchActive("PSB-PAGE-TARGET", 2, 2);

        assertIdsInOrder(
                firstPage,
                exact,
                newerPrefix
        );
        assertIdsInOrder(
                secondPage,
                olderPrefix,
                newerContains
        );
        assertIdsInOrder(lastPage, olderContains);

        assertPageMetadata(firstPage, 0, 5, 3, true);
        assertPageMetadata(secondPage, 1, 5, 3, true);
        assertPageMetadata(lastPage, 2, 5, 3, false);
    }


    private PageResult<Product> searchActive(String keyword) {
        return searchActive(keyword, 0, 20);
    }

    private PageResult<Product> searchActive(
            String keyword,
            int page,
            int size
    ) {
        return search(
                ProductSearchCriteria.activeOnly(keyword),
                page,
                size
        );
    }

    private PageResult<Product> search(
            ProductSearchCriteria criteria,
            int page,
            int size
    ) {
        return productRepository.search(
                criteria,
                new PageQuery(page, size)
        );
    }

    private Product saveActiveProduct(
            String sku,
            String name
    ) {
        return productRepository.save(
                newProduct(sku, name)
        );
    }

    private Product saveInactiveProduct(
            String sku,
            String name
    ) {
        Product product = newProduct(sku, name);
        product.deactivate();

        return productRepository.save(product);
    }

    private Product newProduct(
            String sku,
            String name
    ) {
        return Product.create(
                sku,
                name,
                "PS-B2 relevance fixture",
                PRICE,
                10
        );
    }

    private void updateCreatedAt(
            Product product,
            String timestamp
    ) {
        int affectedRows = jdbcTemplate.update(
                """
                UPDATE products
                SET created_at = ?
                WHERE id = ?
                """,
                Timestamp.from(Instant.parse(timestamp)),
                product.getId()
        );

        assertThat(affectedRows).isEqualTo(1);
    }

    private void assertIdsInOrder(
            PageResult<Product> result,
            Product... expectedProducts
    ) {
        List<UUID> expectedIds = Arrays
                .stream(expectedProducts)
                .map(Product::getId)
                .toList();

        assertThat(result.content())
                .extracting(Product::getId)
                .containsExactlyElementsOf(expectedIds);
    }

    private void assertPageMetadata(
            PageResult<Product> result,
            int expectedPage,
            long expectedTotalElements,
            int expectedTotalPages,
            boolean expectedHasNext
    ) {
        assertThat(result.page()).isEqualTo(expectedPage);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements())
                .isEqualTo(expectedTotalElements);
        assertThat(result.totalPages())
                .isEqualTo(expectedTotalPages);
        assertThat(result.hasNext())
                .isEqualTo(expectedHasNext);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}