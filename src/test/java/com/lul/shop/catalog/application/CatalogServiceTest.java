package com.lul.shop.catalog.application;

import com.lul.shop.catalog.application.dto.*;
import com.lul.shop.catalog.application.port.ProductImageStorage;
import com.lul.shop.catalog.domain.*;
import com.lul.shop.shared.domain.PageQuery;
import com.lul.shop.shared.domain.PageResult;
import com.lul.shop.shared.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final UUID NEXT_PRODUCT_ID =
            UUID.fromString(
                    "33333333-3333-4333-8333-333333333333"
            );

    private static final String IMAGE_KEY =
            "products/22222222-2222-4222-8222-222222222222/product.webp";

    private static final long PRODUCT_VERSION = 4L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductImageStorage productImageStorage;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        service = new CatalogService(
                productRepository,
                productImageStorage,
                new ProductImageUrlResolver(
                        "https://shop.example.com/"
                )
        );
    }

    @Test
    void shouldCreateActiveProductWithNormalizedSku() {
        when(productRepository.existsBySku("SKU-001"))
                .thenReturn(false);

        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        ProductResult result = service.createProduct(
                new CreateProductCommand(
                        " sku-001 ",
                        " Running Shoes ",
                        " Daily shoes ",
                        new BigDecimal("199000.00"),
                        10
                )
        );

        assertThat(result.sku()).isEqualTo("SKU-001");
        assertThat(result.name())
                .isEqualTo("Running Shoes");
        assertThat(result.description())
                .isEqualTo("Daily shoes");
        assertThat(result.price())
                .isEqualByComparingTo("199000.00");
        assertThat(result.stockQuantity()).isEqualTo(10);
        assertThat(result.status())
                .isEqualTo(ProductStatus.ACTIVE);
        assertThat(result.imageUrl()).isNull();
        assertThat(result.version()).isZero();

        verify(productRepository)
                .existsBySku("SKU-001");
        verify(productRepository)
                .save(any(Product.class));
    }

    @Test
    void shouldRejectCreateWhenSkuAlreadyExists() {
        when(productRepository.existsBySku("SKU-001"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(
                new CreateProductCommand(
                        "sku-001",
                        "Running Shoes",
                        null,
                        new BigDecimal("199000.00"),
                        10
                )
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode
                                .PRODUCT_SKU_ALREADY_EXISTS
                )
        );

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldUpdateProductDetails() {
        Product product =
                product(ProductStatus.ACTIVE, null);

        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product));

        when(productRepository.existsOtherProductWithSku(
                "SKU-002",
                PRODUCT_ID
        )).thenReturn(false);

        when(productRepository.save(product))
                .thenReturn(product);

        ProductResult result = service.updateProduct(
                PRODUCT_ID,
                new UpdateProductCommand(
                        " sku-002 ",
                        " Updated Shoes ",
                        " Updated description ",
                        new BigDecimal("249000.00"),
                        20,
                        PRODUCT_VERSION
                )
        );

        assertThat(result.sku()).isEqualTo("SKU-002");
        assertThat(result.name())
                .isEqualTo("Updated Shoes");
        assertThat(result.description())
                .isEqualTo("Updated description");
        assertThat(result.price())
                .isEqualByComparingTo("249000.00");
        assertThat(result.stockQuantity()).isEqualTo(20);
        assertThat(result.version())
                .isEqualTo(PRODUCT_VERSION);

        verify(productRepository).save(product);
    }

    @Test
    void shouldRejectUpdateWhenAnotherProductUsesSku() {
        Product product =
                product(ProductStatus.ACTIVE, null);

        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product));

        when(productRepository.existsOtherProductWithSku(
                "SKU-002",
                PRODUCT_ID
        )).thenReturn(true);

        assertThatThrownBy(() -> service.updateProduct(
                PRODUCT_ID,
                new UpdateProductCommand(
                        "sku-002",
                        "Updated Shoes",
                        null,
                        new BigDecimal("249000.00"),
                        20,
                        PRODUCT_VERSION
                )
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode
                                .PRODUCT_SKU_ALREADY_EXISTS
                )
        );

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldDeactivateProduct() {
        Product product =
                product(ProductStatus.ACTIVE, null);

        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product));

        when(productRepository.save(product))
                .thenReturn(product);

        service.deactivateProduct(PRODUCT_ID);

        assertThat(product.getStatus())
                .isEqualTo(ProductStatus.INACTIVE);
        verify(productRepository).save(product);
    }

    @Test
    void shouldRejectLookupWhenProductDoesNotExist() {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.getProduct(PRODUCT_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode.PRODUCT_NOT_FOUND
                )
        );
    }

    @Test
    void shouldRejectInactiveProductFromPublicLookup() {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(
                        product(ProductStatus.INACTIVE, null)
                ));

        assertThatThrownBy(() ->
                service.getActiveProduct(PRODUCT_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode.PRODUCT_NOT_ACTIVE
                )
        );
    }

    @Test
    void shouldReturnActiveProductSnapshotForCheckout() {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(
                        product(
                                ProductStatus.ACTIVE,
                                IMAGE_KEY
                        )
                ));

        ProductForCheckoutResult result =
                service.getProductForCheckout(PRODUCT_ID);

        assertThat(result.id()).isEqualTo(PRODUCT_ID);
        assertThat(result.sku()).isEqualTo("SKU-001");
        assertThat(result.name())
                .isEqualTo("Running Shoes");
        assertThat(result.price())
                .isEqualByComparingTo("199000.00");
        assertThat(result.imageKey())
                .isEqualTo(IMAGE_KEY);
    }

    @Test
    void shouldRejectInactiveProductForCheckout() {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(
                        product(
                                ProductStatus.INACTIVE,
                                IMAGE_KEY
                        )
                ));

        assertThatThrownBy(() ->
                service.getProductForCheckout(PRODUCT_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode.PRODUCT_NOT_ACTIVE
                )
        );
    }

    @Test
    void shouldSearchOnlyActiveProducts() {
        PageQuery pageQuery = new PageQuery(0, 20);

        when(productRepository.search(
                any(ProductSearchCriteria.class),
                eq(pageQuery)
        )).thenReturn(new PageResult<>(
                List.of(
                        product(ProductStatus.ACTIVE, null)
                ),
                0,
                20,
                1,
                1,
                false
        ));

        PageResult<ProductResult> result =
                service.searchActiveProducts(
                        " shoes ",
                        pageQuery
                );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).status())
                .isEqualTo(ProductStatus.ACTIVE);

        ArgumentCaptor<ProductSearchCriteria>
                criteriaCaptor = ArgumentCaptor.forClass(
                ProductSearchCriteria.class
        );

        verify(productRepository).search(
                criteriaCaptor.capture(),
                eq(pageQuery)
        );

        assertThat(criteriaCaptor.getValue().keyword())
                .isEqualTo("shoes");
        assertThat(criteriaCaptor.getValue().status())
                .isEqualTo(ProductStatus.ACTIVE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void shouldTreatMissingOrBlankPublicCursorAsFirstPage(
            String cursor
    ) {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly("phone");

        ProductSearchWindow firstWindow =
                ProductSearchWindow.first(20);

        when(productRepository.search(
                criteria,
                firstWindow
        )).thenReturn(
                ProductSearchSlice.terminal(List.of())
        );

        ProductCursorPageResult result =
                service.searchActiveProductsByCursor(
                        " phone ",
                        cursor,
                        20
                );

        assertThat(result.content()).isEmpty();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();

        verify(productRepository).search(
                criteria,
                firstWindow
        );
    }

    @Test
    void shouldDecodePublicCursorAndEncodeNextCursor() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly("phone");

        ProductSearchPosition inputPosition =
                ProductSearchPosition.ranked(
                        0,
                        Instant.parse(
                                "2026-08-10T12:00:00Z"
                        ),
                        PRODUCT_ID
                );

        ProductSearchPosition nextPosition =
                ProductSearchPosition.ranked(
                        1,
                        Instant.parse(
                                "2026-08-10T11:00:00Z"
                        ),
                        NEXT_PRODUCT_ID
                );

        String inputCursor =
                ProductSearchCursorCodec.encode(
                        ProductSearchCursorScope.PUBLIC,
                        criteria,
                        inputPosition
                );

        ProductSearchWindow expectedWindow =
                ProductSearchWindow.after(
                        2,
                        inputPosition
                );

        when(productRepository.search(
                criteria,
                expectedWindow
        )).thenReturn(
                ProductSearchSlice.continued(
                        List.of(
                                product(
                                        ProductStatus.ACTIVE,
                                        null
                                )
                        ),
                        nextPosition
                )
        );

        ProductCursorPageResult result =
                service.searchActiveProductsByCursor(
                        "phone",
                        inputCursor,
                        2
                );

        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotBlank();

        assertThat(ProductSearchCursorCodec.decode(
                result.nextCursor(),
                ProductSearchCursorScope.PUBLIC,
                criteria
        )).isEqualTo(nextPosition);

        verify(productRepository).search(
                criteria,
                expectedWindow
        );
    }

    @Test
    void shouldUseAdminScopeAndReturnNoTerminalCursor() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.withStatus(
                        "phone",
                        ProductStatus.INACTIVE
                );

        ProductSearchPosition inputPosition =
                ProductSearchPosition.ranked(
                        1,
                        Instant.parse(
                                "2026-08-10T12:00:00Z"
                        ),
                        PRODUCT_ID
                );

        String inputCursor =
                ProductSearchCursorCodec.encode(
                        ProductSearchCursorScope.ADMIN,
                        criteria,
                        inputPosition
                );

        ProductSearchWindow expectedWindow =
                ProductSearchWindow.after(
                        10,
                        inputPosition
                );

        when(productRepository.search(
                criteria,
                expectedWindow
        )).thenReturn(
                ProductSearchSlice.terminal(
                        List.of(
                                product(
                                        ProductStatus.INACTIVE,
                                        null
                                )
                        )
                )
        );

        ProductCursorPageResult result =
                service.searchProductsByCursor(
                        criteria,
                        inputCursor,
                        10
                );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).status())
                .isEqualTo(ProductStatus.INACTIVE);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();

        verify(productRepository).search(
                criteria,
                expectedWindow
        );
    }

    @Test
    void shouldRejectCursorWhenCriteriaOrScopeChanges() {
        ProductSearchCriteria original =
                ProductSearchCriteria.activeOnly("phone");

        ProductSearchPosition position =
                ProductSearchPosition.ranked(
                        0,
                        Instant.parse(
                                "2026-08-10T12:00:00Z"
                        ),
                        PRODUCT_ID
                );

        String cursor =
                ProductSearchCursorCodec.encode(
                        ProductSearchCursorScope.PUBLIC,
                        original,
                        position
                );

        assertCatalogError(
                () -> service.searchActiveProductsByCursor(
                        "tablet",
                        cursor,
                        20
                ),
                CatalogErrorCode
                        .INVALID_PRODUCT_SEARCH_CURSOR
        );

        assertCatalogError(
                () -> service.searchProductsByCursor(
                        original,
                        cursor,
                        20
                ),
                CatalogErrorCode
                        .INVALID_PRODUCT_SEARCH_CURSOR
        );

        verifyNoInteractions(productRepository);
    }

    @Test
    void shouldAllowLargestJpaRepresentableProductOffset() {
        PageQuery boundary =
                new PageQuery(Integer.MAX_VALUE, 1);

        ProductSearchCriteria criteria =
                ProductSearchCriteria.empty();

        when(productRepository.search(
                criteria,
                boundary
        )).thenReturn(new PageResult<>(
                List.of(),
                Integer.MAX_VALUE,
                1,
                0,
                0,
                false
        ));

        PageResult<ProductResult> result =
                service.searchProducts(
                        criteria,
                        boundary
                );

        assertThat(result.content()).isEmpty();

        verify(productRepository).search(
                criteria,
                boundary
        );
    }

    @Test
    void shouldRejectOffsetBeyondJpaRangeBeforeRepository() {
        PageQuery unsupported =
                new PageQuery(1_073_741_824, 2);

        assertCatalogError(
                () -> service.searchProducts(
                        ProductSearchCriteria.empty(),
                        unsupported
                ),
                CatalogErrorCode
                        .PRODUCT_PAGE_OFFSET_TOO_LARGE
        );

        assertCatalogError(
                () -> service.searchActiveProducts(
                        null,
                        unsupported
                ),
                CatalogErrorCode
                        .PRODUCT_PAGE_OFFSET_TOO_LARGE
        );

        verifyNoInteractions(productRepository);
    }

    @Test
    void shouldDelegateAtomicStockDecreaseToRepository() {
        when(productRepository.decreaseStockIfEnough(
                PRODUCT_ID,
                3
        )).thenReturn(true);

        boolean result =
                service.decreaseStockIfEnough(
                        PRODUCT_ID,
                        3
                );

        assertThat(result).isTrue();

        verify(productRepository)
                .decreaseStockIfEnough(PRODUCT_ID, 3);
    }

    @Test
    void shouldDelegateStockRestorationToRepository() {
        when(productRepository.increaseStock(
                PRODUCT_ID,
                3
        )).thenReturn(true);

        boolean restored =
                service.restoreStock(PRODUCT_ID, 3);

        assertThat(restored).isTrue();

        verify(productRepository)
                .increaseStock(PRODUCT_ID, 3);
    }

    @Test
    void shouldRejectUpdateWhenExpectedVersionIsStale() {
        Product product =
                product(ProductStatus.ACTIVE, null);

        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.updateProduct(
                PRODUCT_ID,
                new UpdateProductCommand(
                        "sku-002",
                        "Updated Shoes",
                        null,
                        new BigDecimal("249000.00"),
                        20,
                        PRODUCT_VERSION - 1
                )
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode
                                .PRODUCT_VERSION_CONFLICT
                )
        );

        assertThat(product.getSku())
                .isEqualTo("SKU-001");
        assertThat(product.getName())
                .isEqualTo("Running Shoes");

        verify(productRepository, never())
                .existsOtherProductWithSku(
                        anyString(),
                        any()
                );

        verify(productRepository, never())
                .save(any());
    }

    private static void assertCatalogError(
            ThrowingCallable callable,
            CatalogErrorCode expectedError
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(expectedError)
                );
    }

    private static Product product(
            ProductStatus status,
            String imageKey
    ) {
        return new Product(
                PRODUCT_ID,
                PRODUCT_VERSION,
                "SKU-001",
                "Running Shoes",
                "Daily shoes",
                new BigDecimal("199000.00"),
                10,
                status,
                imageKey,
                null,
                Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:00Z")
        );
    }
}