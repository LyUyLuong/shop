ALTER TABLE orders
    ADD COLUMN recipient_name VARCHAR(100),
    ADD COLUMN recipient_phone VARCHAR(20),
    ADD COLUMN shipping_address VARCHAR(500),
    ADD COLUMN shipping_method VARCHAR(30),
    ADD COLUMN payment_mode VARCHAR(30),
    ADD COLUMN subtotal_amount NUMERIC(19, 2),
    ADD COLUMN shipping_fee NUMERIC(19, 2);

ALTER TABLE orders
    ALTER COLUMN expires_at DROP NOT NULL;

UPDATE orders
SET payment_mode = 'MOCK',
    subtotal_amount = total_amount,
    shipping_fee = 0.00;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_fulfillment_all_or_none
        CHECK (
            (
                recipient_name IS NULL
                AND recipient_phone IS NULL
                AND shipping_address IS NULL
                AND shipping_method IS NULL
            )
            OR
            (
                recipient_name IS NOT NULL
                AND recipient_phone IS NOT NULL
                AND shipping_address IS NOT NULL
                AND shipping_method IS NOT NULL
            )
        ),

    ADD CONSTRAINT chk_orders_recipient_name
        CHECK (
            recipient_name IS NULL
            OR (
                recipient_name = BTRIM(recipient_name)
                AND CHAR_LENGTH(recipient_name)
                    BETWEEN 2 AND 100
            )
        ),

    ADD CONSTRAINT chk_orders_recipient_phone
        CHECK (
            recipient_phone IS NULL
            OR recipient_phone ~ '^[+]?[0-9]{8,15}$'
        ),

    ADD CONSTRAINT chk_orders_shipping_address
        CHECK (
            shipping_address IS NULL
            OR (
                shipping_address = BTRIM(shipping_address)
                AND CHAR_LENGTH(shipping_address)
                    BETWEEN 10 AND 500
            )
        ),

    ADD CONSTRAINT chk_orders_shipping_method
        CHECK (
            shipping_method IS NULL
            OR shipping_method IN ('STANDARD')
        ),

    ADD CONSTRAINT chk_orders_payment_mode
        CHECK (
            payment_mode IS NULL
            OR payment_mode IN ('MOCK', 'COD')
        ),

    ADD CONSTRAINT chk_orders_pricing_shape
        CHECK (
            (
                payment_mode IS NULL
                AND subtotal_amount IS NULL
                AND shipping_fee IS NULL
            )
            OR
            (
                payment_mode IS NOT NULL
                AND subtotal_amount IS NOT NULL
                AND shipping_fee IS NOT NULL
            )
        ),

    ADD CONSTRAINT chk_orders_fulfillment_requires_pricing
        CHECK (
            recipient_name IS NULL
            OR payment_mode IS NOT NULL
        ),

    ADD CONSTRAINT chk_orders_cod_requires_fulfillment
        CHECK (
            payment_mode IS DISTINCT FROM 'COD'
            OR recipient_name IS NOT NULL
        ),

    ADD CONSTRAINT chk_orders_subtotal_non_negative
        CHECK (
            subtotal_amount IS NULL
            OR subtotal_amount >= 0
        ),

    ADD CONSTRAINT chk_orders_shipping_fee_non_negative
        CHECK (
            shipping_fee IS NULL
            OR shipping_fee >= 0
        ),

    ADD CONSTRAINT chk_orders_amounts_match
        CHECK (
            subtotal_amount IS NULL
            OR total_amount =
                subtotal_amount + shipping_fee
        ),

    ADD CONSTRAINT chk_orders_payment_deadline
        CHECK (
            payment_mode IS NULL
            OR (
                payment_mode = 'MOCK'
                AND expires_at IS NOT NULL
            )
            OR (
                payment_mode = 'COD'
                AND expires_at IS NULL
            )
        ),

    ADD CONSTRAINT chk_orders_payment_mode_status
        CHECK (
            payment_mode IS NULL
            OR (
                payment_mode = 'MOCK'
                AND status IN (
                    'PENDING_PAYMENT',
                    'PAID',
                    'PACKING',
                    'SHIPPED',
                    'COMPLETED',
                    'CANCELLED',
                    'EXPIRED'
                )
            )
            OR (
                payment_mode = 'COD'
                AND status IN (
                    'CONFIRMED',
                    'PACKING',
                    'SHIPPED',
                    'COMPLETED',
                    'CANCELLED'
                )
            )
        );

ALTER TABLE payments
    DROP CONSTRAINT chk_payments_method;

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_method
        CHECK (method IN ('MOCK', 'COD'));

CREATE INDEX idx_orders_user_created_at_id_desc
    ON orders (
        user_id,
        created_at DESC,
        id DESC
    );