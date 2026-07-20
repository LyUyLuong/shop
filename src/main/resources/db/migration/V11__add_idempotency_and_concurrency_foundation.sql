ALTER TABLE carts
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE products
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;


CREATE TABLE order_idempotency_records (
    id                   UUID         PRIMARY KEY,
    user_id              UUID         NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key      VARCHAR(100) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    order_id             UUID
        REFERENCES orders(id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_order_idempotency_user_key
        UNIQUE (user_id, idempotency_key),

    CONSTRAINT chk_order_idempotency_key
        CHECK (
            CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 100
            AND idempotency_key ~ '^[A-Za-z0-9._:-]+$'
        ),

    CONSTRAINT chk_order_idempotency_fingerprint
        CHECK (
            request_fingerprint ~ '^[0-9a-f]{64}$'
        ),

    CONSTRAINT chk_order_idempotency_status
        CHECK (
            status IN ('PROCESSING', 'COMPLETED')
        ),

    CONSTRAINT chk_order_idempotency_result
        CHECK (
            (status = 'PROCESSING' AND order_id IS NULL)
            OR
            (status = 'COMPLETED' AND order_id IS NOT NULL)
        ),

    CONSTRAINT chk_order_idempotency_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_order_idempotency_created_at
    ON order_idempotency_records (created_at);

CREATE INDEX idx_order_idempotency_order_id
    ON order_idempotency_records (order_id)
    WHERE order_id IS NOT NULL;


CREATE TABLE payment_idempotency_records (
    id                   UUID         PRIMARY KEY,
    user_id              UUID         NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key      VARCHAR(100) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    payment_id           UUID
        REFERENCES payments(id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_payment_idempotency_user_key
        UNIQUE (user_id, idempotency_key),

    CONSTRAINT chk_payment_idempotency_key
        CHECK (
            CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 100
            AND idempotency_key ~ '^[A-Za-z0-9._:-]+$'
        ),

    CONSTRAINT chk_payment_idempotency_fingerprint
        CHECK (
            request_fingerprint ~ '^[0-9a-f]{64}$'
        ),

    CONSTRAINT chk_payment_idempotency_status
        CHECK (
            status IN ('PROCESSING', 'COMPLETED')
        ),

    CONSTRAINT chk_payment_idempotency_result
        CHECK (
            (status = 'PROCESSING' AND payment_id IS NULL)
            OR
            (status = 'COMPLETED' AND payment_id IS NOT NULL)
        ),

    CONSTRAINT chk_payment_idempotency_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_payment_idempotency_created_at
    ON payment_idempotency_records (created_at);

CREATE INDEX idx_payment_idempotency_payment_id
    ON payment_idempotency_records (payment_id)
    WHERE payment_id IS NOT NULL;
