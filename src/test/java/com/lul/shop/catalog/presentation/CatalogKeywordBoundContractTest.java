package com.lul.shop.catalog.presentation;

import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductCursorPageResult;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogKeywordBoundContractTest {

    private static final int BOUNDARY = 100;

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private CatalogController catalogController;

    @Test
    void shouldAcceptPublicKeywordAtExactlyOneHundredCharacters() {
        String keyword = "a".repeat(BOUNDARY);
        PageQuery pageQuery = new PageQuery(0, 20);

        when(catalogService.searchActiveProducts(
                keyword,
                pageQuery
        )).thenReturn(emptyPage(0, 20));

        catalogController.searchProducts(
                keyword,
                0,
                20
        );

        verify(catalogService).searchActiveProducts(
                keyword,
                pageQuery
        );
    }

    @Test
    void shouldAcceptAdminKeywordAtExactlyOneHundredCharacters() {
        String keyword = "a".repeat(BOUNDARY);
        PageQuery pageQuery = new PageQuery(0, 20);

        when(catalogService.searchProducts(
                any(),
                eq(pageQuery)
        )).thenReturn(emptyPage(0, 20));

        catalogController.searchAdminProducts(
                keyword,
                ProductStatus.ACTIVE,
                0,
                20
        );

        verify(catalogService).searchProducts(
                any(),
                eq(pageQuery)
        );
    }

    @Test
    void shouldRejectPublicKeywordOverOneHundredCharactersBeforeService() {
        String keyword = "a".repeat(BOUNDARY + 1);

        assertThatThrownBy(() ->
                catalogController.searchProducts(
                        keyword,
                        0,
                        20
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(
                                            CatalogErrorCode
                                                    .INVALID_PRODUCT_SEARCH_KEYWORD
                                    );
                            assertThat(exception.getMessage())
                                    .contains(
                                            "keyword must be <= 100 characters"
                                    );
                        }
                );

        verifyNoInteractions(catalogService);
    }

    @Test
    void shouldRejectOverLongKeywordOnBothAdminRoutes() {
        String keyword = "a".repeat(BOUNDARY + 1);

        assertThatThrownBy(() ->
                catalogController.searchAdminProducts(
                        keyword,
                        ProductStatus.ACTIVE,
                        0,
                        20
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                CatalogErrorCode
                                        .INVALID_PRODUCT_SEARCH_KEYWORD
                        )
                );

        assertThatThrownBy(() ->
                catalogController.searchAdminProductsByCursor(
                        keyword,
                        ProductStatus.ACTIVE,
                        null,
                        20
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                CatalogErrorCode
                                        .INVALID_PRODUCT_SEARCH_KEYWORD
                        )
                );

        verifyNoInteractions(catalogService);
    }

    @Test
    void shouldMeasureLengthAfterTrimmingWhitespace() {
        String raw = " " + "a".repeat(BOUNDARY) + " ";
        PageQuery pageQuery = new PageQuery(0, 20);

        when(catalogService.searchActiveProducts(
                raw,
                pageQuery
        )).thenReturn(emptyPage(0, 20));

        catalogController.searchProducts(
                raw,
                0,
                20
        );

        verify(catalogService).searchActiveProducts(
                raw,
                pageQuery
        );
    }

    @Test
    void shouldRejectOverLongKeywordOnPublicCursorRoute() {
        String keyword = "a".repeat(BOUNDARY + 1);

        assertThatThrownBy(() ->
                catalogController.searchProductsByCursor(
                        keyword,
                        null,
                        20
                )
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                CatalogErrorCode
                                        .INVALID_PRODUCT_SEARCH_KEYWORD
                        )
                );

        verifyNoInteractions(catalogService);
    }

    @Test
    void shouldAcceptPublicCursorKeywordAtExactlyOneHundredCharacters() {
        String keyword = "a".repeat(BOUNDARY);

        when(catalogService.searchActiveProductsByCursor(
                keyword,
                null,
                20
        )).thenReturn(new ProductCursorPageResult(
                List.of(),
                20,
                false,
                null
        ));

        catalogController.searchProductsByCursor(
                keyword,
                null,
                20
        );

        verify(catalogService).searchActiveProductsByCursor(
                keyword,
                null,
                20
        );
    }

    @Test
    void shouldForwardBlankAndNullKeywordUnchanged() {
        PageQuery pageQuery = new PageQuery(0, 20);

        when(catalogService.searchActiveProducts(
                null,
                pageQuery
        )).thenReturn(emptyPage(0, 20));

        when(catalogService.searchActiveProducts(
                "   ",
                pageQuery
        )).thenReturn(emptyPage(0, 20));

        catalogController.searchProducts(
                null,
                0,
                20
        );

        catalogController.searchProducts(
                "   ",
                0,
                20
        );

        verify(catalogService).searchActiveProducts(
                null,
                pageQuery
        );

        verify(catalogService).searchActiveProducts(
                "   ",
                pageQuery
        );
    }

    private static PageResult<ProductResult> emptyPage(
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