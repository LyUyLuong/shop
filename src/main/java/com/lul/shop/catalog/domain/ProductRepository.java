package com.lul.shop.catalog.domain;

import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

    Optional<Product> findById(UUID id);

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    boolean existsOtherProductWithSku(String sku, UUID currentProductId);

    boolean decreaseStockIfEnough(UUID productId, int quantity);

    boolean increaseStock(UUID productId, int quantity);

    Product save(Product product);

    PageResult<Product> search(ProductSearchCriteria criteria, PageQuery pageQuery);
}