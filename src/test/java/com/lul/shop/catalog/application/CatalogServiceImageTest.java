package com.lul.shop.catalog.application;

import com.lul.shop.catalog.application.dto.ProductImageContent;
import com.lul.shop.catalog.application.dto.ProductResult;
import com.lul.shop.catalog.application.dto.StoredProductImage;
import com.lul.shop.catalog.application.dto.UploadProductImageCommand;
import com.lul.shop.catalog.application.port.ProductImageStorage;
import com.lul.shop.catalog.domain.Product;
import com.lul.shop.catalog.domain.ProductRepository;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceImageTest {

    private static final UUID PRODUCT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final String IMAGE_KEY =
            "products/22222222-2222-4222-8222-222222222222/product.webp";

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
                new ProductImageUrlResolver("https://shop.example.com/")
        );
    }

    @Test
    void shouldUploadValidImageAndReturnResolvedImageUrl() {
        Product product = product(null);
        UploadProductImageCommand command = imageCommand(
                " product.WEBP ",
                " IMAGE/WEBP ",
                3
        );

        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(productImageStorage.store(PRODUCT_ID, command))
                .thenReturn(new StoredProductImage(IMAGE_KEY));
        when(productRepository.save(product)).thenReturn(product);

        ProductResult result =
                service.uploadProductImage(PRODUCT_ID, command);

        assertThat(product.getImageKey()).isEqualTo(IMAGE_KEY);
        assertThat(result.imageUrl()).isEqualTo(
                "https://shop.example.com/api/v1/products/"
                        + PRODUCT_ID
                        + "/image"
        );

        verify(productImageStorage).store(PRODUCT_ID, command);
        verify(productRepository).save(product);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidImageCommands")
    void shouldRejectInvalidImageBeforeStorage(
            String scenario,
            UploadProductImageCommand command
    ) {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product(null)));

        assertThatThrownBy(
                () -> service.uploadProductImage(PRODUCT_ID, command)
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        CatalogErrorCode.INVALID_PRODUCT_IMAGE
                                )
                );

        verify(productImageStorage, never()).store(
                any(UUID.class),
                any(UploadProductImageCommand.class)
        );
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void shouldLoadProductImageWhenImageKeyExists() {
        Product product = product(IMAGE_KEY);
        ProductImageContent content = imageContent();

        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(productImageStorage.load(IMAGE_KEY))
                .thenReturn(content);

        ProductImageContent result =
                service.getProductImage(PRODUCT_ID);

        assertThat(result).isSameAs(content);
        verify(productImageStorage).load(IMAGE_KEY);
    }

    @Test
    void shouldRejectProductImageWhenProductHasNoImageKey() {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(product(null)));

        assertThatThrownBy(
                () -> service.getProductImage(PRODUCT_ID)
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND
                                )
                );

        verifyNoInteractions(productImageStorage);
    }

    @Test
    void shouldTrimImageKeyBeforeLoadingByKey() {
        ProductImageContent content = imageContent();

        when(productImageStorage.load(IMAGE_KEY))
                .thenReturn(content);

        ProductImageContent result =
                service.getProductImageByKey("  " + IMAGE_KEY + "  ");

        assertThat(result).isSameAs(content);
        verify(productImageStorage).load(IMAGE_KEY);
    }

    @Test
    void shouldRejectBlankImageKey() {
        assertThatThrownBy(
                () -> service.getProductImageByKey("   ")
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND
                                )
                );

        verifyNoInteractions(productImageStorage);
    }

    private static Stream<Arguments> invalidImageCommands() {
        return Stream.of(
                Arguments.of(
                        "missing command",
                        (UploadProductImageCommand) null
                ),
                Arguments.of(
                        "missing content",
                        new UploadProductImageCommand(
                                "image.png",
                                "image/png",
                                3,
                                null
                        )
                ),
                Arguments.of(
                        "empty image",
                        imageCommand("image.png", "image/png", 0)
                ),
                Arguments.of(
                        "image exceeds five megabytes",
                        imageCommand(
                                "image.png",
                                "image/png",
                                5L * 1024 * 1024 + 1
                        )
                ),
                Arguments.of(
                        "missing content type",
                        imageCommand("image.png", " ", 3)
                ),
                Arguments.of(
                        "unsupported content type",
                        imageCommand("image.png", "image/gif", 3)
                ),
                Arguments.of(
                        "missing filename extension",
                        imageCommand("image", "image/png", 3)
                ),
                Arguments.of(
                        "unsupported filename extension",
                        imageCommand("image.gif", "image/png", 3)
                )
        );
    }

    private static UploadProductImageCommand imageCommand(
            String filename,
            String contentType,
            long size
    ) {
        return new UploadProductImageCommand(
                filename,
                contentType,
                size,
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
    }

    private static ProductImageContent imageContent() {
        return new ProductImageContent(
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "image/webp",
                3
        );
    }

    private static Product product(String imageKey) {
        return new Product(
                PRODUCT_ID,
                "SKU-001",
                "Running Shoes",
                "Daily shoes",
                new BigDecimal("199000.00"),
                10,
                ProductStatus.ACTIVE,
                imageKey,
                null,
                Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:00Z")
        );
    }
}