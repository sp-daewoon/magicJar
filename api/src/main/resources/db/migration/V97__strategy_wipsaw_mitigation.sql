-- V97__strategy_wipsaw_mitigation.sql
-- 감사 부록 A (2026-07-03 전 영역 시스템 감사 — "금호타이어 3,690 매수 → 37분 후 3,675 매도" 휩쏘 반복 패턴) 대응.
-- 사용자 결재 2026-07-03: DSL 휩쏘 청산 = "버퍼 + minHold 상향" (dsl_exit 0.3% 버퍼 + min_hold_bars 3→8).
--
-- 1) dsl_exit 버퍼 — vwap/ma20/FVG/OB 하향 이탈 청산에 0.3% 버퍼(`close < line` → `close < line * 0.997`).
--    vwap-reversion 의 이익 실현 방향(`close > vwap()`)은 +0.3%(`* 1.003`).
--    문법 근거: KisYamlDslParser parseTerm 이 함수 결과 × 리터럴 곱을 지원(시드 dsl_entry `volume_ma(20) * 1.5` 기존재).
--    대상 = 시드 원문(scripts/autopilot-seed-strategies.mjs)과 정확 일치하는 AUTOPILOT 전략만 —
--    사용자가 이미 손댄 dsl_exit 은 덮지 않는다(V94 "사용자 설정값은 덮지 않음" 원칙 정합).
--    멱등: 치환 후 텍스트는 원문 정확 일치 조건에 더 이상 걸리지 않음 + NOT LIKE 버퍼 가드 2중.
--
-- 2) min_hold_bars 3→8 — V94 가 주입한 vwap-cross 즉시청산 휩쏘 완화값(3봉)이 부족(3m tf = 9분 << 실측 37분 패턴).
--    8봉(3m tf = 24분 / 5m tf = 40분)으로 상향. 손절·익절 라인은 MinHoldGuard 가 우회(안전망 우선 — V94 정합).

-- ── 1) dsl_exit 0.3% 버퍼 ─────────────────────────────────────────────────────
-- momentum-breakout · volume-spike (tf 3m): close < vwap() → 0.3% 하향 버퍼.
UPDATE strategy
SET dsl_exit = 'close < vwap() * 0.997'
WHERE owner = 'AUTOPILOT'
  AND dsl_exit = 'close < vwap()'
  AND dsl_exit NOT LIKE '%0.997%';

-- pullback-reclaim (tf 5m): close < ma(20) → 0.3% 하향 버퍼.
UPDATE strategy
SET dsl_exit = 'close < ma(20) * 0.997'
WHERE owner = 'AUTOPILOT'
  AND dsl_exit = 'close < ma(20)'
  AND dsl_exit NOT LIKE '%0.997%';

-- fvg-bullish-reclaim (tf 5m): close < fvg_bullish_bottom(50) → 0.3% 하향 버퍼.
UPDATE strategy
SET dsl_exit = 'close < fvg_bullish_bottom(50) * 0.997'
WHERE owner = 'AUTOPILOT'
  AND dsl_exit = 'close < fvg_bullish_bottom(50)'
  AND dsl_exit NOT LIKE '%0.997%';

-- orderblock-bullish-retest (tf 5m): close < ob_bullish_bottom(20, 20, 50) → 0.3% 하향 버퍼.
UPDATE strategy
SET dsl_exit = 'close < ob_bullish_bottom(20, 20, 50) * 0.997'
WHERE owner = 'AUTOPILOT'
  AND dsl_exit = 'close < ob_bullish_bottom(20, 20, 50)'
  AND dsl_exit NOT LIKE '%0.997%';

-- vwap-reversion (tf 5m): close > vwap() 는 이익 실현 방향 → +0.3% 상향 버퍼(1.003).
UPDATE strategy
SET dsl_exit = 'close > vwap() * 1.003'
WHERE owner = 'AUTOPILOT'
  AND dsl_exit = 'close > vwap()'
  AND dsl_exit NOT LIKE '%1.003%';

-- ── 2) min_hold_bars 3→8 ─────────────────────────────────────────────────────
-- V94 시드값(3)만 상향 — 사용자가 별도 설정한 값(3 이외)은 덮지 않는다(멱등: 재실행 시 8은 3 조건에 불일치).
UPDATE strategy
SET min_hold_bars = 8
WHERE owner = 'AUTOPILOT'
  AND min_hold_bars = 3;

-- rollback (단방향 Flyway — 수동 적용):
--   UPDATE strategy SET dsl_exit = 'close < vwap()' WHERE owner = 'AUTOPILOT' AND dsl_exit = 'close < vwap() * 0.997';
--   UPDATE strategy SET dsl_exit = 'close < ma(20)' WHERE owner = 'AUTOPILOT' AND dsl_exit = 'close < ma(20) * 0.997';
--   UPDATE strategy SET dsl_exit = 'close < fvg_bullish_bottom(50)' WHERE owner = 'AUTOPILOT' AND dsl_exit = 'close < fvg_bullish_bottom(50) * 0.997';
--   UPDATE strategy SET dsl_exit = 'close < ob_bullish_bottom(20, 20, 50)' WHERE owner = 'AUTOPILOT' AND dsl_exit = 'close < ob_bullish_bottom(20, 20, 50) * 0.997';
--   UPDATE strategy SET dsl_exit = 'close > vwap()' WHERE owner = 'AUTOPILOT' AND dsl_exit = 'close > vwap() * 1.003';
--   UPDATE strategy SET min_hold_bars = 3 WHERE owner = 'AUTOPILOT' AND min_hold_bars = 8;
