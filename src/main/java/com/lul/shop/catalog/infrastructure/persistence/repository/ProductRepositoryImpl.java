package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import com.lul.shop.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class ProductRepositoryImpl implements ProductRepository {

    private static final String PRODUCT_SKU_UNIQUE_INDEX =
            "idx_products_sku_lower";

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
    public boolean increaseStock(UUID productId, int quantity) {
        Objects.requireNonNull(
                productId,
                "productId must not be null"
        );

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "quantity must be greater than 0"
            );
        }

        int affectedRows = productJpaRepository.increaseStock(
                productId,
                quantity
        );

        return affectedRows == 1;
    }

    @Override
    @Transactional
    public Product save(Product product) {
        ProductJpaEntity entity = productMapper.toEntity(product);

        try {
            ProductJpaEntity savedEntity =
                    productJpaRepository.saveAndFlush(entity);

            return productMapper.toDomain(savedEntity);
        } catch (OptimisticLockingFailureException exception) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_VERSION_CONFLICT
            );
        } catch (DataIntegrityViolationException exception) {
            if (isConstraintViolation(
                    exception,
                    PRODUCT_SKU_UNIQUE_INDEX
            )) {
                throw new BusinessException(
                        CatalogErrorCode.PRODUCT_SKU_ALREADY_EXISTS
                );
            }

            throw exception;
        }
    }

    @Override
    public PageResult<Product> search(ProductSearchCriteria criteria, PageQuery pageQuery) {
        return productQueryRepository.search(criteria, pageQuery)
                .map(productMapper::toDomain);
    }

    private boolean isConstraintViolation(
            Throwable exception,
            String expectedConstraint
    ) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && expectedConstraint.equals(
                    violation.getConstraintName()
            )) {
                return true;
            }

            if (current == current.getCause()) {
                break;
            }

            current = current.getCause();
        }

        return false;
    }

    private Optional<String> normalizeSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(sku.trim().toUpperCase(Locale.ROOT));
    }
}
