package com.lul.shop.ordering.domain;

import java.time.Instant;

public record OrderSearchCriteria(
        OrderStatus status,
        Instant createdFrom,
        Instant createdTo
) {
    public OrderSearchCriteria {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom must be <= createdTo");
        }
    }

    public static OrderSearchCriteria empty() {
        return new OrderSearchCriteria(null, null, null);
    }
}