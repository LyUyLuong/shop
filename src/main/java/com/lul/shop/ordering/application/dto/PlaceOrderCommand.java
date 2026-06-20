package com.lul.shop.ordering.application.dto;

import java.util.Objects;
import java.util.UUID;

public record PlaceOrderCommand(
        UUID userId
) {

    public PlaceOrderCommand {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}