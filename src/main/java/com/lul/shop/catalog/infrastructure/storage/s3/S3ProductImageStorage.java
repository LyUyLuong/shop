package com.lul.shop.catalog.infrastructure.storage.s3;

import com.lul.shop.catalog.application.CatalogErrorCode;
import com.lul.shop.catalog.application.dto.StoredProductImage;
import com.lul.shop.catalog.application.dto.UploadProductImageCommand;
import com.lul.shop.catalog.application.port.ProductImageStorage;
import com.lul.shop.shared.exception.BusinessException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Component
public class S3ProductImageStorage implements ProductImageStorage {

    private final S3Client s3Client;
    private final S3Properties properties;

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

            return new StoredProductImage(imageKey, buildImageUrl(imageKey));
        } catch (SdkException ex) {
            throw new BusinessException(CatalogErrorCode.PRODUCT_IMAGE_UPLOAD_FAILED);
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