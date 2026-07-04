package com.lul.shop.ordering.presentation.dto.request;

import com.lul.shop.ordering.domain.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeOrderStatusRequest(
        @NotNull
        OrderStatus status,

        @NotBlank
        @Size(max = 500)
        String reason
) {
}