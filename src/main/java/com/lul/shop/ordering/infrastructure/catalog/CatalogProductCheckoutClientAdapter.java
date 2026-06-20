package com.lul.shop.ordering.infrastructure.catalog;

import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.ordering.application.OrderingErrorCode;
import com.lul.shop.ordering.application.port.ProductCheckoutClient;
import com.lul.shop.ordering.application.port.ProductSnapshot;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CatalogProductCheckoutClientAdapter implements ProductCheckoutClient {

    private final CatalogService catalogService;

    public CatalogProductCheckoutClientAdapter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public ProductSnapshot getActiveProduct(UUID productId) {
        try {
            ProductResult result = catalogService.getActiveProduct(productId);

            return new ProductSnapshot(
                    result.id(),
                    result.sku(),
                    result.name(),
                    result.price()
            );
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CatalogErrorCode.PRODUCT_NOT_FOUND
                    || ex.getErrorCode() == CatalogErrorCode.PRODUCT_NOT_ACTIVE) {
                throw new BusinessException(OrderingErrorCode.PRODUCT_NOT_AVAILABLE);
            }

            throw ex;
        }
    }

    @Override
    public boolean decreaseStockIfEnough(UUID productId, int quantity) {
        return catalogService.decreaseStockIfEnough(productId, quantity);
    }
}