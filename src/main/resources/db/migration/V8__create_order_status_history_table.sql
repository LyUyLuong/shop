CREATE TABLE order_status_history (
    id            UUID         PRIMARY KEY,
    order_id      UUID         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status   VARCHAR(30)  NOT NULL,
    to_status     VARCHAR(30)  NOT NULL,
    actor_type    VARCHAR(30)  NOT NULL,
    actor_user_id UUID         NULL REFERENCES users(id) ON DELETE RESTRICT,
    reason        VARCHAR(500) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_order_status_history_status_changed
        CHECK (from_status <> to_status),

    CONSTRAINT chk_order_status_history_actor_type
        CHECK (actor_type IN ('ADMIN', 'SYSTEM', 'MARKETPLACE')),

    CONSTRAINT chk_order_status_history_admin_actor_user
        CHECK (actor_type <> 'ADMIN' OR actor_user_id IS NOT NULL),

    CONSTRAINT chk_order_status_history_reason_not_blank
        CHECK (length(trim(reason)) > 0)
);

CREATE INDEX idx_order_status_history_order_id_created_at
    ON order_status_history (order_id, created_at);

CREATE INDEX idx_order_status_history_actor_user_id
    ON order_status_history (actor_user_id);

CREATE INDEX idx_order_status_history_to_status
    ON order_status_history (to_status);