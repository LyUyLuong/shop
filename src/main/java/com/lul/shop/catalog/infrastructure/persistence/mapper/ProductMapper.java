package com.lul.shop.catalog.infrastructure.persistence.mapper;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import org.mapstruct.Mapper;

import java.util.Objects;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    default Product toDomain(ProductJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        long version = Objects.requireNonNull(
                entity.getVersion(),
                "persisted product version must not be null"
        );

        return new Product(
                entity.getId(),
                version,
                entity.getSku(),
                entity.getName(),
                entity.getDescription(),
                entity.getPrice(),
                entity.getStockQuantity(),
                entity.getStatus(),
                entity.getImageKey(),
                entity.getImageUrl(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    default ProductJpaEntity toEntity(Product product) {
        if (product == null) {
            return null;
        }

        ProductJpaEntity entity = new ProductJpaEntity();
        entity.setId(product.getId());

        if (product.getCreatedAt() != null) {
            entity.setVersion(product.getVersion());
        }

        entity.setSku(product.getSku());
        entity.setName(product.getName());
        entity.setDescription(product.getDescription());
        entity.setPrice(product.getPrice());
        entity.setStockQuantity(product.getStockQuantity());
        entity.setStatus(product.getStatus());
        entity.setImageKey(product.getImageKey());
        entity.setImageUrl(product.getImageUrl());
        entity.setCreatedAt(product.getCreatedAt());
        entity.setUpdatedAt(product.getUpdatedAt());

        return entity;
    }
}