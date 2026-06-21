package com.lul.shop.cart.infrastructure.catalog;

import com.lul.shop.cart.application.port.CatalogProductClient;
import com.lul.shop.cart.application.port.CartProductSnapshot;
import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CatalogProductClientAdapter implements CatalogProductClient {

    private final CatalogService catalogService;

    public CatalogProductClientAdapter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public Optional<CartProductSnapshot> findActiveProduct(UUID productId) {

        try {
            ProductResult product = catalogService.getActiveProduct(productId);

            return Optional.of(new CartProductSnapshot(
                    product.id(),
                    product.stockQuantity()
            ));
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CatalogErrorCode.PRODUCT_NOT_FOUND
                    || ex.getErrorCode() == CatalogErrorCode.PRODUCT_NOT_ACTIVE) {
                return Optional.empty();
            }

            throw ex;
        }

    }

}
