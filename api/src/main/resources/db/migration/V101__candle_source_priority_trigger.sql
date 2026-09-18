-- V101: candle.source_priority 자동 채움 트리거 (P0-A Phase A-2 보강 · 2번 배치개선 대안)
--
-- 근거: V100 이후 신규 INSERT 행(saveAll/insertIgnore/insertOrUpdateIfGapfill 경로)이
--       source_priority 를 채우지 않아 NULL 로 쌓임(서비스 기동 후 수만 행 실측). precedence 가드는
--       COALESCE(...,3) 로 안전하나 데이터 품질상 NULL 을 없앤다.
--
-- 설계 (성능 — 사용자 "정합성 코드가 성능 해치면 안 됨"):
--   - V100 처럼 1087만 행 일괄 UPDATE(5.7분 부팅 지연) 대신 **행별 BEFORE INSERT/UPDATE 트리거**로
--     신규/갱신 행만 채운다. 대량 백필 부담 0. 흩어진 8개 writer 코드 무변경(트리거가 DB단에서 보장).
--   - source_priority 가 명시적으로 주어지면(precedence 경로) 그 값을 존중, NULL 일 때만 source 로 계산.
--   - 매핑 규칙은 CandleSource.fromLabel 과 1:1 정합(POOL/_BF backfill 약어 포함).
--
-- 안전: 트리거는 INSERT/UPDATE 되는 행에만 작동. 기존 행 재기록 0. idempotent(재실행 시 CREATE OR REPLACE).

CREATE OR REPLACE FUNCTION candle_fill_source_priority()
RETURNS TRIGGER AS $$
DECLARE
    s TEXT;
BEGIN
    -- 명시적으로 준 source_priority 는 존중(precedence 경로가 넘긴 값 보존).
    IF NEW.source_priority IS NOT NULL THEN
        RETURN NEW;
    END IF;

    s := UPPER(REPLACE(COALESCE(NEW.source, ''), '-', '_'));

    NEW.source_priority := CASE
        WHEN s = '' THEN 3                                              -- 미상 → WS_LIVE
        WHEN s LIKE '%DAILY_CLOSE%' OR s LIKE '%SNAP%' THEN 1           -- OFFICIAL_DAILY_CLOSE
        WHEN s LIKE '%BACKFILL%' OR s LIKE '%GAPFILL%'
          OR s LIKE '%MKT_DAILY%' OR s LIKE '%RANGE%'
          OR s LIKE '%KSDINFO%' OR s LIKE '%POOL%'
          OR s LIKE '%\_BF' ESCAPE '\' THEN 2                          -- REST_CONFIRMED (POOL/_BF backfill 약어 포함)
        WHEN s LIKE '%POLLING%' OR s LIKE '%NOTICK%'
          OR s LIKE '%FALLBACK%' THEN 4                                -- POLLING_FALLBACK
        ELSE 3                                                          -- WS_LIVE (KIS 등)
    END;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_candle_fill_source_priority ON candle;
CREATE TRIGGER trg_candle_fill_source_priority
    BEFORE INSERT OR UPDATE ON candle
    FOR EACH ROW
    EXECUTE FUNCTION candle_fill_source_priority();

-- V100 이후 이미 NULL 로 쌓인 소량 신규 행 정리 + V100 이 옛 규칙으로 POOL/_BF 를 3(WS_LIVE)으로
-- 오분류한 행 교정(POOL/_BF backfill 은 2 REST_CONFIRMED 가 맞음). 소량(수십만)이라 부담 낮음.
UPDATE candle
SET source_priority = CASE
    WHEN source IS NULL OR source = '' THEN 3
    WHEN UPPER(REPLACE(source, '-', '_')) LIKE '%DAILY_CLOSE%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%SNAP%' THEN 1
    WHEN UPPER(REPLACE(source, '-', '_')) LIKE '%BACKFILL%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%GAPFILL%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%MKT_DAILY%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%RANGE%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%KSDINFO%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%POOL%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%\_BF' ESCAPE '\' THEN 2
    WHEN UPPER(REPLACE(source, '-', '_')) LIKE '%POLLING%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%NOTICK%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%FALLBACK%' THEN 4
    ELSE 3
END
WHERE source_priority IS NULL
   -- V100 옛 규칙 POOL/_BF 오분류(3) 교정 대상
   OR (source_priority = 3
       AND (UPPER(REPLACE(source, '-', '_')) LIKE '%POOL%'
            OR UPPER(REPLACE(source, '-', '_')) LIKE '%\_BF' ESCAPE '\'));

COMMENT ON FUNCTION candle_fill_source_priority() IS
    'candle INSERT/UPDATE 시 source_priority가 NULL이면 source 문자열로 자동 계산(CandleSource.fromLabel 정합). V100 대량백필 대신 행별 트리거로 성능 부담 0.';
