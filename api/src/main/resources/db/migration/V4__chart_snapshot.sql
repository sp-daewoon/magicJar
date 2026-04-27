-- V4: 차트 스크린샷 테이블 (ADR 0026 — 객체 저장소 MinIO 연동 준비)
-- source: CLIENT_LWC (LWC 공식 takeScreenshot), SERVER_BROWSER (pyworker Playwright)

CREATE TABLE IF NOT EXISTS chart_snapshot (
    id           UUID         PRIMARY KEY,
    trade_id     UUID         NOT NULL,
    symbol       VARCHAR(20)  NOT NULL DEFAULT '',
    timeframe    VARCHAR(16)  NOT NULL DEFAULT '',
    captured_at  TIMESTAMPTZ  NOT NULL,
    source       VARCHAR(20)  NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    public_url   VARCHAR(1024) NOT NULL,
    meta         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chart_snapshot_trade_id
    ON chart_snapshot (trade_id);
