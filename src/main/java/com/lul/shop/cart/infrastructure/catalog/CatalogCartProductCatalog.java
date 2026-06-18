package com.lul.shop.cart.infrastructure.catalog;

import com.lul.shop.cart.application.port.CartProductCatalog;
import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CatalogCartProductCatalog implements CartProductCatalog {

    private final CatalogService catalogService;

    public CatalogCartProductCatalog(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public boolean existsActiveProduct(UUID productId) {
        try {
            catalogService.getActiveProduct(productId);
            return true;
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CatalogErrorCode.PRODUCT_NOT_FOUND
                    || ex.getErrorCode() == CatalogErrorCode.PRODUCT_NOT_ACTIVE) {
                return false;
            }

            throw ex;
        }
    }
}