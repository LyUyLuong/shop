package com.lul.shop.ordering.domain;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PACKING,
    SHIPPED,
    COMPLETED,
    CANCELLED
}