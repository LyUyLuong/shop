package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.catalog.domain.ProductSearchSlice;
import com.lul.shop.catalog.domain.ProductSearchWindow;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.test.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductCursorPaginationIntegrationTest
        extends PostgresIntegrationTest {

    private static final BigDecimal PRICE =
            new BigDecimal("100000.00");

    private static final Instant TIED_CREATED_AT =
            Instant.parse("2026-05-01T00:00:00Z");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldTraverseStablePublicBrowseWithoutDuplicatesOrOmissions() {
        List<Product> activeProducts = List.of(
                saveProduct(
                        1,
                        "PSB5-BROWSE-1",
                        "Browse Product 1",
                        ProductStatus.ACTIVE
                ),
                saveProduct(
                        2,
                        "PSB5-BROWSE-2",
                        "Browse Product 2",
                        ProductStatus.ACTIVE
                ),
                saveProduct(
                        3,
                        "PSB5-BROWSE-3",
                        "Browse Product 3",
                        ProductStatus.ACTIVE
                ),
                saveProduct(
                        4,
                        "PSB5-BROWSE-4",
                        "Browse Product 4",
                        ProductStatus.ACTIVE
                ),
                saveProduct(
                        5,
                        "PSB5-BROWSE-5",
                        "Browse Product 5",
                        ProductStatus.ACTIVE
                )
        );

        Product inactiveProduct = saveProduct(
                6,
                "PSB5-BROWSE-INACTIVE",
                "Inactive Browse Product",
                ProductStatus.INACTIVE
        );

        tieCreatedAt(
                List.of(
                        activeProducts.get(0),
                        activeProducts.get(1),
                        activeProducts.get(2),
                        activeProducts.get(3),
                        activeProducts.get(4),
                        inactiveProduct
                )
        );

        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        Traversal firstTraversal =
                traverse(criteria, 2);

        Traversal repeatedTraversal =
                traverse(criteria, 2);

        List<UUID> expectedIds = List.of(
                id(5),
                id(4),
                id(3),
                id(2),
                id(1)
        );

        assertThat(firstTraversal.ids())
                .containsExactlyElementsOf(expectedIds)
                .doesNotHaveDuplicates();

        assertThat(firstTraversal.pageSizes())
                .containsExactly(2, 2, 1);

        assertThat(repeatedTraversal)
                .isEqualTo(firstTraversal);

        assertThat(firstTraversal.ids())
                .doesNotContain(inactiveProduct.getId());
    }

    @Test
    void shouldTraverseRankedSearchAcrossPriorityBoundaries() {
        String keyword = "PSB5-RANK-TARGET";

        Product exact = saveProduct(
                11,
                keyword,
                "Exact SKU Product",
                ProductStatus.ACTIVE
        );

        Product olderPrefix = saveProduct(
                12,
                "PSB5-RANK-PREFIX-12",
                keyword + " Basic",
                ProductStatus.ACTIVE
        );

        Product newerPrefix = saveProduct(
                13,
                "PSB5-RANK-PREFIX-13",
                keyword + " Pro",
                ProductStatus.ACTIVE
        );

        Product olderContains = saveProduct(
                14,
                "PSB5-RANK-CONTAINS-14",
                "Accessory for " + keyword + " A",
                ProductStatus.ACTIVE
        );

        Product newerContains = saveProduct(
                15,
                "PSB5-RANK-CONTAINS-15",
                "Accessory for " + keyword + " B",
                ProductStatus.ACTIVE
        );

        Product unrelated = saveProduct(
                16,
                "PSB5-RANK-UNRELATED",
                "Unrelated Product",
                ProductStatus.ACTIVE
        );

        tieCreatedAt(
                List.of(
                        exact,
                        olderPrefix,
                        newerPrefix,
                        olderContains,
                        newerContains,
                        unrelated
                )
        );

        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(keyword);

        ProductSearchSlice<Product> first =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.first(2)
                );

        assertSlice(
                first,
                true,
                exact,
                newerPrefix
        );

        ProductSearchPosition firstPosition =
                first.nextPosition().orElseThrow();

        assertThat(firstPosition.requiredMatchPriority())
                .isEqualTo(1);
        assertThat(firstPosition.id())
                .isEqualTo(newerPrefix.getId());

        ProductSearchSlice<Product> second =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.after(
                                2,
                                firstPosition
                        )
                );

        assertSlice(
                second,
                true,
                olderPrefix,
                newerContains
        );

        ProductSearchPosition secondPosition =
                second.nextPosition().orElseThrow();

        assertThat(secondPosition.requiredMatchPriority())
                .isEqualTo(2);
        assertThat(secondPosition.id())
                .isEqualTo(newerContains.getId());

        ProductSearchSlice<Product> terminal =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.after(
                                2,
                                secondPosition
                        )
                );

        assertSlice(
                terminal,
                false,
                olderContains
        );

        List<UUID> traversedIds = new ArrayList<>();
        traversedIds.addAll(ids(first));
        traversedIds.addAll(ids(second));
        traversedIds.addAll(ids(terminal));

        assertThat(traversedIds)
                .containsExactly(
                        exact.getId(),
                        newerPrefix.getId(),
                        olderPrefix.getId(),
                        newerContains.getId(),
                        olderContains.getId()
                )
                .doesNotHaveDuplicates()
                .doesNotContain(unrelated.getId());
    }

    @Test
    void shouldContinueAfterAnchorProductIsDeleted() {
        Product oldest = saveProduct(
                21,
                "PSB5-DELETE-21",
                "Delete Product 21",
                ProductStatus.ACTIVE
        );

        Product older = saveProduct(
                22,
                "PSB5-DELETE-22",
                "Delete Product 22",
                ProductStatus.ACTIVE
        );

        Product anchor = saveProduct(
                23,
                "PSB5-DELETE-23",
                "Delete Product 23",
                ProductStatus.ACTIVE
        );

        Product newest = saveProduct(
                24,
                "PSB5-DELETE-24",
                "Delete Product 24",
                ProductStatus.ACTIVE
        );

        tieCreatedAt(
                List.of(
                        oldest,
                        older,
                        anchor,
                        newest
                )
        );

        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        ProductSearchSlice<Product> first =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.first(2)
                );

        assertSlice(
                first,
                true,
                newest,
                anchor
        );

        ProductSearchPosition position =
                first.nextPosition().orElseThrow();

        int deletedRows = jdbcTemplate.update(
                """
                DELETE FROM products
                WHERE id = ?
                """,
                anchor.getId()
        );

        assertThat(deletedRows).isEqualTo(1);
        entityManager.clear();

        ProductSearchSlice<Product> terminal =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.after(
                                2,
                                position
                        )
                );

        assertSlice(
                terminal,
                false,
                older,
                oldest
        );
    }

    @Test
    void shouldReapplyActiveVisibilityAfterLiveStatusAndInsertChanges() {
        Product newest = saveProduct(
                35,
                "PSB5-LIVE-35",
                "Live Product 35",
                ProductStatus.ACTIVE
        );

        Product anchor = saveProduct(
                34,
                "PSB5-LIVE-34",
                "Live Product 34",
                ProductStatus.ACTIVE
        );

        Product deactivatedAfterFirstPage = saveProduct(
                33,
                "PSB5-LIVE-33",
                "Live Product 33",
                ProductStatus.ACTIVE
        );

        Product activatedAfterFirstPage = saveProduct(
                32,
                "PSB5-LIVE-32",
                "Live Product 32",
                ProductStatus.INACTIVE
        );

        Product existingActive = saveProduct(
                31,
                "PSB5-LIVE-31",
                "Live Product 31",
                ProductStatus.ACTIVE
        );

        tieCreatedAt(
                List.of(
                        newest,
                        anchor,
                        deactivatedAfterFirstPage,
                        activatedAfterFirstPage,
                        existingActive
                )
        );

        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        ProductSearchSlice<Product> first =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.first(2)
                );

        assertSlice(
                first,
                true,
                newest,
                anchor
        );

        ProductSearchPosition position =
                first.nextPosition().orElseThrow();

        updateStatus(
                deactivatedAfterFirstPage,
                ProductStatus.INACTIVE
        );
        updateStatus(
                activatedAfterFirstPage,
                ProductStatus.ACTIVE
        );

        entityManager.clear();

        Product insertedAfterFirstPage = saveProduct(
                30,
                "PSB5-LIVE-30",
                "Inserted Live Product",
                ProductStatus.ACTIVE
        );

        tieCreatedAt(List.of(insertedAfterFirstPage));

        ProductSearchSlice<Product> terminal =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.after(
                                10,
                                position
                        )
                );

        assertSlice(
                terminal,
                false,
                activatedAfterFirstPage,
                existingActive,
                insertedAfterFirstPage
        );

        assertThat(ids(terminal))
                .doesNotContain(
                        deactivatedAfterFirstPage.getId()
                );
    }

    @Test
    void shouldReflectLiveKeywordMembershipAndPriorityChanges() {
        String keyword = "PSB5-LIVE-TARGET";

        Product exact = saveProduct(
                45,
                keyword,
                "Exact Product",
                ProductStatus.ACTIVE
        );

        Product anchor = saveProduct(
                44,
                "PSB5-LIVE-PREFIX",
                keyword + " Initial Prefix",
                ProductStatus.ACTIVE
        );

        Product removedFromMembership = saveProduct(
                43,
                "PSB5-LIVE-REMOVED",
                "Accessory for " + keyword,
                ProductStatus.ACTIVE
        );

        Product addedByName = saveProduct(
                42,
                "PSB5-LIVE-ADDED-NAME",
                "Initially Unrelated Name",
                ProductStatus.ACTIVE
        );

        Product promotedByName = saveProduct(
                41,
                "PSB5-LIVE-PROMOTED",
                "Accessory for " + keyword,
                ProductStatus.ACTIVE
        );

        Product addedBySku = saveProduct(
                40,
                "PSB5-LIVE-UNRELATED-SKU",
                "Initially Unrelated SKU Product",
                ProductStatus.ACTIVE
        );

        tieCreatedAt(
                List.of(
                        exact,
                        anchor,
                        removedFromMembership,
                        addedByName,
                        promotedByName,
                        addedBySku
                )
        );

        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(keyword);

        ProductSearchSlice<Product> first =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.first(2)
                );

        assertSlice(
                first,
                true,
                exact,
                anchor
        );

        ProductSearchPosition position =
                first.nextPosition().orElseThrow();

        updateName(
                removedFromMembership,
                "No Longer Matching"
        );
        updateName(
                addedByName,
                keyword + " Added By Name"
        );
        updateName(
                promotedByName,
                keyword + " Promoted To Prefix"
        );
        updateSku(
                addedBySku,
                "SKU-" + keyword + "-ADDED"
        );

        entityManager.clear();

        ProductSearchSlice<Product> terminal =
                productRepository.search(
                        criteria,
                        ProductSearchWindow.after(
                                10,
                                position
                        )
                );

        assertSlice(
                terminal,
                false,
                addedByName,
                promotedByName,
                addedBySku
        );

        assertThat(ids(terminal))
                .doesNotContain(
                        removedFromMembership.getId()
                );
    }

    @Test
    void shouldApplyAdminStatusCriteriaDuringCursorTraversal() {
        Product active = saveProduct(
                55,
                "PSB5-ADMIN-ACTIVE",
                "Admin Active Product",
                ProductStatus.ACTIVE
        );

        Product newerInactive = saveProduct(
                54,
                "PSB5-ADMIN-INACTIVE-54",
                "Admin Inactive Product 54",
                ProductStatus.INACTIVE
        );

        Product olderInactive = saveProduct(
                53,
                "PSB5-ADMIN-INACTIVE-53",
                "Admin Inactive Product 53",
                ProductStatus.INACTIVE
        );

        tieCreatedAt(
                List.of(
                        active,
                        newerInactive,
                        olderInactive
                )
        );

        ProductSearchCriteria criteria =
                ProductSearchCriteria.withStatus(
                        null,
                        ProductStatus.INACTIVE
                );

        Traversal traversal = traverse(criteria, 1);

        assertThat(traversal.ids())
                .containsExactly(
                        newerInactive.getId(),
                        olderInactive.getId()
                )
                .doesNotContain(active.getId());

        assertThat(traversal.pageSizes())
                .containsExactly(1, 1);
    }

    private Traversal traverse(
            ProductSearchCriteria criteria,
            int size
    ) {
        List<UUID> traversedIds = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();

        ProductSearchWindow window =
                ProductSearchWindow.first(size);

        for (int page = 0; page < 20; page++) {
            ProductSearchSlice<Product> slice =
                    productRepository.search(
                            criteria,
                            window
                    );

            assertThat(slice.content().size())
                    .isLessThanOrEqualTo(size);

            traversedIds.addAll(ids(slice));
            pageSizes.add(slice.content().size());

            if (!slice.hasNext()) {
                assertThat(slice.nextPosition()).isEmpty();

                return new Traversal(
                        traversedIds,
                        pageSizes
                );
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

    private Product saveProduct(
            long sequence,
            String sku,
            String name,
            ProductStatus status
    ) {
        return productRepository.save(
                new Product(
                        id(sequence),
                        0L,
                        sku,
                        name,
                        "PS-B5 cursor pagination fixture",
                        PRICE,
                        10,
                        status,
                        null,
                        null,
                        null,
                        null
                )
        );
    }

    private UUID id(long sequence) {
        return UUID.fromString(
                "00000000-0000-0000-0000-"
                        + String.format("%012d", sequence)
        );
    }

    private void tieCreatedAt(List<Product> products) {
        entityManager.flush();
        entityManager.clear();

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

    private void updateStatus(
            Product product,
            ProductStatus status
    ) {
        int affectedRows = jdbcTemplate.update(
                """
                UPDATE products
                SET status = ?
                WHERE id = ?
                """,
                status.name(),
                product.getId()
        );

        assertThat(affectedRows).isEqualTo(1);
    }

    private void updateName(
            Product product,
            String name
    ) {
        int affectedRows = jdbcTemplate.update(
                """
                UPDATE products
                SET name = ?
                WHERE id = ?
                """,
                name,
                product.getId()
        );

        assertThat(affectedRows).isEqualTo(1);
    }

    private void updateSku(
            Product product,
            String sku
    ) {
        int affectedRows = jdbcTemplate.update(
                """
                UPDATE products
                SET sku = ?
                WHERE id = ?
                """,
                sku,
                product.getId()
        );

        assertThat(affectedRows).isEqualTo(1);
    }

    private void assertSlice(
            ProductSearchSlice<Product> slice,
            boolean expectedHasNext,
            Product... expectedProducts
    ) {
        List<UUID> expectedIds = Arrays
                .stream(expectedProducts)
                .map(Product::getId)
                .toList();

        assertThat(ids(slice))
                .containsExactlyElementsOf(expectedIds);

        assertThat(slice.hasNext())
                .isEqualTo(expectedHasNext);

        assertThat(slice.nextPosition().isPresent())
                .isEqualTo(expectedHasNext);
    }

    private List<UUID> ids(
            ProductSearchSlice<Product> slice
    ) {
        return slice.content()
                .stream()
                .map(Product::getId)
                .toList();
    }

    private record Traversal(
            List<UUID> ids,
            List<Integer> pageSizes
    ) {

        private Traversal {
            ids = List.copyOf(ids);
            pageSizes = List.copyOf(pageSizes);
        }
    }
}