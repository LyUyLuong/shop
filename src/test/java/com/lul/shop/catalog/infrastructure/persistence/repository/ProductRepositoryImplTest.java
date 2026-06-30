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
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class ProductRepositoryImplTest extends PostgresIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldSaveAndFindProductByIdAndSkuIgnoringCase() {
        Product saved = saveProduct(
                " tc-find-001 ",
                "Test Product",
                "Product used for find test",
                "100000.00",
                7
        );

        flushAndClear();

        Product foundById = productRepository.findById(saved.getId()).orElseThrow();
        Product foundBySku = productRepository.findBySku("tc-find-001").orElseThrow();

        assertThat(foundById.getId()).isEqualTo(saved.getId());
        assertThat(foundById.getSku()).isEqualTo("TC-FIND-001");
        assertThat(foundById.getName()).isEqualTo("Test Product");

        assertThat(foundBySku.getId()).isEqualTo(saved.getId());
        assertThat(foundBySku.getSku()).isEqualTo("TC-FIND-001");
    }

    @Test
    void shouldCheckSkuExistenceIgnoringCase() {
        Product target = saveProduct(
                "TC-EXISTS-001",
                "Existing Product",
                "Product used for sku existence test",
                "120000.00",
                5
        );

        Product another = saveProduct(
                "TC-EXISTS-002",
                "Another Product",
                "Another product",
                "130000.00",
                3
        );

        flushAndClear();

        assertThat(productRepository.existsBySku("tc-exists-001")).isTrue();
        assertThat(productRepository.existsBySku("missing-sku")).isFalse();
        assertThat(productRepository.existsBySku(" ")).isFalse();

        assertThat(productRepository.existsOtherProductWithSku("tc-exists-001", target.getId())).isFalse();
        assertThat(productRepository.existsOtherProductWithSku("tc-exists-001", another.getId())).isTrue();
    }

    @Test
    void shouldSearchProductsByKeywordStatusAndPriceRange() {
        Product matching = saveProduct(
                "TC-IPHONE-001",
                "Apple iPhone 15 Pro",
                "Matching product",
                "25990000.00",
                8
        );

        saveProduct(
                "TC-GALAXY-001",
                "Samsung Galaxy S24",
                "Different keyword",
                "22990000.00",
                6
        );

        saveProduct(
                "TC-IPHONE-CHEAP",
                "Apple iPhone Case",
                "Keyword matches but price is outside range",
                "99000.00",
                20
        );

        saveInactiveProduct(
                "TC-IPHONE-INACTIVE",
                "Apple iPhone Old",
                "Keyword matches but status is inactive",
                "12990000.00",
                2
        );

        flushAndClear();

        ProductSearchCriteria criteria = new ProductSearchCriteria(
                "iphone",
                ProductStatus.ACTIVE,
                new BigDecimal("20000000.00"),
                new BigDecimal("30000000.00")
        );

        PageResult<Product> result = productRepository.search(criteria, new PageQuery(0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.content())
                .extracting(Product::getId)
                .containsExactly(matching.getId());
    }

    @Test
    void shouldDecreaseStockWhenProductIsActiveAndStockIsEnough() {
        Product product = saveProduct(
                "TC-STOCK-001",
                "Stock Product",
                "Product used for stock decrease test",
                "150000.00",
                5
        );

        flushAndClear();

        boolean decreased = productRepository.decreaseStockIfEnough(product.getId(), 3);

        flushAndClear();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();

        assertThat(decreased).isTrue();
        assertThat(reloaded.getStockQuantity()).isEqualTo(2);
    }

    @Test
    void shouldNotDecreaseStockWhenStockIsNotEnough() {
        Product product = saveProduct(
                "TC-STOCK-002",
                "Low Stock Product",
                "Product used for insufficient stock test",
                "150000.00",
                2
        );

        flushAndClear();

        boolean decreased = productRepository.decreaseStockIfEnough(product.getId(), 3);

        flushAndClear();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();

        assertThat(decreased).isFalse();
        assertThat(reloaded.getStockQuantity()).isEqualTo(2);
    }

    @Test
    void shouldNotDecreaseStockWhenProductIsInactive() {
        Product product = saveInactiveProduct(
                "TC-STOCK-INACTIVE",
                "Inactive Stock Product",
                "Inactive product should not be decreased",
                "150000.00",
                5
        );

        flushAndClear();

        boolean decreased = productRepository.decreaseStockIfEnough(product.getId(), 1);

        flushAndClear();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();

        assertThat(decreased).isFalse();
        assertThat(reloaded.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void shouldRejectNonPositiveStockDecreaseQuantity() {
        assertThatThrownBy(() -> productRepository.decreaseStockIfEnough(UUID.randomUUID(), 0))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("quantity must be greater than 0")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private Product saveProduct(String sku,
                                String name,
                                String description,
                                String price,
                                int stockQuantity) {
        return productRepository.save(Product.create(
                sku,
                name,
                description,
                new BigDecimal(price),
                stockQuantity
        ));
    }

    private Product saveInactiveProduct(String sku,
                                        String name,
                                        String description,
                                        String price,
                                        int stockQuantity) {
        Product product = Product.create(
                sku,
                name,
                description,
                new BigDecimal(price),
                stockQuantity
        );
        product.deactivate();

        return productRepository.save(product);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}