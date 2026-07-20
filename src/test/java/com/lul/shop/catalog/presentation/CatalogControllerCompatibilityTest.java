package com.lul.shop.catalog.presentation;

import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.UpdateProductCommand;
import com.lul.shop.catalog.presentation.dto.request.UpdateProductRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogControllerCompatibilityTest {

    private static final UUID PRODUCT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private CatalogController catalogController;

    @Test
    void shouldForwardOptionalExpectedVersion() {
        verifyUpdateContract(7L);
    }

    @Test
    void shouldPreserveLegacyUpdateWithoutExpectedVersion() {
        verifyUpdateContract(null);
    }

    private void verifyUpdateContract(Long expectedVersion) {
        UpdateProductRequest request = new UpdateProductRequest(
                "SKU-001",
                "Running Shoes",
                "Daily shoes",
                new BigDecimal("199.90"),
                8,
                expectedVersion
        );

        UpdateProductCommand expected = new UpdateProductCommand(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.stockQuantity(),
                expectedVersion
        );

        RuntimeException probe =
                new RuntimeException("Stop after verifying service call");

        when(catalogService.updateProduct(PRODUCT_ID, expected))
                .thenThrow(probe);

        assertThatThrownBy(() ->
                catalogController.updateProduct(PRODUCT_ID, request)
        ).isSameAs(probe);

        verify(catalogService).updateProduct(PRODUCT_ID, expected);
    }
}
