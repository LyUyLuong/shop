CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,
    event_type      VARCHAR(80)   NOT NULL,
    aggregate_type  VARCHAR(80)   NOT NULL,
    aggregate_id    UUID          NOT NULL,
    payload         TEXT          NOT NULL,
    status          VARCHAR(30)   NOT NULL,
    retry_count     INTEGER       NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('NEW', 'PUBLISHED', 'FAILED')),

    CONSTRAINT chk_outbox_events_retry_count
        CHECK (retry_count >= 0),

    CONSTRAINT chk_outbox_events_published_state
        CHECK (
            (status = 'PUBLISHED' AND published_at IS NOT NULL)
            OR
            (status <> 'PUBLISHED' AND published_at IS NULL)
        )
);

CREATE INDEX idx_outbox_events_status_created_at
    ON outbox_events (status, created_at);

CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

CREATE INDEX idx_outbox_events_event_type
    ON outbox_events (event_type);