package com.lul.shop.catalog.application;


import com.lul.shop.catalog.application.dto.CreateProductCommand;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.application.dto.UpdateProductCommand;
import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final ProductRepository productRepository;

    public CatalogService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }


    @Transactional
    public ProductResult createProduct(CreateProductCommand command) {
        Product product = Product.create(command.sku(),
                command.name(),
                command.description(),
                command.price(),
                command.stockQuantity()
        );

        if(productRepository.existsBySku(product.getSku())) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_SKU_ALREADY_EXISTS);
        }

        Product savedProduct = productRepository.save(product);

        return toResult(savedProduct);
    }


    @Transactional
    public ProductResult updateProduct(UUID productId, UpdateProductCommand command) {
        Product product = getProductOrThrow(productId);

        product.updateDetails(
                command.sku(),
                command.name(),
                command.description(),
                command.price(),
                command.stockQuantity()
        );

        if (productRepository.existsOtherProductWithSku(product.getSku(), productId)) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_SKU_ALREADY_EXISTS);
        }

        Product savedProduct = productRepository.save(product);

        return toResult(savedProduct);
    }

    @Transactional
    public void deactivateProduct(UUID productId) {
        Product product = getProductOrThrow(productId);

        product.deactivate();

        productRepository.save(product);
    }

    public ProductResult getProduct(UUID productId) {
        Product product = getProductOrThrow(productId);

        return toResult(product);
    }

    public PageResult<ProductResult> searchProducts(ProductSearchCriteria criteria, PageQuery pageQuery) {

        return  productRepository.search(criteria,pageQuery).map(this::toResult);
    }

    public PageResult<ProductResult> searchActiveProducts(String keyword, PageQuery pageQuery) {
        ProductSearchCriteria criteria = ProductSearchCriteria.activeOnly(keyword);

        return productRepository.search(criteria,pageQuery).map(this::toResult);
    }

    private Product getProductOrThrow(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(CatalogErrorCode.PRODUCT_NOT_FOUND));
    }


    private ProductResult toResult(Product product) {
        return new ProductResult(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getStatus(),
                product.getImageUrl(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }


    public ProductResult getActiveProduct(UUID productId) {
        Product product = getProductOrThrow(productId);

        if(!product.isActive()) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_NOT_ACTIVE);
        }

        return toResult(product);
    }


}
