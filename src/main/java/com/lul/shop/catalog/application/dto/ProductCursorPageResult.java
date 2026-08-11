package com.lul.shop.catalog.application.dto;

import java.util.List;
import java.util.Objects;

public record ProductCursorPageResult(
        List<ProductResult> content,
        int size,
        boolean hasNext,
        String nextCursor
) {

    public ProductCursorPageResult {
        content = List.copyOf(
                Objects.requireNonNull(
                        content,
                        "content must not be null"
                )
        );

        if (size < 1) {
            throw new IllegalArgumentException(
                    "size must be >= 1"
            );
        }

        if (content.size() > size) {
            throw new IllegalArgumentException(
                    "content must not contain more than size items"
            );
        }

        if (hasNext && content.isEmpty()) {
            throw new IllegalArgumentException(
                    "a continued cursor page must contain at least one item"
            );
        }

        if (
                hasNext
                        && (
                        nextCursor == null
                                || nextCursor.isBlank()
                )
        ) {
            throw new IllegalArgumentException(
                    "nextCursor is required when hasNext is true"
            );
        }

        if (!hasNext && nextCursor != null) {
            throw new IllegalArgumentException(
                    "nextCursor must be null when hasNext is false"
            );
        }
    }
}