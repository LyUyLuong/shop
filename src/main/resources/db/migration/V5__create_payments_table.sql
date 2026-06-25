CREATE TABLE payments (
    id              UUID           PRIMARY KEY,
    order_id        UUID           NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    user_id         UUID           NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    method          VARCHAR(30)    NOT NULL,
    status          VARCHAR(30)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    paid_at         TIMESTAMPTZ,
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_payments_order_id
        UNIQUE (order_id),

    CONSTRAINT chk_payments_method
        CHECK (method IN ('MOCK')),

    CONSTRAINT chk_payments_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),

    CONSTRAINT chk_payments_amount_non_negative
        CHECK (amount >= 0),

    CONSTRAINT chk_payments_status_fields
        CHECK (
            (status = 'PENDING' AND paid_at IS NULL AND failure_reason IS NULL)
            OR
            (status = 'SUCCEEDED' AND paid_at IS NOT NULL AND failure_reason IS NULL)
            OR
            (status = 'FAILED' AND paid_at IS NULL AND failure_reason IS NOT NULL)
        )
);

CREATE INDEX idx_payments_user_id
    ON payments (user_id);

CREATE INDEX idx_payments_status
    ON payments (status);

CREATE INDEX idx_payments_created_at
    ON payments (created_at);