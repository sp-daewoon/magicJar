-- V12: Instrument Master 확장 (ADR 0037 T3)
-- 기존 instrument 테이블에 컬럼 추가 + 인덱스 보강
-- 상장폐지 종목 보존 의무 (행 삭제 X, delisted_at 설정)

ALTER TABLE instrument
    ADD COLUMN IF NOT EXISTS isin        VARCHAR(12),
    ADD COLUMN IF NOT EXISTS name_en     VARCHAR(128),
    ADD COLUMN IF NOT EXISTS type        VARCHAR(16) NOT NULL DEFAULT 'STOCK',
    ADD COLUMN IF NOT EXISTS sector      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS listed_at   DATE,
    ADD COLUMN IF NOT EXISTS delisted_at DATE,
    ADD COLUMN IF NOT EXISTS par_value   NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS trading_unit INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS active      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- market 컬럼 크기 확장 (ETF·ETN 추가)
ALTER TABLE instrument
    ALTER COLUMN market TYPE VARCHAR(16);

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_instrument_market ON instrument (market);
CREATE INDEX IF NOT EXISTS idx_instrument_status ON instrument (active);
CREATE INDEX IF NOT EXISTS idx_instrument_name   ON instrument (name);

-- 기존 레거시 name 컬럼을 name_kr alias로 인식 (이름 변경 없이 유지)
-- instrument.name = 한글명 (기존 컬럼 그대로 사용)

-- instrument_alias 테이블 (코드 별칭 — KRX·NAVER·FNGUIDE)
CREATE TABLE IF NOT EXISTS instrument_alias (
    id            BIGSERIAL    PRIMARY KEY,
    code          VARCHAR(12)  NOT NULL,
    alias_code    VARCHAR(32)  NOT NULL,
    alias_source  VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (alias_code, alias_source)
);

CREATE INDEX IF NOT EXISTS idx_instrument_alias_code ON instrument_alias (code);

-- kafka instrument.updated.v1 처리용 watermark 테이블
CREATE TABLE IF NOT EXISTS instrument_sync_log (
    id          BIGSERIAL    PRIMARY KEY,
    synced_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    market      VARCHAR(16)  NOT NULL,
    total_count INT          NOT NULL DEFAULT 0,
    new_count   INT          NOT NULL DEFAULT 0,
    updated_count INT        NOT NULL DEFAULT 0,
    delisted_count INT       NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS'
);
