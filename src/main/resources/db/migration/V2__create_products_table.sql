CREATE TABLE products (
    id              UUID PRIMARY KEY,
    sku             VARCHAR(100)  NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    description     TEXT,
    price           NUMERIC(19,2) NOT NULL,
    stock_quantity  INTEGER      NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    image_key       VARCHAR(500),
    image_url       VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_products_price_non_negative
        CHECK (price >= 0),

    CONSTRAINT chk_products_stock_non_negative
        CHECK (stock_quantity >= 0),

    CONSTRAINT chk_products_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX idx_products_sku_lower
    ON products (LOWER(sku));

CREATE INDEX idx_products_status
    ON products (status);

CREATE INDEX idx_products_name_lower
    ON products (LOWER(name));

CREATE INDEX idx_products_created_at
    ON products (created_at);