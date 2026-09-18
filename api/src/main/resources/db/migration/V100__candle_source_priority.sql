-- V100: candle.source_priority 신뢰등급 컬럼 (P0-A Phase A-2 · P0-B/P0-D)
--
-- 근거: 감사 2026-09-17 §1-② + candle-data-canon §1 진실원 우선순위 +
--       설계 _workspace/specs/2026-09-18-candle-write-arbiter-design.md Phase A-2.
--
-- 목적: 같은 (code, interval, ts) 봉에 8개 writer가 DO NOTHING/DO UPDATE 혼재로 써서 최종값이
--       실행 순서에 의존하는 오염을 막기 위한 precedence 가드의 DB 토대.
--       source_priority 값이 작을수록 신뢰 높음(CandleSource enum priority 정합):
--         1 = OFFICIAL_DAILY_CLOSE, 2 = REST_CONFIRMED, 3 = WS_LIVE, 4 = POLLING_FALLBACK.
--
-- 안전: 기존 source(VARCHAR(20)) 문자열 컬럼은 그대로 유지(하위호환). 본 마이그레이션은
--       nullable 컬럼 추가 + 기존 행 백필만 수행 — 기존 UPSERT 동작 무변경(zero-regression).
--       precedence 가드 UPSERT는 flag 뒤 별도 메서드로 전환(Phase A-2 코드).

ALTER TABLE candle
    ADD COLUMN IF NOT EXISTS source_priority SMALLINT;

-- 기존 행 백필 — CandleSource.fromLabel 매핑 규칙과 1:1 정합.
-- 하이픈/언더스코어 혼재를 대문자·언더스코어로 정규화한 뒤 포함 문자열로 등급 판정.
UPDATE candle
SET source_priority = CASE
    WHEN source IS NULL OR source = '' THEN 3  -- 미상 → WS_LIVE(보수적 중간)
    WHEN UPPER(REPLACE(source, '-', '_')) LIKE '%DAILY_CLOSE%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%SNAP%' THEN 1     -- OFFICIAL_DAILY_CLOSE
    WHEN UPPER(REPLACE(source, '-', '_')) LIKE '%BACKFILL%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%GAPFILL%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%MKT_DAILY%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%RANGE%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%KSDINFO%' THEN 2  -- REST_CONFIRMED
    WHEN UPPER(REPLACE(source, '-', '_')) LIKE '%POLLING%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%NOTICK%'
      OR UPPER(REPLACE(source, '-', '_')) LIKE '%FALLBACK%' THEN 4 -- POLLING_FALLBACK
    ELSE 3                                                         -- WS_LIVE(KIS 등)
END
WHERE source_priority IS NULL;

COMMENT ON COLUMN candle.source_priority IS
    '캔들 출처 신뢰등급(1=공식마감<2=REST확정<3=WS실시간<4=polling). CandleSource enum priority 정합. precedence 가드(P0-B) 근거. 작을수록 신뢰 높음.';
