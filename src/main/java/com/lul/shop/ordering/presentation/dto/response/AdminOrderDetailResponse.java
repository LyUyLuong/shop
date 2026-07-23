package com.lul.shop.ordering.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lul.shop.ordering.application.dto.AdminOrderDetailResult;
import com.lul.shop.ordering.presentation.OrderItemImageUrlResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminOrderDetailResponse(
        UUID id,
        UUID userId,
        String status,
        String paymentMode,
        BigDecimal subtotalAmount,
        BigDecimal shippingFee,
        BigDecimal totalAmount,

        @JsonInclude(JsonInclude.Include.ALWAYS)
        OrderFulfillmentResponse fulfillment,

        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminOrderDetailResponse from(
            AdminOrderDetailResult result,
            OrderItemImageUrlResolver imageUrlResolver
    ) {
        return new AdminOrderDetailResponse(
                result.id(),
                result.userId(),
                result.status().name(),
                result.paymentMode().name(),
                result.subtotalAmount(),
                result.shippingFee(),
                result.totalAmount(),
                OrderFulfillmentResponse.from(
                        result.fulfillment()
                ),
                result.items()
                        .stream()
                        .map(item ->
                                OrderItemResponse.from(
                                        item,
                                        imageUrlResolver.adminImageUrl(
                                                result.id(),
                                                item.id(),
                                                item.productImageKey()
                                        )
                                )
                        )
                        .toList(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}