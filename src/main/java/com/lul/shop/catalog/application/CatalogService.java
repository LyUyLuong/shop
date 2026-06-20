package com.lul.shop.catalog.application;


import com.lul.shop.catalog.application.dto.*;
import com.lul.shop.catalog.application.port.ProductImageStorage;
import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private static final long MAX_PRODUCT_IMAGE_SIZE_BYTES = 5 * 1024 * 1024;

    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of(
            "jpg",
            "jpeg",
            "png",
            "webp"
    );


    private final ProductRepository productRepository;
    private final ProductImageStorage productImageStorage;

    public CatalogService(ProductRepository productRepository,
                          ProductImageStorage productImageStorage) {
        this.productRepository = productRepository;
        this.productImageStorage = productImageStorage;
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

    @Transactional
    public ProductResult uploadProductImage(UUID productId, UploadProductImageCommand command) {
        Product product = getProductOrThrow(productId);

        validateProductImage(command);

        StoredProductImage storedImage = productImageStorage.store(productId, command);

        product.updateImage(storedImage.imageKey(), storedImage.imageUrl());

        Product savedProduct = productRepository.save(product);

        return toResult(savedProduct);
    }


    @Transactional
    public boolean decreaseStockIfEnough(UUID productId, int quantity) {
        return productRepository.decreaseStockIfEnough(productId, quantity);
    }


    private void validateProductImage(UploadProductImageCommand command) {

        if (command == null) {
            throw invalidImage("image file is required");
        }

        if (command.content() == null) {
            throw invalidImage("image content is required");
        }

        if (command.size() <= 0) {
            throw invalidImage("image file must not be empty");
        }

        if (command.size() > MAX_PRODUCT_IMAGE_SIZE_BYTES) {
            throw invalidImage("image file must not exceed 5MB");
        }

        String contentType = normalizeContentType(command.contentType());
        if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw invalidImage("only JPEG, PNG, and WebP images are allowed");
        }

        String extension = extractExtension(command.originalFilename());
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw invalidImage("image extension must be jpg, jpeg, png, or webp");
        }
    }

    private BusinessException invalidImage(String detail) {
        return new BusinessException(CatalogErrorCode.INVALID_PRODUCT_IMAGE, detail);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw invalidImage("image content type is required");
        }

        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            throw invalidImage("image filename is required");
        }

        String trimmedFilename = filename.trim();
        int lastDotIndex = trimmedFilename.lastIndexOf('.');

        if (lastDotIndex < 0 || lastDotIndex == trimmedFilename.length() - 1) {
            throw invalidImage("image filename must have an extension");
        }

        return trimmedFilename.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
    }


}
