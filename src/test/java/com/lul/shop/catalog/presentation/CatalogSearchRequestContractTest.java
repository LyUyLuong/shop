package com.lul.shop.catalog.presentation;

import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogSearchRequestContractTest {

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private CatalogController catalogController;

    @Test
    void shouldCapPublicSearchPageSizeAtOneHundred() {
        PageQuery expectedPageQuery = new PageQuery(2, 100);

        when(catalogService.searchActiveProducts(
                "phone",
                expectedPageQuery
        )).thenReturn(emptyPage(2, 100));

        catalogController.searchProducts(
                "phone",
                2,
                1000
        );

        verify(catalogService).searchActiveProducts(
                "phone",
                expectedPageQuery
        );
    }

    @Test
    void shouldRejectNegativePageBeforeCallingService() {
        assertThatThrownBy(() ->
                catalogController.searchProducts(
                        "phone",
                        -1,
                        20
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page must be >= 0");

        verifyNoInteractions(catalogService);
    }

    @Test
    void shouldRejectNonPositiveSizeBeforeCallingService() {
        assertThatThrownBy(() ->
                catalogController.searchProducts(
                        "phone",
                        0,
                        0
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be >= 1");

        assertThatThrownBy(() ->
                catalogController.searchProducts(
                        "phone",
                        0,
                        -1
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be >= 1");

        verifyNoInteractions(catalogService);
    }

    private PageResult<ProductResult> emptyPage(
            int page,
            int size
    ) {
        return new PageResult<>(
                List.of(),
                page,
                size,
                0,
                0,
                false
        );
    }
}