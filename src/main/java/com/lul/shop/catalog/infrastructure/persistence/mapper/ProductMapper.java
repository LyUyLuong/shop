package com.lul.shop.catalog.infrastructure.persistence.mapper;

import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.infrastructure.persistence.entity.ProductJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    Product toDomain(ProductJpaEntity entity);

    ProductJpaEntity toEntity(Product product);
}