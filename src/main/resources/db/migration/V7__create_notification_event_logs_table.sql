CREATE TABLE notification_event_logs (
    id              UUID         PRIMARY KEY,
    event_id        UUID         NOT NULL,
    event_type      VARCHAR(80)  NOT NULL,
    aggregate_type  VARCHAR(80)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    payload         TEXT,
    status          VARCHAR(30)  NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_notification_event_logs_event_id
        UNIQUE (event_id),

    CONSTRAINT chk_notification_event_logs_status
        CHECK (status IN ('PROCESSED'))
);

CREATE INDEX idx_notification_event_logs_event_type
    ON notification_event_logs (event_type);

CREATE INDEX idx_notification_event_logs_aggregate
    ON notification_event_logs (aggregate_type, aggregate_id);

CREATE INDEX idx_notification_event_logs_processed_at
    ON notification_event_logs (processed_at);