-- V27__notification_outbox.sql
-- T-NOTIFY Phase 1 사전 baseline (ADR 0044·0070 §3-3 + spec §2-4)
-- IF NOT EXISTS LOCK (헌법 §6 + ADR 0068 §15-1 hotfix 회피 패턴)

CREATE TABLE IF NOT EXISTS notification_outbox (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    channel         VARCHAR(16)  NOT NULL,           -- SLACK · (FUTURE: EMAIL · SMS · PUSH · DISCORD)
    priority        VARCHAR(4)   NOT NULL,            -- P0 · P1 · P2
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL,            -- PENDING · SENT · FAILED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_outbox_event_channel
    ON notification_outbox (event_id, channel);

CREATE INDEX IF NOT EXISTS ix_notification_outbox_status_created
    ON notification_outbox (status, created_at);

-- system_config 컬럼 ALTER (ADR 0046 AES-256-GCM 정합 — BYTEA 단일 row)
ALTER TABLE system_config ADD COLUMN IF NOT EXISTS notification_slack_webhook BYTEA;
