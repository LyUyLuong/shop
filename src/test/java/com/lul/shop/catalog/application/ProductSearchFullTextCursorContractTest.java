package com.lul.shop.catalog.application;

import com.lul.shop.catalog.domain.ProductSearchCriteria;
import com.lul.shop.catalog.domain.ProductSearchPosition;
import com.lul.shop.catalog.domain.ProductStatus;
import com.lul.shop.shared.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchFullTextCursorContractTest {

    private static final Instant BASE_TIME =
            Instant.parse("2026-08-15T08:30:00.123456789Z");

    @Test
    void shouldRoundTripCanonicalV2Cursor() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly("keyboard");

        CandidatePosition expected = position(
                4,
                1,
                987_654,
                BASE_TIME,
                uuid("44444444-4444-4444-8444-444444444444")
        );

        String cursor = CandidateCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                expected
        );

        assertThat(CandidateCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                criteria
        )).isEqualTo(expected);

        assertThat(cursor)
                .matches("[A-Za-z0-9_-]+")
                .doesNotContain("=");

        assertThat(decodePayload(cursor))
                .hasSize(CandidateCursorCodec.PAYLOAD_BYTES);
    }

    @Test
    void shouldBindCursorToNormalizedCriteriaAndScopeAndRejectV1() {
        ProductSearchCriteria original = new ProductSearchCriteria(
                " \u0110i\u1EC7n ",
                ProductStatus.ACTIVE,
                new BigDecimal("10.00"),
                new BigDecimal("100.000")
        );

        ProductSearchCriteria canonicallyEquivalent =
                new ProductSearchCriteria(
                        "\u0111ie\u0323\u0302n",
                        ProductStatus.ACTIVE,
                        new BigDecimal("10.0"),
                        new BigDecimal("100")
                );

        CandidatePosition position = position(
                1,
                0,
                800_000,
                BASE_TIME,
                uuid("11111111-1111-4111-8111-111111111111")
        );

        String cursor = CandidateCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                original,
                position
        );

        assertThat(CandidateCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                canonicallyEquivalent
        )).isEqualTo(position);

        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.ADMIN,
                canonicallyEquivalent
        ));

        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                ProductSearchCriteria.activeOnly("laptop")
        ));

        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                new ProductSearchCriteria(
                        "\u0110i\u1EC7n",
                        ProductStatus.INACTIVE,
                        new BigDecimal("10"),
                        new BigDecimal("100")
                )
        ));

        String v1Cursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                original,
                ProductSearchPosition.ranked(
                        1,
                        BASE_TIME,
                        position.id()
                )
        );

        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                v1Cursor,
                ProductSearchCursorScope.PUBLIC,
                original
        ));
    }

    @Test
    void shouldRejectMalformedAndOutOfBoundsPayloads() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly("keyboard");

        CandidatePosition position = position(
                4,
                0,
                500_000,
                BASE_TIME,
                uuid("22222222-2222-4222-8222-222222222222")
        );

        String cursor = CandidateCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                position
        );

        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                null,
                ProductSearchCursorScope.PUBLIC,
                criteria
        ));

        for (String malformed : List.of(
                "",
                "not+base64",
                cursor + "=",
                "A".repeat(129)
        )) {
            assertInvalidCursor(() -> CandidateCursorCodec.decode(
                    malformed,
                    ProductSearchCursorScope.PUBLIC,
                    criteria
            ));
        }

        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                encodePayload(Arrays.copyOf(
                        decodePayload(cursor),
                        CandidateCursorCodec.PAYLOAD_BYTES - 1
                )),
                ProductSearchCursorScope.PUBLIC,
                criteria
        ));

        assertInvalidPayload(
                withByte(cursor, CandidateCursorCodec.VERSION_OFFSET, 3),
                criteria
        );
        assertInvalidPayload(
                withInt(cursor, CandidateCursorCodec.TIER_OFFSET, 5),
                criteria
        );
        assertInvalidPayload(
                withInt(cursor, CandidateCursorCodec.SURFACE_OFFSET, 2),
                criteria
        );
        assertInvalidPayload(
                withInt(cursor, CandidateCursorCodec.RANK_OFFSET, 1_000_001),
                criteria
        );
        assertInvalidPayload(
                withInt(cursor, CandidateCursorCodec.NANO_OFFSET, 1_000_000_000),
                criteria
        );
        assertInvalidPayload(
                withLong(cursor, CandidateCursorCodec.EPOCH_OFFSET, Long.MAX_VALUE),
                criteria
        );

        String invalidSkuShape = withInt(
                cursor,
                CandidateCursorCodec.TIER_OFFSET,
                0
        );
        assertInvalidPayload(invalidSkuShape, criteria);

        assertThatThrownBy(() -> CandidateCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                ProductSearchCriteria.activeOnly(null),
                position
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldApplyCompleteDeterministicSearchOrder() {
        List<CandidatePosition> expected = orderedFixture();
        List<CandidatePosition> reversed = new ArrayList<>(expected);
        Collections.reverse(reversed);

        List<CandidatePosition> sorted = reversed.stream()
                .sorted(ProductSearchFullTextCursorContractTest::compare)
                .toList();

        assertThat(sorted).containsExactlyElementsOf(expected);
    }

    @Test
    void shouldTraverseStableRowsForwardWithoutDuplicates() {
        List<CandidatePosition> expected = orderedFixture();
        List<CandidatePosition> visited = new ArrayList<>();
        CandidatePosition anchor = null;

        while (true) {
            List<CandidatePosition> page =
                    pageAfter(expected, anchor, 2);

            if (page.isEmpty()) {
                break;
            }

            visited.addAll(page);
            anchor = page.get(page.size() - 1);
        }

        assertThat(visited)
                .containsExactlyElementsOf(expected)
                .doesNotHaveDuplicates();
    }

    @Test
    void shouldKeepForwardOnlyLiveDataSemanticsWithoutClaimingSnapshot() {
        CandidatePosition first = position(
                1, 0, 900_000, BASE_TIME,
                uuid("11111111-1111-4111-8111-111111111111")
        );
        CandidatePosition anchor = position(
                1, 0, 800_000, BASE_TIME,
                uuid("22222222-2222-4222-8222-222222222222")
        );
        CandidatePosition third = position(
                1, 0, 700_000, BASE_TIME,
                uuid("33333333-3333-4333-8333-333333333333")
        );
        CandidatePosition deleted = position(
                1, 0, 600_000, BASE_TIME,
                uuid("44444444-4444-4444-8444-444444444444")
        );

        List<CandidatePosition> firstPage = pageAfter(
                List.of(first, anchor, third, deleted),
                null,
                2
        );
        assertThat(firstPage).containsExactly(first, anchor);

        CandidatePosition insertedBeforeAnchor = position(
                1, 0, 950_000, BASE_TIME,
                uuid("55555555-5555-4555-8555-555555555555")
        );
        CandidatePosition insertedAfterAnchor = position(
                1, 0, 750_000, BASE_TIME,
                uuid("66666666-6666-4666-8666-666666666666")
        );
        CandidatePosition movedPreviouslySeenRow = position(
                1, 0, 650_000, BASE_TIME, first.id()
        );

        List<CandidatePosition> currentRows = List.of(
                insertedBeforeAnchor,
                anchor,
                insertedAfterAnchor,
                third,
                movedPreviouslySeenRow
        ).stream().sorted(
                ProductSearchFullTextCursorContractTest::compare
        ).toList();

        List<CandidatePosition> nextPage =
                pageAfter(currentRows, anchor, 10);

        assertThat(nextPage).containsExactly(
                insertedAfterAnchor,
                third,
                movedPreviouslySeenRow
        );
        assertThat(nextPage)
                .doesNotContain(insertedBeforeAnchor, deleted);
    }

    private static List<CandidatePosition> orderedFixture() {
        CandidatePosition exactSku = position(
                0, 0, 0, BASE_TIME.minusSeconds(60),
                uuid("00000000-0000-4000-8000-000000000001")
        );
        CandidatePosition higherRank = position(
                1, 0, 900_000, BASE_TIME.minusSeconds(60),
                uuid("10000000-0000-4000-8000-000000000001")
        );
        CandidatePosition newer = position(
                1, 0, 800_000, BASE_TIME.plusSeconds(60),
                uuid("20000000-0000-4000-8000-000000000001")
        );
        CandidatePosition higherUnsignedUuid = position(
                1, 0, 800_000, BASE_TIME,
                uuid("ffffffff-ffff-ffff-ffff-ffffffffffff")
        );
        CandidatePosition lowerUnsignedUuid = position(
                1, 0, 800_000, BASE_TIME,
                uuid("00000000-0000-0000-0000-000000000000")
        );
        CandidatePosition foldedOnly = position(
                1, 1, 999_999, BASE_TIME.plusSeconds(120),
                uuid("30000000-0000-4000-8000-000000000001")
        );
        CandidatePosition skuPrefix = position(
                2, 0, 0, BASE_TIME.plusSeconds(180),
                uuid("40000000-0000-4000-8000-000000000001")
        );
        CandidatePosition remainingFts = position(
                4, 0, 500_000, BASE_TIME.plusSeconds(240),
                uuid("50000000-0000-4000-8000-000000000001")
        );

        return List.of(
                exactSku,
                higherRank,
                newer,
                higherUnsignedUuid,
                lowerUnsignedUuid,
                foldedOnly,
                skuPrefix,
                remainingFts
        );
    }

    private static List<CandidatePosition> pageAfter(
            List<CandidatePosition> rows,
            CandidatePosition anchor,
            int limit
    ) {
        return rows.stream()
                .sorted(ProductSearchFullTextCursorContractTest::compare)
                .filter(row -> anchor == null || compare(row, anchor) > 0)
                .limit(limit)
                .toList();
    }

    private static int compare(
            CandidatePosition left,
            CandidatePosition right
    ) {
        int compared = Integer.compare(
                left.matchTier(),
                right.matchTier()
        );
        if (compared != 0) {
            return compared;
        }

        compared = Integer.compare(
                left.surfaceFormPriority(),
                right.surfaceFormPriority()
        );
        if (compared != 0) {
            return compared;
        }

        compared = Integer.compare(
                right.rankScore(),
                left.rankScore()
        );
        if (compared != 0) {
            return compared;
        }

        compared = right.createdAt().compareTo(left.createdAt());
        if (compared != 0) {
            return compared;
        }

        compared = Long.compareUnsigned(
                right.id().getMostSignificantBits(),
                left.id().getMostSignificantBits()
        );
        if (compared != 0) {
            return compared;
        }

        return Long.compareUnsigned(
                right.id().getLeastSignificantBits(),
                left.id().getLeastSignificantBits()
        );
    }

    private static CandidatePosition position(
            int matchTier,
            int surfaceFormPriority,
            int rankScore,
            Instant createdAt,
            UUID id
    ) {
        return new CandidatePosition(
                matchTier,
                surfaceFormPriority,
                rankScore,
                createdAt,
                id
        );
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static void assertInvalidPayload(
            String cursor,
            ProductSearchCriteria criteria
    ) {
        assertInvalidCursor(() -> CandidateCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                criteria
        ));
    }

    private static void assertInvalidCursor(
            ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(
                        CatalogErrorCode.INVALID_PRODUCT_SEARCH_CURSOR
                ));
    }

    private static String withByte(
            String cursor,
            int offset,
            int value
    ) {
        byte[] payload = decodePayload(cursor);
        payload[offset] = (byte) value;
        return encodePayload(payload);
    }

    private static String withInt(
            String cursor,
            int offset,
            int value
    ) {
        byte[] payload = decodePayload(cursor);
        ByteBuffer.wrap(payload)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(offset, value);
        return encodePayload(payload);
    }

    private static String withLong(
            String cursor,
            int offset,
            long value
    ) {
        byte[] payload = decodePayload(cursor);
        ByteBuffer.wrap(payload)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(offset, value);
        return encodePayload(payload);
    }

    private static byte[] decodePayload(String cursor) {
        return Base64.getUrlDecoder().decode(cursor);
    }

    private static String encodePayload(byte[] payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload);
    }

    private record CandidatePosition(
            int matchTier,
            int surfaceFormPriority,
            int rankScore,
            Instant createdAt,
            UUID id
    ) {

        private CandidatePosition {
            if (matchTier < 0 || matchTier > 4) {
                throw new IllegalArgumentException(
                        "matchTier must be between 0 and 4"
                );
            }
            if (
                    surfaceFormPriority < 0
                            || surfaceFormPriority > 1
            ) {
                throw new IllegalArgumentException(
                        "surfaceFormPriority must be 0 or 1"
                );
            }
            if (rankScore < 0 || rankScore > 1_000_000) {
                throw new IllegalArgumentException(
                        "rankScore must be between 0 and 1000000"
                );
            }

            boolean skuTier = matchTier == 0 || matchTier == 2;
            if (
                    skuTier
                            && (
                            surfaceFormPriority != 0
                                    || rankScore != 0
                    )
            ) {
                throw new IllegalArgumentException(
                        "SKU tiers require surface priority and rank zero"
                );
            }

            Objects.requireNonNull(
                    createdAt,
                    "createdAt must not be null"
            );
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    private static final class CandidateCursorCodec {

        private static final byte VERSION = 2;
        private static final String CONTRACT = "product-search-v2";
        private static final int FINGERPRINT_BYTES = 32;

        private static final int VERSION_OFFSET = 0;
        private static final int TIER_OFFSET = 33;
        private static final int SURFACE_OFFSET = 37;
        private static final int RANK_OFFSET = 41;
        private static final int EPOCH_OFFSET = 45;
        private static final int NANO_OFFSET = 53;
        private static final int PAYLOAD_BYTES = 73;
        private static final int MAX_ENCODED_LENGTH = 128;

        private static final Base64.Encoder ENCODER =
                Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Decoder DECODER =
                Base64.getUrlDecoder();

        private CandidateCursorCodec() {
        }

        private static String encode(
                ProductSearchCursorScope scope,
                ProductSearchCriteria criteria,
                CandidatePosition position
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

            if (criteria.keyword() == null) {
                throw new IllegalArgumentException(
                        "candidate v2 cursor requires a keyword"
                );
            }

            ByteBuffer buffer = ByteBuffer
                    .allocate(PAYLOAD_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);

            buffer.put(VERSION);
            buffer.put(fingerprint(scope, criteria));
            buffer.putInt(position.matchTier());
            buffer.putInt(position.surfaceFormPriority());
            buffer.putInt(position.rankScore());
            buffer.putLong(position.createdAt().getEpochSecond());
            buffer.putInt(position.createdAt().getNano());
            buffer.putLong(position.id().getMostSignificantBits());
            buffer.putLong(position.id().getLeastSignificantBits());

            if (buffer.hasRemaining()) {
                throw new IllegalStateException(
                        "candidate cursor payload is incomplete"
                );
            }

            String encoded = ENCODER.encodeToString(buffer.array());
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalStateException(
                        "candidate cursor is too long"
                );
            }

            return encoded;
        }

        private static CandidatePosition decode(
                String cursor,
                ProductSearchCursorScope scope,
                ProductSearchCriteria criteria
        ) {
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(
                    criteria,
                    "criteria must not be null"
            );

            if (
                    criteria.keyword() == null
                            || !isCanonicalBase64Url(cursor)
            ) {
                throw invalidCursor();
            }

            byte[] payload;
            try {
                payload = DECODER.decode(cursor);
            } catch (IllegalArgumentException exception) {
                throw invalidCursor();
            }

            if (
                    payload.length != PAYLOAD_BYTES
                            || !ENCODER.encodeToString(payload).equals(cursor)
            ) {
                throw invalidCursor();
            }

            ByteBuffer buffer = ByteBuffer
                    .wrap(payload)
                    .order(ByteOrder.BIG_ENDIAN);

            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }

            byte[] actualFingerprint =
                    new byte[FINGERPRINT_BYTES];
            buffer.get(actualFingerprint);

            if (!MessageDigest.isEqual(
                    actualFingerprint,
                    fingerprint(scope, criteria)
            )) {
                throw invalidCursor();
            }

            int matchTier = buffer.getInt();
            int surfaceFormPriority = buffer.getInt();
            int rankScore = buffer.getInt();
            long epochSecond = buffer.getLong();
            int nano = buffer.getInt();
            UUID id = new UUID(
                    buffer.getLong(),
                    buffer.getLong()
            );

            if (
                    buffer.hasRemaining()
                            || nano < 0
                            || nano > 999_999_999
            ) {
                throw invalidCursor();
            }

            try {
                return new CandidatePosition(
                        matchTier,
                        surfaceFormPriority,
                        rankScore,
                        Instant.ofEpochSecond(epochSecond, nano),
                        id
                );
            } catch (
                    IllegalArgumentException
                    | DateTimeException exception
            ) {
                throw invalidCursor();
            }
        }

        private static byte[] fingerprint(
                ProductSearchCursorScope scope,
                ProductSearchCriteria criteria
        ) {
            MessageDigest digest = newDigest();
            updateField(digest, CONTRACT);
            updateField(digest, scope.fingerprintValue());
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

        private static MessageDigest newDigest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                        "SHA-256 is unavailable",
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

            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
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

        private static String normalizeKeyword(String value) {
            return Normalizer.normalize(
                    value.trim(),
                    Normalizer.Form.NFC
            ).toLowerCase(Locale.ROOT);
        }

        private static String normalizePrice(BigDecimal value) {
            if (value == null) {
                return null;
            }

            BigDecimal normalized = value.stripTrailingZeros();
            return normalized.signum() == 0
                    ? "0"
                    : normalized.toPlainString();
        }

        private static boolean isCanonicalBase64Url(String value) {
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
}