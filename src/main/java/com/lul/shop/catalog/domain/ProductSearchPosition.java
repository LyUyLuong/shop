package com.lul.shop.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProductSearchPosition(
        Integer matchPriority,
        Instant createdAt,
        UUID id
) {

    private static final int MIN_MATCH_PRIORITY = 0;
    private static final int MAX_MATCH_PRIORITY = 2;

    public ProductSearchPosition {
        if (
                matchPriority != null
                        && (
                        matchPriority < MIN_MATCH_PRIORITY
                                || matchPriority > MAX_MATCH_PRIORITY
                )
        ) {
            throw new IllegalArgumentException(
                    "matchPriority must be between 0 and 2"
            );
        }

        createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        id = Objects.requireNonNull(
                id,
                "id must not be null"
        );
    }

    public static ProductSearchPosition browse(
            Instant createdAt,
            UUID id
    ) {
        return new ProductSearchPosition(
                null,
                createdAt,
                id
        );
    }

    public static ProductSearchPosition ranked(
            int matchPriority,
            Instant createdAt,
            UUID id
    ) {
        return new ProductSearchPosition(
                matchPriority,
                createdAt,
                id
        );
    }

    public boolean hasMatchPriority() {
        return matchPriority != null;
    }

    public int requiredMatchPriority() {
        if (matchPriority == null) {
            throw new IllegalStateException(
                    "matchPriority is required for ranked search"
            );
        }

        return matchPriority;
    }
}