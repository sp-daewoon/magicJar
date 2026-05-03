-- V20: 캔들 정책 v2 — 1분봉 단일 영속 (ADR 0048, candle-data-policy-v2.md)
--
-- 변경 내용:
--   1. M5/M15/M30/H1 interval row 영구 삭제 (분봉 SoT = M1 단일 영속)
--   2. instrument_cache_meta M5/M15/M30/H1 row 영구 삭제
--   3. candle 테이블 interval CHECK constraint 추가 (D1·M1 외 INSERT 사전 차단)
--
-- 정책 근거: N분봉은 M1 단일 영속에서 application layer(CandleAggregator)로 합성.
-- D1(일봉)은 영구 보존 — 별도 영속 정책(일봉 SoT).

-- ── Step 1: M5/M15/M30/H1 candle row 삭제 ──────────────────────────────────
DELETE FROM candle
WHERE interval IN ('M5', 'M15', 'M30', 'H1');

-- ── Step 2: instrument_cache_meta M5/M15/M30/H1 row 삭제 ────────────────────
-- (M1/D1 외 합성 interval은 더 이상 캐시 메타로 추적하지 않음)
DELETE FROM instrument_cache_meta
WHERE interval IN ('M5', 'M15', 'M30', 'H1');

-- ── Step 3: candle.interval CHECK constraint 추가 ────────────────────────────
-- 신규 INSERT 시 M5/M15/M30/H1 등 합성 interval 저장 원천 차단.
-- instrument_cache_meta는 향후 지수(KOSPI 0001·KOSDAQ 1001) 분봉 30일 한정 영속 가능성 고려,
-- CHECK constraint 추가하지 않음.
ALTER TABLE candle
    ADD CONSTRAINT candle_interval_chk
    CHECK (interval IN ('D1', 'M1'));
