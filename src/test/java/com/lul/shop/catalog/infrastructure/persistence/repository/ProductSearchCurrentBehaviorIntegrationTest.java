package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
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
class ProductSearchCurrentBehaviorIntegrationTest
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
    void shouldMatchExactPrefixSuffixAndContainsAcrossSkuAndNameIgnoringCase() {
        Product nameMatch = saveActiveProduct(
                "PSA-NAME-001",
                "Alpha Mechanical Keyboard"
        );
        Product skuMatch = saveActiveProduct(
                "PSA-ALPHA-SKU",
                "USB Cable"
        );
        saveActiveProduct(
                "PSA-UNRELATED-001",
                "Wireless Mouse"
        );

        flushAndClear();

        assertProductIds(
                searchActive("alpha mechanical keyboard"),
                nameMatch
        );
        assertProductIds(
                searchActive("psa-alpha-sku"),
                skuMatch
        );
        assertProductIds(
                searchActive("ALPHA"),
                nameMatch,
                skuMatch
        );
        assertProductIds(
                searchActive("keyboard"),
                nameMatch
        );
        assertProductIds(
                searchActive("mechanical"),
                nameMatch
        );
    }

    @Test
    void shouldTreatNullEmptyAndWhitespaceKeywordAsNoKeywordFilter() {
        Product first = saveActiveProduct(
                "PSA-BLANK-001",
                "First Active Product"
        );
        Product second = saveActiveProduct(
                "PSA-BLANK-002",
                "Second Active Product"
        );
        saveInactiveProduct(
                "PSA-BLANK-003",
                "Inactive Product"
        );

        flushAndClear();

        assertProductIds(searchActive(null), first, second);
        assertProductIds(searchActive(""), first, second);
        assertProductIds(searchActive("   "), first, second);
    }

    @Test
    void shouldTreatPercentAndUnderscoreAsLikeWildcards() {
        Product first = saveActiveProduct(
                "PSA-WILDCARD-001",
                "Mechanical Keyboard"
        );
        Product second = saveActiveProduct(
                "PSA-WILDCARD-002",
                "Wireless Mouse"
        );

        flushAndClear();

        assertProductIds(searchActive("%"), first, second);
        assertProductIds(searchActive("_"), first, second);
    }

    @Test
    void shouldStillExcludeInactiveProductsWhenWildcardMatchesEverything() {
        Product active = saveActiveProduct(
                "PSA-WILDCARD-ACTIVE",
                "Active Product"
        );
        saveInactiveProduct(
                "PSA-WILDCARD-INACTIVE",
                "Inactive Product"
        );

        flushAndClear();

        assertProductIds(searchActive("%"), active);
    }

    @Test
    void shouldMatchVietnameseCaseWithoutFoldingAccents() {
        Product vietnamese = saveActiveProduct(
                "PSA-VIETNAMESE-001",
                "Điện Thoại Thông Minh"
        );
        Product unicode = saveActiveProduct(
                "PSA-UNICODE-001",
                "Japanese 日本語 Keyboard"
        );

        flushAndClear();

        assertProductIds(searchActive("ĐIỆN"), vietnamese);
        assertProductIds(searchActive("điện"), vietnamese);
        assertProductIds(searchActive("dien"));
        assertProductIds(searchActive("日本"), unicode);
    }

    @Test
    void shouldBindSqlLookingKeywordAsData() {
        saveActiveProduct(
                "PSA-INJECTION-001",
                "Ordinary Product"
        );

        flushAndClear();

        assertProductIds(searchActive("' OR 1=1 --"));
    }

    @Test
    void shouldAcceptVeryLongKeywordWithoutApplicationLengthValidation() {
        saveActiveProduct(
                "PSA-LONG-001",
                "Ordinary Product"
        );

        flushAndClear();

        assertProductIds(searchActive("x".repeat(5000)));
    }

    @Test
    void shouldReturnFirstLastAndEmptyPagesWithCurrentMetadata() {
        Product oldest = saveActiveProduct(
                "PSA-PAGE-001",
                "Oldest Product"
        );
        Product older = saveActiveProduct(
                "PSA-PAGE-002",
                "Older Product"
        );
        Product middle = saveActiveProduct(
                "PSA-PAGE-003",
                "Middle Product"
        );
        Product newer = saveActiveProduct(
                "PSA-PAGE-004",
                "Newer Product"
        );
        Product newest = saveActiveProduct(
                "PSA-PAGE-005",
                "Newest Product"
        );

        flushAndClear();

        updateCreatedAt(oldest, "2026-01-01T00:00:00Z");
        updateCreatedAt(older, "2026-01-02T00:00:00Z");
        updateCreatedAt(middle, "2026-01-03T00:00:00Z");
        updateCreatedAt(newer, "2026-01-04T00:00:00Z");
        updateCreatedAt(newest, "2026-01-05T00:00:00Z");

        entityManager.clear();

        PageResult<Product> firstPage =
                searchActive(null, 0, 2);
        PageResult<Product> secondPage =
                searchActive(null, 1, 2);
        PageResult<Product> lastPage =
                searchActive(null, 2, 2);
        PageResult<Product> emptyPage =
                searchActive(null, 3, 2);

        assertIdsInOrder(firstPage, newest, newer);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.hasNext()).isTrue();

        assertIdsInOrder(secondPage, middle, older);
        assertThat(secondPage.hasNext()).isTrue();

        assertIdsInOrder(lastPage, oldest);
        assertThat(lastPage.hasNext()).isFalse();

        assertThat(emptyPage.content()).isEmpty();
        assertThat(emptyPage.totalElements()).isEqualTo(5);
        assertThat(emptyPage.totalPages()).isEqualTo(3);
        assertThat(emptyPage.hasNext()).isFalse();
    }

    private PageResult<Product> searchActive(String keyword) {
        return searchActive(keyword, 0, 20);
    }

    private PageResult<Product> searchActive(
            String keyword,
            int page,
            int size
    ) {
        return productRepository.search(
                ProductSearchCriteria.activeOnly(keyword),
                new PageQuery(page, size)
        );
    }

    private Product saveActiveProduct(
            String sku,
            String name
    ) {
        return productRepository.save(newProduct(sku, name));
    }

    private Product saveInactiveProduct(
            String sku,
            String name
    ) {
        Product product = newProduct(sku, name);
        product.deactivate();

        return productRepository.save(product);
    }

    private Product newProduct(String sku, String name) {
        return Product.create(
                sku,
                name,
                "PS-A current-query baseline fixture",
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

    private void assertProductIds(
            PageResult<Product> result,
            Product... expectedProducts
    ) {
        List<UUID> expectedIds = Arrays.stream(expectedProducts)
                .map(Product::getId)
                .toList();

        assertThat(result.content())
                .extracting(Product::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds);

        assertThat(result.totalElements())
                .isEqualTo(expectedIds.size());
    }

    private void assertIdsInOrder(
            PageResult<Product> result,
            Product... expectedProducts
    ) {
        List<UUID> expectedIds = Arrays.stream(expectedProducts)
                .map(Product::getId)
                .toList();

        assertThat(result.content())
                .extracting(Product::getId)
                .containsExactlyElementsOf(expectedIds);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}