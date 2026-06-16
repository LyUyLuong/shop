package com.lul.shop.catalog.presentation;

import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.CreateProductCommand;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.application.dto.UpdateProductCommand;
import com.lul.shop.catalog.presentation.dto.request.CreateProductRequest;
import com.lul.shop.catalog.presentation.dto.request.UpdateProductRequest;
import com.lul.shop.catalog.presentation.dto.response.ProductResponse;
import com.lul.shop.shared.api.ApiResponse;
import com.lul.shop.shared.api.PageResponse;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class CatalogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/products")
    public ApiResponse<PageResponse<ProductResponse>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResult<ProductResponse> result = catalogService
                .searchActiveProducts(keyword, toPageQuery(page, size))
                .map(ProductResponse::from);

        return ApiResponse.ok(PageResponse.from(result));
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<ProductResponse> getProduct(@PathVariable UUID productId) {
        ProductResult result = catalogService.getActiveProduct(productId);

        return ApiResponse.ok(ProductResponse.from(result));
    }

    @PostMapping("/admin/products")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        CreateProductCommand command = new CreateProductCommand(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.stockQuantity()
        );

        ProductResult result = catalogService.createProduct(command);

        return ApiResponse.ok(ProductResponse.from(result));
    }

    @PutMapping("/admin/products/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProductResponse> updateProduct(@PathVariable UUID productId,
                                                      @Valid @RequestBody UpdateProductRequest request) {
        UpdateProductCommand command = new UpdateProductCommand(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.stockQuantity()
        );

        ProductResult result = catalogService.updateProduct(productId, command);

        return ApiResponse.ok(ProductResponse.from(result));
    }

    @DeleteMapping("/admin/products/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deactivateProduct(@PathVariable UUID productId) {
        catalogService.deactivateProduct(productId);

        return ApiResponse.ok();
    }

    private PageQuery toPageQuery(int page, int size) {
        return new PageQuery(page, Math.min(size, MAX_PAGE_SIZE));
    }
}