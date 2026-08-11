package com.lul.shop.catalog.application;

public enum ProductSearchCursorScope {

    PUBLIC("PUBLIC"),
    ADMIN("ADMIN");

    private final String fingerprintValue;

    ProductSearchCursorScope(String fingerprintValue) {
        this.fingerprintValue = fingerprintValue;
    }

    String fingerprintValue() {
        return fingerprintValue;
    }
}