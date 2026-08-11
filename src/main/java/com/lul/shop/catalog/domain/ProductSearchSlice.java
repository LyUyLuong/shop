package com.lul.shop.catalog.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public record ProductSearchSlice<T>(
        List<T> content,
        boolean hasNext,
        Optional<ProductSearchPosition> nextPosition
) {

    public ProductSearchSlice {
        content = List.copyOf(
                Objects.requireNonNull(
                        content,
                        "content must not be null"
                )
        );
        nextPosition = Objects.requireNonNull(
                nextPosition,
                "nextPosition must not be null"
        );

        if (hasNext && content.isEmpty()) {
            throw new IllegalArgumentException(
                    "a continued slice must contain at least one item"
            );
        }

        if (hasNext != nextPosition.isPresent()) {
            throw new IllegalArgumentException(
                    "nextPosition must be present exactly when hasNext is true"
            );
        }
    }

    public static <T> ProductSearchSlice<T> terminal(
            List<T> content
    ) {
        return new ProductSearchSlice<>(
                content,
                false,
                Optional.empty()
        );
    }

    public static <T> ProductSearchSlice<T> continued(
            List<T> content,
            ProductSearchPosition nextPosition
    ) {
        return new ProductSearchSlice<>(
                content,
                true,
                Optional.of(
                        Objects.requireNonNull(
                                nextPosition,
                                "nextPosition must not be null"
                        )
                )
        );
    }

    public <R> ProductSearchSlice<R> map(
            Function<T, R> mapper
    ) {
        Objects.requireNonNull(
                mapper,
                "mapper must not be null"
        );

        return new ProductSearchSlice<>(
                content.stream()
                        .map(mapper)
                        .toList(),
                hasNext,
                nextPosition
        );
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }
}