-- Phase B-2: market-data 모듈 영속 스키마.
-- 종목 마스터, 캔들(일/분봉), 뉴스/공시.

CREATE TABLE IF NOT EXISTS instrument (
    code        VARCHAR(12)  PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    market      VARCHAR(16)  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS candle (
    code         VARCHAR(12)    NOT NULL,
    interval     VARCHAR(8)     NOT NULL,
    open_time    TIMESTAMPTZ    NOT NULL,
    open         NUMERIC(18, 4) NOT NULL,
    high         NUMERIC(18, 4) NOT NULL,
    low          NUMERIC(18, 4) NOT NULL,
    close        NUMERIC(18, 4) NOT NULL,
    volume       BIGINT         NOT NULL,
    trade_count  BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (code, interval, open_time)
);

CREATE INDEX IF NOT EXISTS idx_candle_code_interval_open_time
    ON candle (code, interval, open_time);

CREATE TABLE IF NOT EXISTS news_item (
    id            UUID         PRIMARY KEY,
    source        VARCHAR(32)  NOT NULL,
    published_at  TIMESTAMPTZ  NOT NULL,
    title         VARCHAR(512) NOT NULL,
    body          TEXT         NOT NULL,
    instruments   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_news_published_at
    ON news_item (published_at DESC);
