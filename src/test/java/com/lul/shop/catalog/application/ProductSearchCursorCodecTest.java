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
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchCursorCodecTest {

    private static final UUID POSITION_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final Instant POSITION_TIME =
            Instant.parse("2026-08-10T10:15:30.123456789Z");

    @Test
    void shouldRoundTripBrowseCursorAsCanonicalBase64Url() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        ProductSearchPosition position =
                ProductSearchPosition.browse(
                        POSITION_TIME,
                        POSITION_ID
                );

        String cursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                position
        );

        ProductSearchPosition decoded =
                ProductSearchCursorCodec.decode(
                        cursor,
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                );

        assertThat(decoded).isEqualTo(position);
        assertThat(cursor)
                .matches("[A-Za-z0-9_-]+")
                .doesNotContain("=");

        assertThat(decodePayload(cursor)).hasSize(62);
    }

    @Test
    void shouldRoundTripRankedCursor() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly("phone");

        ProductSearchPosition position =
                ProductSearchPosition.ranked(
                        1,
                        POSITION_TIME,
                        POSITION_ID
                );

        String cursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                position
        );

        assertThat(ProductSearchCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                criteria
        )).isEqualTo(position);

        assertThat(decodePayload(cursor)).hasSize(66);
    }

    @Test
    void shouldBindCursorToNormalizedCriteriaAndScope() {
        ProductSearchCriteria original =
                new ProductSearchCriteria(
                        " Phone ",
                        ProductStatus.ACTIVE,
                        new BigDecimal("10.00"),
                        new BigDecimal("100.000")
                );

        ProductSearchCriteria equivalent =
                new ProductSearchCriteria(
                        "phone",
                        ProductStatus.ACTIVE,
                        new BigDecimal("10.0"),
                        new BigDecimal("100")
                );

        ProductSearchPosition position =
                ProductSearchPosition.ranked(
                        0,
                        POSITION_TIME,
                        POSITION_ID
                );

        String cursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                original,
                position
        );

        assertThat(ProductSearchCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                equivalent
        )).isEqualTo(position);

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        cursor,
                        ProductSearchCursorScope.ADMIN,
                        equivalent
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        cursor,
                        ProductSearchCursorScope.PUBLIC,
                        new ProductSearchCriteria(
                                "tablet",
                                ProductStatus.ACTIVE,
                                new BigDecimal("10"),
                                new BigDecimal("100")
                        )
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        cursor,
                        ProductSearchCursorScope.PUBLIC,
                        new ProductSearchCriteria(
                                "phone",
                                ProductStatus.INACTIVE,
                                new BigDecimal("10"),
                                new BigDecimal("100")
                        )
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        cursor,
                        ProductSearchCursorScope.PUBLIC,
                        new ProductSearchCriteria(
                                "phone",
                                ProductStatus.ACTIVE,
                                new BigDecimal("11"),
                                new BigDecimal("100")
                        )
                )
        );
    }

    @Test
    void shouldRejectMalformedNonCanonicalAndOversizedInput() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        ProductSearchPosition position =
                ProductSearchPosition.browse(
                        POSITION_TIME,
                        POSITION_ID
                );

        String validCursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                position
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        null,
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        "",
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        "not+base64",
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        validCursor + "=",
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                )
        );

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        "A".repeat(129),
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                )
        );

        byte[] payload = decodePayload(validCursor);

        assertInvalidPayload(
                Arrays.copyOf(payload, payload.length - 1),
                criteria
        );

        assertInvalidPayload(
                Arrays.copyOf(payload, payload.length + 1),
                criteria
        );
    }

    @Test
    void shouldRejectUnsupportedVersionShapePriorityAndTimestamp() {
        ProductSearchCriteria browseCriteria =
                ProductSearchCriteria.activeOnly(null);

        String browseCursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                browseCriteria,
                ProductSearchPosition.browse(
                        POSITION_TIME,
                        POSITION_ID
                )
        );

        byte[] unsupportedVersion =
                decodePayload(browseCursor);
        unsupportedVersion[0] = 2;

        assertInvalidPayload(
                unsupportedVersion,
                browseCriteria
        );

        byte[] unexpectedPriorityFlag =
                decodePayload(browseCursor);
        unexpectedPriorityFlag[33] = 1;

        assertInvalidPayload(
                unexpectedPriorityFlag,
                browseCriteria
        );

        byte[] invalidNano = decodePayload(browseCursor);
        ByteBuffer.wrap(invalidNano)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(42, 1_000_000_000);

        assertInvalidPayload(
                invalidNano,
                browseCriteria
        );

        ProductSearchCriteria rankedCriteria =
                ProductSearchCriteria.activeOnly("phone");

        String rankedCursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                rankedCriteria,
                ProductSearchPosition.ranked(
                        1,
                        POSITION_TIME,
                        POSITION_ID
                )
        );

        byte[] invalidPriority =
                decodePayload(rankedCursor);
        ByteBuffer.wrap(invalidPriority)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(34, 3);

        assertInvalidPayload(
                invalidPriority,
                rankedCriteria
        );
    }

    @Test
    void shouldRejectPositionShapeThatDoesNotMatchSearchMode() {
        ProductSearchPosition browsePosition =
                ProductSearchPosition.browse(
                        POSITION_TIME,
                        POSITION_ID
                );

        ProductSearchPosition rankedPosition =
                ProductSearchPosition.ranked(
                        1,
                        POSITION_TIME,
                        POSITION_ID
                );

        assertThatThrownBy(() ->
                ProductSearchCursorCodec.encode(
                        ProductSearchCursorScope.PUBLIC,
                        ProductSearchCriteria.activeOnly(null),
                        rankedPosition
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "browse cursor must not contain matchPriority"
                );

        assertThatThrownBy(() ->
                ProductSearchCursorCodec.encode(
                        ProductSearchCursorScope.PUBLIC,
                        ProductSearchCriteria.activeOnly("phone"),
                        browsePosition
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "ranked search cursor requires matchPriority"
                );
    }

    @Test
    void shouldNotExpireCursorBasedOnPositionTimestamp() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        ProductSearchPosition oldPosition =
                ProductSearchPosition.browse(
                        Instant.parse("2000-01-01T00:00:00Z"),
                        POSITION_ID
                );

        String cursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                oldPosition
        );

        assertThat(ProductSearchCursorCodec.decode(
                cursor,
                ProductSearchCursorScope.PUBLIC,
                criteria
        )).isEqualTo(oldPosition);
    }

    @Test
    void shouldAcceptStructurallyValidModifiedPosition() {
        ProductSearchCriteria criteria =
                ProductSearchCriteria.activeOnly(null);

        String cursor = ProductSearchCursorCodec.encode(
                ProductSearchCursorScope.PUBLIC,
                criteria,
                ProductSearchPosition.browse(
                        POSITION_TIME,
                        POSITION_ID
                )
        );

        Instant changedTime =
                Instant.parse("2026-01-01T01:02:03.456Z");

        UUID changedId = UUID.fromString(
                "22222222-2222-4222-8222-222222222222"
        );

        byte[] payload = decodePayload(cursor);

        ByteBuffer buffer = ByteBuffer.wrap(payload)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.putLong(
                34,
                changedTime.getEpochSecond()
        );
        buffer.putInt(
                42,
                changedTime.getNano()
        );
        buffer.putLong(
                46,
                changedId.getMostSignificantBits()
        );
        buffer.putLong(
                54,
                changedId.getLeastSignificantBits()
        );

        String modifiedCursor = encodePayload(payload);

        assertThat(ProductSearchCursorCodec.decode(
                modifiedCursor,
                ProductSearchCursorScope.PUBLIC,
                criteria
        )).isEqualTo(
                ProductSearchPosition.browse(
                        changedTime,
                        changedId
                )
        );
    }

    private static void assertInvalidPayload(
            byte[] payload,
            ProductSearchCriteria criteria
    ) {
        String cursor = encodePayload(payload);

        assertInvalidCursor(() ->
                ProductSearchCursorCodec.decode(
                        cursor,
                        ProductSearchCursorScope.PUBLIC,
                        criteria
                )
        );
    }

    private static void assertInvalidCursor(
            ThrowingCallable callable
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                CatalogErrorCode
                                        .INVALID_PRODUCT_SEARCH_CURSOR
                        )
                );
    }

    private static byte[] decodePayload(String cursor) {
        return Base64.getUrlDecoder().decode(cursor);
    }

    private static String encodePayload(byte[] payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload);
    }
}