package com.lul.shop.catalog.presentation;

import com.lul.shop.catalog.application.CatalogService;
import com.lul.shop.catalog.application.dto.ProductCursorPageResult;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.catalog.presentation.dto.response.ProductCursorPageResponse;
import com.lul.shop.shared.api.ApiResponse;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogSearchRequestContractTest {

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private CatalogController catalogController;

    @Test
    void shouldCapPublicSearchPageSizeAtOneHundred() {
        PageQuery expectedPageQuery =
                new PageQuery(2, 100);

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

    @Test
    void shouldExposeDistinctStaticCursorRoutes()
            throws NoSuchMethodException {

        Method publicMethod = publicCursorMethod();
        Method adminMethod = adminCursorMethod();

        assertThat(publicMethod.getAnnotation(
                GetMapping.class
        ).value()).containsExactly(
                "/products/cursor"
        );

        assertThat(adminMethod.getAnnotation(
                GetMapping.class
        ).value()).containsExactly(
                "/admin/products/cursor"
        );

        assertThat(publicMethod.getParameterTypes())
                .containsExactly(
                        String.class,
                        String.class,
                        int.class
                );

        assertThat(adminMethod.getParameterTypes())
                .containsExactly(
                        String.class,
                        ProductStatus.class,
                        String.class,
                        int.class
                );
    }

    @Test
    void shouldDeclareCursorSizeDefaultAndOptionalCursor()
            throws NoSuchMethodException {

        Method publicMethod = publicCursorMethod();
        Method adminMethod = adminCursorMethod();

        RequestParam publicCursor =
                requestParam(publicMethod, 1);

        RequestParam publicSize =
                requestParam(publicMethod, 2);

        RequestParam adminCursor =
                requestParam(adminMethod, 2);

        RequestParam adminSize =
                requestParam(adminMethod, 3);

        assertThat(publicCursor.required()).isFalse();
        assertThat(adminCursor.required()).isFalse();

        assertThat(publicSize.defaultValue())
                .isEqualTo("20");

        assertThat(adminSize.defaultValue())
                .isEqualTo("20");
    }

    @Test
    void shouldForwardNullAndBlankPublicCursor() {
        when(catalogService.searchActiveProductsByCursor(
                "phone",
                null,
                20
        )).thenReturn(emptyCursorPage(20));

        when(catalogService.searchActiveProductsByCursor(
                "phone",
                "   ",
                20
        )).thenReturn(emptyCursorPage(20));

        catalogController.searchProductsByCursor(
                "phone",
                null,
                20
        );

        catalogController.searchProductsByCursor(
                "phone",
                "   ",
                20
        );

        verify(catalogService)
                .searchActiveProductsByCursor(
                        "phone",
                        null,
                        20
                );

        verify(catalogService)
                .searchActiveProductsByCursor(
                        "phone",
                        "   ",
                        20
                );
    }

    @Test
    void shouldCapPublicAndAdminCursorSizeAtOneHundred() {
        ProductSearchCriteria adminCriteria =
                ProductSearchCriteria.withStatus(
                        "phone",
                        ProductStatus.INACTIVE
                );

        when(catalogService.searchActiveProductsByCursor(
                "phone",
                null,
                100
        )).thenReturn(emptyCursorPage(100));

        when(catalogService.searchProductsByCursor(
                adminCriteria,
                null,
                100
        )).thenReturn(emptyCursorPage(100));

        catalogController.searchProductsByCursor(
                "phone",
                null,
                1000
        );

        catalogController.searchAdminProductsByCursor(
                "phone",
                ProductStatus.INACTIVE,
                null,
                1000
        );

        verify(catalogService)
                .searchActiveProductsByCursor(
                        "phone",
                        null,
                        100
                );

        verify(catalogService)
                .searchProductsByCursor(
                        adminCriteria,
                        null,
                        100
                );
    }

    @Test
    void shouldRejectNonPositiveCursorSizeBeforeService() {
        assertThatThrownBy(() ->
                catalogController.searchProductsByCursor(
                        "phone",
                        null,
                        0
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be >= 1");

        assertThatThrownBy(() ->
                catalogController.searchAdminProductsByCursor(
                        "phone",
                        ProductStatus.ACTIVE,
                        null,
                        -1
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be >= 1");

        verifyNoInteractions(catalogService);
    }

    @Test
    void shouldBuildTypedAdminCursorCriteria() {
        when(catalogService.searchProductsByCursor(
                any(ProductSearchCriteria.class),
                eq("cursor-value"),
                eq(25)
        )).thenReturn(emptyCursorPage(25));

        catalogController.searchAdminProductsByCursor(
                " phone ",
                ProductStatus.INACTIVE,
                "cursor-value",
                25
        );

        ArgumentCaptor<ProductSearchCriteria>
                criteriaCaptor = ArgumentCaptor.forClass(
                ProductSearchCriteria.class
        );

        verify(catalogService)
                .searchProductsByCursor(
                        criteriaCaptor.capture(),
                        eq("cursor-value"),
                        eq(25)
                );

        assertThat(criteriaCaptor.getValue().keyword())
                .isEqualTo("phone");

        assertThat(criteriaCaptor.getValue().status())
                .isEqualTo(ProductStatus.INACTIVE);
    }

    @Test
    void shouldReturnDedicatedCursorResponseShape() {
        when(catalogService.searchActiveProductsByCursor(
                "phone",
                null,
                5
        )).thenReturn(new ProductCursorPageResult(
                List.of(productResult()),
                5,
                true,
                "next-cursor"
        ));

        ApiResponse<ProductCursorPageResponse> response =
                catalogController.searchProductsByCursor(
                        "phone",
                        null,
                        5
                );

        assertThat(response.data().content())
                .hasSize(1);

        assertThat(response.data().content().get(0).id())
                .isEqualTo(PRODUCT_ID);

        assertThat(response.data().size()).isEqualTo(5);
        assertThat(response.data().hasNext()).isTrue();
        assertThat(response.data().nextCursor())
                .isEqualTo("next-cursor");

        assertThat(Arrays.stream(
                        ProductCursorPageResponse.class
                                .getRecordComponents()
                )
                .map(component -> component.getName())
                .toList())
                .containsExactly(
                        "content",
                        "size",
                        "hasNext",
                        "nextCursor"
                );
    }

    private static Method publicCursorMethod()
            throws NoSuchMethodException {
        return CatalogController.class.getDeclaredMethod(
                "searchProductsByCursor",
                String.class,
                String.class,
                int.class
        );
    }

    private static Method adminCursorMethod()
            throws NoSuchMethodException {
        return CatalogController.class.getDeclaredMethod(
                "searchAdminProductsByCursor",
                String.class,
                ProductStatus.class,
                String.class,
                int.class
        );
    }

    private static RequestParam requestParam(
            Method method,
            int parameterIndex
    ) {
        return method.getParameters()[parameterIndex]
                .getAnnotation(RequestParam.class);
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

    private static ProductCursorPageResult
    emptyCursorPage(int size) {
        return new ProductCursorPageResult(
                List.of(),
                size,
                false,
                null
        );
    }

    private static ProductResult productResult() {
        return new ProductResult(
                PRODUCT_ID,
                4L,
                "SKU-001",
                "Running Shoes",
                "Daily shoes",
                new BigDecimal("199000.00"),
                10,
                ProductStatus.ACTIVE,
                null,
                Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:00Z")
        );
    }
}