-- V94__strategy_allow_overnight.sql
-- ADR 0129 레버 1 — 오버나잇 홀드 전략별 토글 + 레버 4 autopilot min_hold_bars 시드.
-- default true = 기존 USER/SYSTEM/AUTOPILOT 전략 현행 거동 보존(zero-regression).
-- autopilot 신규 시드만 false (단타 본질 — 갭 리스크 default 회피).
ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS allow_overnight BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN strategy.allow_overnight IS
    'ADR 0129 — 오버나잇 보유 허용 토글. false + owner=AUTOPILOT 이면 EOD 청산 워커가 장마감 전(default 15:15 KST) 시장가 청산 + 14:30 이후 신규 BUY 금지. USER/SYSTEM 전략은 owner 격리로 EOD 워커·게이트 미적용(값 무관). default true = 현행 거동 보존.';

-- 기존 AUTOPILOT 전략은 allow_overnight 을 소급 변경하지 않는다(true 유지 — 사용자/orchestrator 의도적 전환 대기).
-- 향후 autopilot 시드 템플릿(V89 류·자율 생성)은 INSERT 시 allow_overnight=false 명시.

-- 레버 4 — autopilot 시드 전략 min_hold_bars 기본값 주입.
--   모멘텀 진입 ↔ vwap-cross 즉시청산 휩쏘 완화(진입 후 3봉(1분봉=3분) 동안 DSL 매도 무시).
--   사용자가 이미 설정한 값은 덮지 않는다(IS NULL 조건). 손절·익절 라인은 MinHoldGuard 가 우회(안전망 우선 유지).
UPDATE strategy
SET min_hold_bars = 3
WHERE owner = 'AUTOPILOT'
  AND min_hold_bars IS NULL;

-- rollback (단방향 Flyway — 수동 적용):
--   ALTER TABLE strategy DROP COLUMN IF EXISTS allow_overnight;
--   UPDATE strategy SET min_hold_bars = NULL WHERE owner = 'AUTOPILOT';
