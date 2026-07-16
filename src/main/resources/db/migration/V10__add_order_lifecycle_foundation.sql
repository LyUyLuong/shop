ALTER TABLE orders
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN inventory_released_at TIMESTAMPTZ;

UPDATE orders
SET expires_at = created_at + INTERVAL '30 minutes'
WHERE expires_at IS NULL;

ALTER TABLE orders
    ALTER COLUMN expires_at
        SET DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 minutes'),
    ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_expires_at_not_before_created_at
        CHECK (expires_at >= created_at),
    ADD CONSTRAINT chk_orders_inventory_released_at_not_before_created_at
        CHECK (
            inventory_released_at IS NULL
            OR inventory_released_at >= created_at
        );

CREATE INDEX idx_orders_pending_payment_expires_at
    ON orders (expires_at, id)
    WHERE status = 'PENDING_PAYMENT';

ALTER TABLE order_status_history
    DROP CONSTRAINT chk_order_status_history_actor_type;

ALTER TABLE order_status_history
    ADD CONSTRAINT chk_order_status_history_actor_type
        CHECK (
            actor_type IN (
                'ADMIN',
                'SYSTEM',
                'PAYMENT',
                'MARKETPLACE'
            )
        );