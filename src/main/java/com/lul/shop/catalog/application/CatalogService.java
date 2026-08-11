package com.lul.shop.catalog.application;

import com.lul.shop.catalog.application.dto.*;
import com.lul.shop.catalog.application.port.ProductImageStorage;
import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.catalog.domain.ProductSearchSlice;
import com.lul.shop.catalog.domain.ProductSearchWindow;
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

    private static final long MAX_PRODUCT_IMAGE_SIZE_BYTES =
            5 * 1024 * 1024;

    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "image/webp"
            );

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp"
            );

    private final ProductRepository productRepository;
    private final ProductImageStorage productImageStorage;
    private final ProductImageUrlResolver productImageUrlResolver;

    public CatalogService(
            ProductRepository productRepository,
            ProductImageStorage productImageStorage,
            ProductImageUrlResolver productImageUrlResolver
    ) {
        this.productRepository = productRepository;
        this.productImageStorage = productImageStorage;
        this.productImageUrlResolver =
                productImageUrlResolver;
    }

    @Transactional
    public ProductResult createProduct(
            CreateProductCommand command
    ) {
        Product product = Product.create(
                command.sku(),
                command.name(),
                command.description(),
                command.price(),
                command.stockQuantity()
        );

        if (productRepository.existsBySku(product.getSku())) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_SKU_ALREADY_EXISTS
            );
        }

        Product savedProduct =
                productRepository.save(product);

        return toResult(savedProduct);
    }

    @Transactional
    public ProductResult updateProduct(
            UUID productId,
            UpdateProductCommand command
    ) {
        Product product = getProductOrThrow(productId);

        if (product.getVersion() != command.expectedVersion()) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_VERSION_CONFLICT
            );
        }

        product.updateDetails(
                command.sku(),
                command.name(),
                command.description(),
                command.price(),
                command.stockQuantity()
        );

        if (
                productRepository.existsOtherProductWithSku(
                        product.getSku(),
                        productId
                )
        ) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_SKU_ALREADY_EXISTS
            );
        }

        Product savedProduct =
                productRepository.save(product);

        return toResult(savedProduct);
    }

    @Transactional
    public void deactivateProduct(UUID productId) {
        Product product = getProductOrThrow(productId);

        product.deactivate();
        productRepository.save(product);
    }

    public ProductResult getProduct(UUID productId) {
        return toResult(getProductOrThrow(productId));
    }

    public PageResult<ProductResult> searchProducts(
            ProductSearchCriteria criteria,
            PageQuery pageQuery
    ) {
        validateProductPageOffset(pageQuery);

        return productRepository
                .search(criteria, pageQuery)
                .map(this::toResult);
    }

    public PageResult<ProductResult> searchActiveProducts(
            String keyword,
            PageQuery pageQuery
    ) {
        validateProductPageOffset(pageQuery);

        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(keyword);

        return productRepository
                .search(criteria, pageQuery)
                .map(this::toResult);
    }

    public ProductCursorPageResult
    searchActiveProductsByCursor(
            String keyword,
            String cursor,
            int size
    ) {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(keyword);

        return searchProductsByCursor(
                criteria,
                ProductSearchCursorScope.PUBLIC,
                cursor,
                size
        );
    }

    public ProductCursorPageResult searchProductsByCursor(
            ProductSearchCriteria criteria,
            String cursor,
            int size
    ) {
        return searchProductsByCursor(
                criteria,
                ProductSearchCursorScope.ADMIN,
                cursor,
                size
        );
    }

    private ProductCursorPageResult searchProductsByCursor(
            ProductSearchCriteria criteria,
            ProductSearchCursorScope scope,
            String cursor,
            int size
    ) {
        ProductSearchWindow window =
                toSearchWindow(
                        criteria,
                        scope,
                        cursor,
                        size
                );

        ProductSearchSlice<ProductResult> resultSlice =
                productRepository
                        .search(criteria, window)
                        .map(this::toResult);

        String nextCursor = resultSlice
                .nextPosition()
                .map(position ->
                        ProductSearchCursorCodec.encode(
                                scope,
                                criteria,
                                position
                        )
                )
                .orElse(null);

        return new ProductCursorPageResult(
                resultSlice.content(),
                window.size(),
                resultSlice.hasNext(),
                nextCursor
        );
    }

    private ProductSearchWindow toSearchWindow(
            ProductSearchCriteria criteria,
            ProductSearchCursorScope scope,
            String cursor,
            int size
    ) {
        ProductSearchWindow firstWindow =
                ProductSearchWindow.first(size);

        if (cursor == null || cursor.isBlank()) {
            return firstWindow;
        }

        ProductSearchPosition position =
                ProductSearchCursorCodec.decode(
                        cursor,
                        scope,
                        criteria
                );

        return ProductSearchWindow.after(
                firstWindow.size(),
                position
        );
    }

    private void validateProductPageOffset(
            PageQuery pageQuery
    ) {
        long offset =
                (long) pageQuery.page()
                        * pageQuery.size();

        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(
                    CatalogErrorCode
                            .PRODUCT_PAGE_OFFSET_TOO_LARGE
            );
        }
    }

    private Product getProductOrThrow(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() ->
                        new BusinessException(
                                CatalogErrorCode.PRODUCT_NOT_FOUND
                        )
                );
    }

    private ProductResult toResult(Product product) {
        return new ProductResult(
                product.getId(),
                product.getVersion(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getStatus(),
                productImageUrlResolver.resolve(
                        product.getId(),
                        product.getImageKey()
                ),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    public ProductResult getActiveProduct(UUID productId) {
        Product product = getProductOrThrow(productId);

        if (!product.isActive()) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_NOT_ACTIVE
            );
        }

        return toResult(product);
    }

    public ProductForCheckoutResult getProductForCheckout(
            UUID productId
    ) {
        Product product = getProductOrThrow(productId);

        if (!product.isActive()) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_NOT_ACTIVE
            );
        }

        return new ProductForCheckoutResult(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getImageKey()
        );
    }

    @Transactional
    public ProductResult uploadProductImage(
            UUID productId,
            UploadProductImageCommand command
    ) {
        Product product = getProductOrThrow(productId);

        validateProductImage(command);

        StoredProductImage storedImage =
                productImageStorage.store(
                        productId,
                        command
                );

        product.updateImage(
                storedImage.imageKey(),
                null
        );

        Product savedProduct =
                productRepository.save(product);

        return toResult(savedProduct);
    }

    @Transactional
    public boolean decreaseStockIfEnough(
            UUID productId,
            int quantity
    ) {
        return productRepository.decreaseStockIfEnough(
                productId,
                quantity
        );
    }

    @Transactional
    public boolean restoreStock(
            UUID productId,
            int quantity
    ) {
        return productRepository.increaseStock(
                productId,
                quantity
        );
    }

    public ProductImageContent getProductImage(
            UUID productId
    ) {
        Product product = getProductOrThrow(productId);

        if (
                product.getImageKey() == null
                        || product.getImageKey().isBlank()
        ) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND
            );
        }

        return productImageStorage.load(
                product.getImageKey()
        );
    }

    public ProductImageContent getProductImageByKey(
            String imageKey
    ) {
        if (imageKey == null || imageKey.isBlank()) {
            throw new BusinessException(
                    CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND
            );
        }

        return productImageStorage.load(imageKey.trim());
    }

    private void validateProductImage(
            UploadProductImageCommand command
    ) {
        if (command == null) {
            throw invalidImage(
                    "image file is required"
            );
        }

        if (command.content() == null) {
            throw invalidImage(
                    "image content is required"
            );
        }

        if (command.size() <= 0) {
            throw invalidImage(
                    "image file must not be empty"
            );
        }

        if (command.size() > MAX_PRODUCT_IMAGE_SIZE_BYTES) {
            throw invalidImage(
                    "image file must not exceed 5MB"
            );
        }

        String contentType =
                normalizeContentType(command.contentType());

        if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw invalidImage(
                    "only JPEG, PNG, and WebP images are allowed"
            );
        }

        String extension =
                extractExtension(command.originalFilename());

        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw invalidImage(
                    "image extension must be jpg, jpeg, png, or webp"
            );
        }
    }

    private BusinessException invalidImage(String detail) {
        return new BusinessException(
                CatalogErrorCode.INVALID_PRODUCT_IMAGE,
                detail
        );
    }

    private String normalizeContentType(
            String contentType
    ) {
        if (
                contentType == null
                        || contentType.isBlank()
        ) {
            throw invalidImage(
                    "image content type is required"
            );
        }

        return contentType
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            throw invalidImage(
                    "image filename is required"
            );
        }

        String trimmedFilename = filename.trim();
        int lastDotIndex =
                trimmedFilename.lastIndexOf('.');

        if (
                lastDotIndex < 0
                        || lastDotIndex
                        == trimmedFilename.length() - 1
        ) {
            throw invalidImage(
                    "image filename must have an extension"
            );
        }

        return trimmedFilename
                .substring(lastDotIndex + 1)
                .toLowerCase(Locale.ROOT);
    }
}