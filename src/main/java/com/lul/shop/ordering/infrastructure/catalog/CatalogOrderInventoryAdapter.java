package com.lul.shop.ordering.infrastructure.catalog;


import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.ordering.application.port.OrderInventoryClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CatalogOrderInventoryAdapter implements OrderInventoryClient {

    private final CatalogService catalogService;

    CatalogOrderInventoryAdapter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public boolean restoreStock(UUID productId, int quantity) {
        return catalogService.restoreStock(productId,quantity);
    }
}
