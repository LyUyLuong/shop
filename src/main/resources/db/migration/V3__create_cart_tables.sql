CREATE TABLE carts (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_carts_user_id
    ON carts (user_id);

CREATE TABLE cart_items (
    id          UUID PRIMARY KEY,
    cart_id     UUID        NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id  UUID        NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity    INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_cart_items_quantity_positive
        CHECK (quantity > 0)
);

CREATE UNIQUE INDEX idx_cart_items_cart_product
    ON cart_items (cart_id, product_id);

CREATE INDEX idx_cart_items_product_id
    ON cart_items (product_id);