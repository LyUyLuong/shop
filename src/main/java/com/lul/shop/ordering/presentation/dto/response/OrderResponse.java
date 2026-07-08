package com.lul.shop.ordering.presentation.dto.response;

import com.lul.shop.ordering.application.dto.OrderResult;
import com.lul.shop.ordering.presentation.OrderItemImageUrlResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID userId,
        String status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {

    public static OrderResponse from(OrderResult result, OrderItemImageUrlResolver imageUrlResolver) {
        return new OrderResponse(
                result.id(),
                result.userId(),
                result.status().name(),
                result.totalAmount(),
                result.items()
                        .stream()
                        .map(item -> OrderItemResponse.from(
                                item,
                                imageUrlResolver.customerImageUrl(result.id(), item.id(), item.productImageKey())
                        ))
                        .toList(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}