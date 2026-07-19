package com.lul.shop.ordering.infrastructure.catalog;

import com.lul.shop.catalog.application.CatalogService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogOrderInventoryAdapterTest {

    private static final UUID PRODUCT_ID = UUID.fromString(
            "33333333-3333-4333-8333-333333333333"
    );

    @Test
    void shouldDelegateInventoryRestorationToCatalog() {
        CatalogService catalogService = mock(CatalogService.class);
        CatalogOrderInventoryAdapter adapter =
                new CatalogOrderInventoryAdapter(catalogService);

        when(catalogService.restoreStock(PRODUCT_ID, 3))
                .thenReturn(true);

        boolean restored = adapter.restoreStock(PRODUCT_ID, 3);

        assertThat(restored).isTrue();
        verify(catalogService).restoreStock(PRODUCT_ID, 3);
    }

    @Test
    void shouldPropagateMissingProductResult() {
        CatalogService catalogService = mock(CatalogService.class);
        CatalogOrderInventoryAdapter adapter =
                new CatalogOrderInventoryAdapter(catalogService);

        when(catalogService.restoreStock(PRODUCT_ID, 3))
                .thenReturn(false);

        boolean restored = adapter.restoreStock(PRODUCT_ID, 3);

        assertThat(restored).isFalse();
        verify(catalogService).restoreStock(PRODUCT_ID, 3);
    }
}