package com.lul.shop.catalog.application;

import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.shared.exception.BusinessException;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class ProductSearchCursorCodec {

    private static final byte CURSOR_VERSION = 1;

    private static final String SEARCH_CONTRACT_VERSION =
            "product-search-v1";

    private static final int FINGERPRINT_BYTES = 32;
    private static final int BROWSE_PAYLOAD_BYTES = 62;
    private static final int RANKED_PAYLOAD_BYTES = 66;
    private static final int MAX_ENCODED_LENGTH = 128;

    private static final Base64.Encoder BASE64_ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder BASE64_DECODER =
            Base64.getUrlDecoder();

    private ProductSearchCursorCodec() {
    }

    public static String encode(
            ProductSearchCursorScope scope,
            ProductSearchCriteria criteria,
            ProductSearchPosition position
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(
                criteria,
                "criteria must not be null"
        );
        Objects.requireNonNull(
                position,
                "position must not be null"
        );

        boolean rankedSearch = hasKeyword(criteria);
        validateEncodeShape(rankedSearch, position);

        int payloadLength = rankedSearch
                ? RANKED_PAYLOAD_BYTES
                : BROWSE_PAYLOAD_BYTES;

        ByteBuffer buffer = ByteBuffer
                .allocate(payloadLength)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.put(CURSOR_VERSION);
        buffer.put(criteriaFingerprint(scope, criteria));
        buffer.put((byte) (rankedSearch ? 1 : 0));

        if (rankedSearch) {
            buffer.putInt(position.requiredMatchPriority());
        }

        buffer.putLong(position.createdAt().getEpochSecond());
        buffer.putInt(position.createdAt().getNano());
        buffer.putLong(position.id().getMostSignificantBits());
        buffer.putLong(position.id().getLeastSignificantBits());

        if (buffer.hasRemaining()) {
            throw new IllegalStateException(
                    "cursor payload length does not match its format"
            );
        }

        String encoded = BASE64_ENCODER.encodeToString(
                buffer.array()
        );

        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalStateException(
                    "generated cursor exceeds the supported length"
            );
        }

        return encoded;
    }

    public static ProductSearchPosition decode(
            String cursor,
            ProductSearchCursorScope scope,
            ProductSearchCriteria criteria
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(
                criteria,
                "criteria must not be null"
        );

        if (!isCanonicalBase64UrlInput(cursor)) {
            throw invalidCursor();
        }

        byte[] payload;

        try {
            payload = BASE64_DECODER.decode(cursor);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }

        if (!BASE64_ENCODER.encodeToString(payload).equals(cursor)) {
            throw invalidCursor();
        }

        return decodePayload(
                payload,
                scope,
                criteria
        );
    }

    private static ProductSearchPosition decodePayload(
            byte[] payload,
            ProductSearchCursorScope scope,
            ProductSearchCriteria criteria
    ) {
        if (
                payload.length != BROWSE_PAYLOAD_BYTES
                        && payload.length != RANKED_PAYLOAD_BYTES
        ) {
            throw invalidCursor();
        }

        ByteBuffer buffer = ByteBuffer
                .wrap(payload)
                .order(ByteOrder.BIG_ENDIAN);

        if (buffer.get() != CURSOR_VERSION) {
            throw invalidCursor();
        }

        byte[] actualFingerprint =
                new byte[FINGERPRINT_BYTES];
        buffer.get(actualFingerprint);

        byte[] expectedFingerprint =
                criteriaFingerprint(scope, criteria);

        if (
                !MessageDigest.isEqual(
                        actualFingerprint,
                        expectedFingerprint
                )
        ) {
            throw invalidCursor();
        }

        int priorityFlag = Byte.toUnsignedInt(buffer.get());

        if (priorityFlag != 0 && priorityFlag != 1) {
            throw invalidCursor();
        }

        boolean priorityPresent = priorityFlag == 1;
        boolean rankedSearch = hasKeyword(criteria);

        if (priorityPresent != rankedSearch) {
            throw invalidCursor();
        }

        int expectedLength = priorityPresent
                ? RANKED_PAYLOAD_BYTES
                : BROWSE_PAYLOAD_BYTES;

        if (payload.length != expectedLength) {
            throw invalidCursor();
        }

        Integer matchPriority = priorityPresent
                ? buffer.getInt()
                : null;

        long epochSecond = buffer.getLong();
        int nano = buffer.getInt();

        if (nano < 0 || nano > 999_999_999) {
            throw invalidCursor();
        }

        Instant createdAt;

        try {
            createdAt = Instant.ofEpochSecond(
                    epochSecond,
                    nano
            );
        } catch (DateTimeException exception) {
            throw invalidCursor();
        }

        UUID id = new UUID(
                buffer.getLong(),
                buffer.getLong()
        );

        if (buffer.hasRemaining()) {
            throw invalidCursor();
        }

        try {
            return new ProductSearchPosition(
                    matchPriority,
                    createdAt,
                    id
            );
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static void validateEncodeShape(
            boolean rankedSearch,
            ProductSearchPosition position
    ) {
        if (
                rankedSearch
                        && !position.hasMatchPriority()
        ) {
            throw new IllegalArgumentException(
                    "ranked search cursor requires matchPriority"
            );
        }

        if (
                !rankedSearch
                        && position.hasMatchPriority()
        ) {
            throw new IllegalArgumentException(
                    "browse cursor must not contain matchPriority"
            );
        }
    }

    private static byte[] criteriaFingerprint(
            ProductSearchCursorScope scope,
            ProductSearchCriteria criteria
    ) {
        MessageDigest digest = newSha256Digest();

        updateField(
                digest,
                SEARCH_CONTRACT_VERSION
        );
        updateField(
                digest,
                scope.fingerprintValue()
        );
        updateField(
                digest,
                normalizeKeyword(criteria.keyword())
        );
        updateField(
                digest,
                criteria.status() == null
                        ? null
                        : criteria.status().name()
        );
        updateField(
                digest,
                normalizePrice(criteria.minPrice())
        );
        updateField(
                digest,
                normalizePrice(criteria.maxPrice())
        );

        return digest.digest();
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    private static void updateField(
            MessageDigest digest,
            String value
    ) {
        if (value == null) {
            updateInt(digest, -1);
            return;
        }

        byte[] bytes = value.getBytes(
                StandardCharsets.UTF_8
        );

        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(
            MessageDigest digest,
            int value
    ) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }

        String normalized = keyword
                .trim()
                .toLowerCase(Locale.ROOT);

        return normalized.isEmpty()
                ? null
                : normalized;
    }

    private static String normalizePrice(
            BigDecimal price
    ) {
        if (price == null) {
            return null;
        }

        BigDecimal normalized =
                price.stripTrailingZeros();

        return normalized.signum() == 0
                ? "0"
                : normalized.toPlainString();
    }

    private static boolean hasKeyword(
            ProductSearchCriteria criteria
    ) {
        return criteria.keyword() != null;
    }

    private static boolean isCanonicalBase64UrlInput(
            String value
    ) {
        if (
                value == null
                        || value.isBlank()
                        || value.length() > MAX_ENCODED_LENGTH
        ) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            boolean allowed =
                    character >= 'A' && character <= 'Z'
                            || character >= 'a'
                            && character <= 'z'
                            || character >= '0'
                            && character <= '9'
                            || character == '-'
                            || character == '_';

            if (!allowed) {
                return false;
            }
        }

        return true;
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(
                CatalogErrorCode.INVALID_PRODUCT_SEARCH_CURSOR
        );
    }
}