-- V14: candle 테이블에 adjusted price 컬럼 추가 (ADR 0038 T4)
--
-- 기존 open/high/low/close 컬럼은 raw(원주가)로 유지.
-- adjusted 컬럼은 Corp Action 보정 후 수정주가.
-- retroactive: CorpActionBackfillJob이 corp_action 발생 시 일괄 재계산.
--
-- NULL 허용: 초기 적재 시 adjusted 미계산 상태를 NULL로 표현.
--   - NULL = 아직 보정 미완료 또는 Corp Action 없음
--   - NOT NULL = Corp Action 보정 완료

ALTER TABLE candle
    ADD COLUMN IF NOT EXISTS open_adjusted   NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS high_adjusted   NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS low_adjusted    NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS close_adjusted  NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS volume_adjusted BIGINT,
    ADD COLUMN IF NOT EXISTS adjustment_factor NUMERIC(20, 8);  -- 누적 보정 계수 (e.g. 0.02000000 = 1/50 분할)

-- 수정주가 조회용 복합 인덱스 (code + interval + open_time DESC)
CREATE INDEX IF NOT EXISTS idx_candle_code_interval_time
    ON candle (code, interval, open_time DESC);

COMMENT ON COLUMN candle.open_adjusted IS
    '수정 시가. Corp Action 보정 후 값. NULL = 미보정.';
COMMENT ON COLUMN candle.close_adjusted IS
    '수정 종가. Corp Action 보정 후 값. NULL = 미보정. 차트 기본 표시 컬럼.';
COMMENT ON COLUMN candle.adjustment_factor IS
    '누적 보정 계수. 1.0 = 보정 없음, 0.02 = 50:1 분할 보정.';
