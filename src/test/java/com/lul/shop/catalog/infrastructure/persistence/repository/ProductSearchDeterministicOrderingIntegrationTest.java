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
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductSearchDeterministicOrderingIntegrationTest
        extends PostgresIntegrationTest {

    private static final BigDecimal PRICE =
            new BigDecimal("100000.00");
    private static final Instant TIED_CREATED_AT =
            Instant.parse("2026-04-01T00:00:00Z");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldUseIdDescendingForBlankKeywordAcrossAdjacentPages() {
        List<Product> products = saveFiveProducts(1, false);

        tieCreatedAt(products);

        assertStableTraversal(
                null,
                descending(products)
        );
    }

    @Test
    void shouldUseIdDescendingWithinSameKeywordPriorityAcrossAdjacentPages() {
        List<Product> products = saveFiveProducts(11, true);

        tieCreatedAt(products);

        assertStableTraversal(
                "PSB4-TIE",
                descending(products)
        );
    }

    private List<Product> saveFiveProducts(
            long firstSequence,
            boolean keywordFixture
    ) {
        return LongStream.range(
                        firstSequence,
                        firstSequence + 5
                )
                .mapToObj(sequence -> saveActiveProduct(
                        sequence,
                        keywordFixture
                ))
                .toList();
    }

    private Product saveActiveProduct(
            long sequence,
            boolean keywordFixture
    ) {
        UUID id = UUID.fromString(
                "00000000-0000-0000-0000-"
                        + String.format("%012d", sequence)
        );
        String name = keywordFixture
                ? "Accessory for PSB4-TIE Product " + sequence
                : "Browse Product " + sequence;

        return productRepository.save(
                new Product(
                        id,
                        0L,
                        "PSB4-SKU-" + sequence,
                        name,
                        "PS-B4 deterministic-order fixture",
                        PRICE,
                        10,
                        ProductStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null
                )
        );
    }

    private void tieCreatedAt(List<Product> products) {
        flushAndClear();

        for (Product product : products) {
            int affectedRows = jdbcTemplate.update(
                    """
                    UPDATE products
                    SET created_at = ?
                    WHERE id = ?
                    """,
                    Timestamp.from(TIED_CREATED_AT),
                    product.getId()
            );

            assertThat(affectedRows).isEqualTo(1);
        }

        entityManager.clear();
    }

    private void assertStableTraversal(
            String keyword,
            List<Product> expectedOrder
    ) {
        List<UUID> expectedIds = expectedOrder.stream()
                .map(Product::getId)
                .toList();
        List<List<UUID>> expectedPages = List.of(
                expectedIds.subList(0, 2),
                expectedIds.subList(2, 4),
                expectedIds.subList(4, 5)
        );

        List<List<UUID>> firstTraversal =
                readPageIds(keyword);
        List<List<UUID>> repeatedTraversal =
                readPageIds(keyword);

        assertThat(firstTraversal)
                .containsExactlyElementsOf(expectedPages);
        assertThat(repeatedTraversal)
                .isEqualTo(firstTraversal);

        List<UUID> allIds = firstTraversal.stream()
                .flatMap(List::stream)
                .toList();

        assertThat(allIds)
                .containsExactlyElementsOf(expectedIds)
                .doesNotHaveDuplicates();
    }

    private List<List<UUID>> readPageIds(String keyword) {
        PageResult<Product> firstPage =
                searchActive(keyword, 0, 2);
        PageResult<Product> secondPage =
                searchActive(keyword, 1, 2);
        PageResult<Product> lastPage =
                searchActive(keyword, 2, 2);

        assertPageMetadata(firstPage, 0, 2, true);
        assertPageMetadata(secondPage, 1, 2, true);
        assertPageMetadata(lastPage, 2, 1, false);

        return List.of(
                ids(firstPage),
                ids(secondPage),
                ids(lastPage)
        );
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

    private List<UUID> ids(PageResult<Product> result) {
        return result.content().stream()
                .map(Product::getId)
                .toList();
    }

    private void assertPageMetadata(
            PageResult<Product> result,
            int expectedPage,
            int expectedContentSize,
            boolean expectedHasNext
    ) {
        assertThat(result.content())
                .hasSize(expectedContentSize);
        assertThat(result.page()).isEqualTo(expectedPage);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isEqualTo(expectedHasNext);
    }

    private List<Product> descending(List<Product> products) {
        return List.of(
                products.get(4),
                products.get(3),
                products.get(2),
                products.get(1),
                products.get(0)
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
