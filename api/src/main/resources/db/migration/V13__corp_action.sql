-- V13: Corp Action 테이블 (ADR 0038 T4)
-- 액면분할·무상증자·유상증자·배당·합병·분할 이벤트 저장
-- retroactive adjusted price 계산의 원본 데이터
-- 멱등성: instrument_code + type + ex_date UNIQUE

CREATE TABLE IF NOT EXISTS corp_action (
    id              BIGSERIAL       PRIMARY KEY,
    instrument_code VARCHAR(12)     NOT NULL,
    type            VARCHAR(24)     NOT NULL,   -- SPLIT|BONUS_ISSUE|RIGHTS_ISSUE|DIVIDEND|MERGER|SPINOFF
    ex_date         DATE            NOT NULL,
    payload         JSONB           NOT NULL DEFAULT '{}',
    source          VARCHAR(16)     NOT NULL DEFAULT 'KIS',  -- KIS|DART|MANUAL
    applied_at      TIMESTAMPTZ,                -- AdjustmentJob이 candle 보정 완료 시점
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT corp_action_code_type_exdate_uq UNIQUE (instrument_code, type, ex_date)
);

-- 종목코드 + ex_date 범위 조회 인덱스
CREATE INDEX IF NOT EXISTS idx_corp_action_code_exdate
    ON corp_action (instrument_code, ex_date DESC);

-- 미적용 Corp Action 조회 인덱스 (applied_at IS NULL)
CREATE INDEX IF NOT EXISTS idx_corp_action_unapplied
    ON corp_action (instrument_code, applied_at)
    WHERE applied_at IS NULL;

-- payload JSONB 상세 인덱스 (ratio, cash_amount 등 조회 최적화)
CREATE INDEX IF NOT EXISTS idx_corp_action_payload
    ON corp_action USING gin (payload);

-- instrument 테이블 FK (상장폐지 종목도 corp_action 보존 — FK는 NOT deferrable)
-- instrument 테이블이 V0에서 생성되어 있으므로 FK 추가
ALTER TABLE corp_action
    ADD CONSTRAINT fk_corp_action_instrument
    FOREIGN KEY (instrument_code) REFERENCES instrument (code)
    ON DELETE RESTRICT;

-- COMMENT
COMMENT ON TABLE corp_action IS
    'KIS 예탁원정보 API 기반 Corp Action 이력. retroactive adjusted price 계산의 원본. (ADR 0038)';

COMMENT ON COLUMN corp_action.type IS
    'SPLIT: 액면분할, BONUS_ISSUE: 무상증자, RIGHTS_ISSUE: 유상증자, DIVIDEND: 배당, MERGER: 합병, SPINOFF: 분할';

COMMENT ON COLUMN corp_action.payload IS
    'type별 상세 정보. SPLIT:{ratio}, BONUS_ISSUE:{ratio}, RIGHTS_ISSUE:{ratio,subscription_price}, DIVIDEND:{cash_amount,stock_ratio}, MERGER:{merge_ratio,surviving_code}, SPINOFF:{split_ratio}';

COMMENT ON COLUMN corp_action.applied_at IS
    'candle.adjusted 컬럼 보정 완료 시점. NULL = 미보정 (CorpActionBackfillJob 처리 대기)';
