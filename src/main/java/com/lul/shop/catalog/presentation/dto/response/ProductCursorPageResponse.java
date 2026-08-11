package com.lul.shop.catalog.presentation.dto.response;

import com.lul.shop.catalog.application.dto.ProductCursorPageResult;

import java.util.List;
import java.util.Objects;

public record ProductCursorPageResponse(
        List<ProductResponse> content,
        int size,
        boolean hasNext,
        String nextCursor
) {

    public ProductCursorPageResponse {
        content = List.copyOf(
                Objects.requireNonNull(
                        content,
                        "content must not be null"
                )
        );
    }

    public static ProductCursorPageResponse from(
            ProductCursorPageResult result
    ) {
        Objects.requireNonNull(
                result,
                "result must not be null"
        );

        return new ProductCursorPageResponse(
                result.content()
                        .stream()
                        .map(ProductResponse::from)
                        .toList(),
                result.size(),
                result.hasNext(),
                result.nextCursor()
        );
    }
}