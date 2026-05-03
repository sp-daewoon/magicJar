-- V16: Screener 테이블 (T10 — ADR 0040)
-- screen_run: 스크리닝 실행 이력
-- screen_match: 스크리닝 매칭 종목
-- instrument_index_member: KOSPI200 / KOSDAQ150 등 지수 구성 종목

-- 지수 구성 종목 테이블
-- index_group: KOSPI200 / KOSDAQ150 / KOSPI100 / KRX300 / ETF_BENCHMARK 등
CREATE TABLE IF NOT EXISTS instrument_index_member (
    id          BIGSERIAL   PRIMARY KEY,
    code        VARCHAR(12) NOT NULL,
    index_group VARCHAR(32) NOT NULL,
    added_at    DATE        NOT NULL DEFAULT CURRENT_DATE,
    removed_at  DATE,
    UNIQUE (code, index_group)
);

CREATE INDEX IF NOT EXISTS idx_iim_index_group ON instrument_index_member (index_group);
CREATE INDEX IF NOT EXISTS idx_iim_code        ON instrument_index_member (code);

-- 스크리닝 실행 이력
CREATE TABLE IF NOT EXISTS screen_run (
    id              BIGSERIAL    PRIMARY KEY,
    run_id          VARCHAR(36)  NOT NULL UNIQUE,
    strategy_yaml   TEXT         NOT NULL,
    universe_type   VARCHAR(32)  NOT NULL,
    universe_detail JSONB,
    as_of_date      DATE         NOT NULL,
    result_json     JSONB,
    total_scanned   INT          NOT NULL DEFAULT 0,
    match_count     INT          NOT NULL DEFAULT 0,
    skip_count      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    user_id         BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_screen_run_user_id    ON screen_run (user_id);
CREATE INDEX IF NOT EXISTS idx_screen_run_as_of_date ON screen_run (as_of_date DESC);
CREATE INDEX IF NOT EXISTS idx_screen_run_status     ON screen_run (status);

-- 스크리닝 매칭 종목
CREATE TABLE IF NOT EXISTS screen_match (
    id             BIGSERIAL    PRIMARY KEY,
    screen_run_id  BIGINT       NOT NULL REFERENCES screen_run(id) ON DELETE CASCADE,
    code           VARCHAR(12)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    entry_reason   JSONB        NOT NULL DEFAULT '[]',
    matched_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_screen_match_run_id ON screen_match (screen_run_id);
CREATE INDEX IF NOT EXISTS idx_screen_match_code   ON screen_match (code);
