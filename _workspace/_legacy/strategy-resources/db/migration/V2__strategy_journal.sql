-- V2: 전략 정의 + 매매일지 (ADR 0007)
-- 작성: strategy-engine, Phase B-2

CREATE TABLE IF NOT EXISTS strategy (
    id              VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    condition_expr  TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS trade_journal (
    id              UUID         PRIMARY KEY,
    trade_id        UUID         NOT NULL,
    mode            VARCHAR(16)  NOT NULL,
    code            VARCHAR(16)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    side            VARCHAR(8)   NOT NULL,
    entry_at        TIMESTAMPTZ  NOT NULL,
    entry_price     NUMERIC(20,4) NOT NULL,
    entry_qty       INT          NOT NULL,
    exit_at         TIMESTAMPTZ,
    exit_price      NUMERIC(20,4),
    exit_qty        INT,
    pnl             NUMERIC(20,4),
    pnl_rate        NUMERIC(12,6),
    strategy_id     VARCHAR(64)  NOT NULL,
    entry_snapshot  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    exit_snapshot   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    entry_reason    TEXT         NOT NULL,
    exit_reason     TEXT,
    ai_explanation  TEXT,
    ai_review       TEXT,
    tags            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    user_note       TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_trade_journal_code        ON trade_journal (code);
CREATE INDEX IF NOT EXISTS idx_trade_journal_strategy_id ON trade_journal (strategy_id);
CREATE INDEX IF NOT EXISTS idx_trade_journal_entry_at    ON trade_journal (entry_at);
CREATE INDEX IF NOT EXISTS idx_trade_journal_mode        ON trade_journal (mode);
CREATE INDEX IF NOT EXISTS idx_trade_journal_trade_id    ON trade_journal (trade_id);
