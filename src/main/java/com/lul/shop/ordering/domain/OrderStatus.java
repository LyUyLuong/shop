package com.lul.shop.ordering.domain;

public enum OrderStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    PAID,
    PACKING,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    EXPIRED
}