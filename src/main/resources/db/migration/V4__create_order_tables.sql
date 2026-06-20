CREATE TABLE orders (
    id            UUID PRIMARY KEY,
    user_id       UUID           NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status        VARCHAR(30)    NOT NULL,
    total_amount  NUMERIC(19, 2) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,

    CONSTRAINT chk_orders_total_amount_non_negative
        CHECK (total_amount >= 0)
);

CREATE INDEX idx_orders_user_id
    ON orders (user_id);

CREATE INDEX idx_orders_status
    ON orders (status);

CREATE INDEX idx_orders_created_at
    ON orders (created_at);

CREATE TABLE order_items (
    id            UUID           PRIMARY KEY,
    order_id      UUID           NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id    UUID           NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    product_sku   VARCHAR(100)   NOT NULL,
    product_name  VARCHAR(255)   NOT NULL,
    unit_price    NUMERIC(19, 2) NOT NULL,
    quantity      INTEGER        NOT NULL,
    line_total    NUMERIC(19, 2) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,

    CONSTRAINT chk_order_items_unit_price_non_negative
        CHECK (unit_price >= 0),

    CONSTRAINT chk_order_items_quantity_positive
        CHECK (quantity > 0),

    CONSTRAINT chk_order_items_line_total_non_negative
        CHECK (line_total >= 0)
);

CREATE INDEX idx_order_items_order_id
    ON order_items (order_id);

CREATE INDEX idx_order_items_product_id
    ON order_items (product_id);