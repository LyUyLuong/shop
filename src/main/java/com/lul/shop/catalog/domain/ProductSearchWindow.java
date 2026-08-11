package com.lul.shop.catalog.domain;

import java.util.Objects;
import java.util.Optional;

public record ProductSearchWindow(
        int size,
        Optional<ProductSearchPosition> afterPosition
) {

    public ProductSearchWindow {
        if (size < 1) {
            throw new IllegalArgumentException(
                    "size must be >= 1"
            );
        }

        afterPosition = Objects.requireNonNull(
                afterPosition,
                "afterPosition must not be null"
        );
    }

    public static ProductSearchWindow first(int size) {
        return new ProductSearchWindow(
                size,
                Optional.empty()
        );
    }

    public static ProductSearchWindow after(
            int size,
            ProductSearchPosition position
    ) {
        return new ProductSearchWindow(
                size,
                Optional.of(
                        Objects.requireNonNull(
                                position,
                                "position must not be null"
                        )
                )
        );
    }

    public boolean isFirst() {
        return afterPosition.isEmpty();
    }
}