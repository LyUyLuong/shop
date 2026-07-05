package com.lul.shop.catalog.infrastructure.storage.s3;

import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.dto.ProductImageContent;
import com.lul.shop.catalog.application.dto.StoredProductImage;
import com.lul.shop.catalog.application.dto.UploadProductImageCommand;
import com.lul.shop.catalog.application.port.ProductImageStorage;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Component
public class S3ProductImageStorage implements ProductImageStorage {

    private final S3Client s3Client;
    private final S3Properties properties;

    private static final Logger log = LoggerFactory.getLogger(S3ProductImageStorage.class);

    public S3ProductImageStorage(S3Client s3Client, S3Properties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public StoredProductImage store(UUID productId, UploadProductImageCommand command) {
        String imageKey = buildImageKey(productId, command.originalFilename());

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(imageKey)
                    .contentType(command.contentType())
                    .contentLength(command.size())
                    .build();

            s3Client.putObject(
                    request,
                    RequestBody.fromInputStream(command.content(), command.size())
            );

            log.info(
                    "action=product.image_uploaded productId={} imageKey={} contentType={} size={}",
                    productId,
                    imageKey,
                    command.contentType(),
                    command.size()
            );

            return new StoredProductImage(imageKey);
        } catch (SdkException ex) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_IMAGE_UPLOAD_FAILED);
        }
    }

    @Override
    public ProductImageContent load(String imageKey) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(imageKey)
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);

            return new ProductImageContent(
                    response,
                    response.response().contentType(),
                    response.response().contentLength()
            );
        } catch (NoSuchKeyException ex) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw new BusinessException(CatalogErrorCode.PRODUCT_IMAGE_NOT_FOUND);
            }

            throw new BusinessException(CatalogErrorCode.PRODUCT_IMAGE_READ_FAILED);
        } catch (SdkException ex) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_IMAGE_READ_FAILED);
        }
    }

    private String buildImageKey(UUID productId, String originalFilename) {
        String prefix = normalizePrefix(properties.productImagePrefix());
        String extension = extractExtension(originalFilename);
        String filename = UUID.randomUUID() + "." + extension;

        return prefix + "/" + productId + "/" + filename;
    }

    private String encodePath(String path) {
        String[] parts = path.split("/");

        for (int i = 0; i < parts.length; i++) {
            parts[i] = URLEncoder.encode(parts[i], StandardCharsets.UTF_8)
                    .replace("+", "%20");
        }

        return String.join("/", parts);
    }

    private String buildImageUrl(String imageKey) {
        String encodedKey = encodePath(imageKey);

        if (hasText(properties.publicBaseUrl())) {
            return removeTrailingSlash(properties.publicBaseUrl()) + "/" + encodedKey;
        }

        return "https://" + properties.bucket()
                + ".s3."
                + properties.region()
                + ".amazonaws.com/"
                + encodedKey;
    }

    private String normalizePrefix(String prefix) {
        if (!hasText(prefix)) {
            return "products";
        }

        String normalized = prefix.trim();

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.isBlank() ? "products" : normalized;
    }

    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(CatalogErrorCode.INVALID_PRODUCT_IMAGE, "image filename is required");
        }

        String trimmedFilename = filename.trim();
        int lastDotIndex = trimmedFilename.lastIndexOf('.');

        if (lastDotIndex < 0 || lastDotIndex == trimmedFilename.length() - 1) {
            throw new BusinessException(CatalogErrorCode.INVALID_PRODUCT_IMAGE, "image filename must have an extension");
        }

        return trimmedFilename.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String removeTrailingSlash(String value) {
        String result = value.trim();

        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}