package com.lul.shop.catalog.infrastructure.persistence.repository;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import com.lul.shop.catalog.infrastructure.persistence.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRepositoryImplExceptionTranslationTest {

    @Mock
    private ProductJpaRepository productJpaRepository;

    @Mock
    private ProductQueryRepository productQueryRepository;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductRepositoryImpl productRepository;

    @Test
    void shouldPreserveUnknownDataIntegrityFailure() {
        Product product = Product.create(
                "SKU-UNKNOWN-CONSTRAINT",
                "Unknown Constraint Product",
                null,
                new BigDecimal("100000.00"),
                1
        );

        ProductJpaEntity entity = new ProductJpaEntity();

        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "unknown product constraint"
                );

        when(productMapper.toEntity(product)).thenReturn(entity);
        when(productJpaRepository.saveAndFlush(entity))
                .thenThrow(failure);

        assertThatThrownBy(() -> productRepository.save(product))
                .isSameAs(failure);
    }
}
