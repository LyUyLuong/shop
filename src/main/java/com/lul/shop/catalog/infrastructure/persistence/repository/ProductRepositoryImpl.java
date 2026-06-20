package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import com.lul.shop.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;
    private final ProductQueryRepository productQueryRepository;
    private final ProductMapper productMapper;

    public ProductRepositoryImpl(ProductJpaRepository productJpaRepository,
                                 ProductQueryRepository productQueryRepository,
                                 ProductMapper productMapper) {
        this.productJpaRepository = productJpaRepository;
        this.productQueryRepository = productQueryRepository;
        this.productMapper = productMapper;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return productJpaRepository.findById(id)
                .map(productMapper::toDomain);
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return normalizeSku(sku)
                .flatMap(productJpaRepository::findBySku)
                .map(productMapper::toDomain);
    }

    @Override
    public boolean existsBySku(String sku) {
        return normalizeSku(sku)
                .map(productJpaRepository::existsBySku)
                .orElse(false);
    }

    @Override
    public boolean existsOtherProductWithSku(String sku, UUID currentProductId) {
        Objects.requireNonNull(currentProductId, "currentProductId must not be null");

        return normalizeSku(sku)
                .map(normalizedSku -> productJpaRepository.existsOtherProductWithSku(normalizedSku, currentProductId))
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean decreaseStockIfEnough(UUID productId, int quantity) {
        Objects.requireNonNull(productId, "productId must not be null");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        int affectedRows = productJpaRepository.decreaseStockIfEnough(
                productId,
                quantity,
                ProductStatus.ACTIVE
        );

        return affectedRows == 1;
    }

    @Override
    @Transactional
    public Product save(Product product) {
        ProductJpaEntity entity = productMapper.toEntity(product);
        ProductJpaEntity savedEntity = productJpaRepository.save(entity);

        return productMapper.toDomain(savedEntity);
    }

    @Override
    public PageResult<Product> search(ProductSearchCriteria criteria, PageQuery pageQuery) {
        return productQueryRepository.search(criteria, pageQuery)
                .map(productMapper::toDomain);
    }

    private Optional<String> normalizeSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(sku.trim().toUpperCase(Locale.ROOT));
    }
}