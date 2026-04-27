-- V5: 백테스트 실행 메타데이터 테이블 (spec §1-A)
-- api-app이 runId 발급, batch-app이 실행 완료 후 status/summary 업데이트.

CREATE TABLE IF NOT EXISTS backtest_run (
    run_id       VARCHAR(64)  PRIMARY KEY,
    strategy_id  VARCHAR(64)  NOT NULL,
    symbol       VARCHAR(16)  NOT NULL,
    from_date    VARCHAR(16)  NOT NULL,
    to_date      VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    summary_json TEXT,
    error_message TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_backtest_run_strategy_id
    ON backtest_run (strategy_id);
