-- ============================================================================
-- V91 — autopilot_state (자율 루프 안전 하니스 단일 권위 행)
-- ============================================================================
-- spec: _workspace/specs/2026-06-22-autopilot-self-augmentation.md §2-0-A·§2-0-B
-- plan: _workspace/specs/2026-06-22-autopilot-phase0-plan.md Task 1
-- 목적: magicjar-autopilot(완전 자율 REAL 트레이딩) 회로차단기·kill switch의 단일 권위 상태.
--       계좌 전체(account-wide) 단일 행 — 루프 전용 자본 풀 한도·일일/누적 손실 추적·kill 플래그.
-- 폐기 철학(spec §3-bis Tier A): 본 테이블은 autopilot 전용. MAGICJAR_AUTOPILOT_ENABLED=false(default)면
--       어떤 코드도 본 테이블을 읽지 않는다 → v3.14.0 zero-regression. 폐기 = 하단 ROLLBACK SQL 실행.
-- 컨벤션: autopilot_ prefix 격리 · IF NOT EXISTS LOCK · seed 1행 · 모든 한도는 보수적 default 보유.
-- 자원 audit: V90 최신 확인 → V91 충돌 0. strategy.owner 는 VARCHAR(16)(V88) → AUTOPILOT enum 추가에 DDL 불필요.
-- ============================================================================

CREATE TABLE IF NOT EXISTS autopilot_state (
    id                   BIGINT         PRIMARY KEY,
    -- kill switch — 단일 권위 플래그(spec §2-0-B). 누적 손실 한도 도달 자동 ON / /autopilot stop 수동 ON.
    kill_active          BOOLEAN        NOT NULL DEFAULT false,
    -- 한도 config(spec §2-0-A) — 전부 NUMERIC %, 보수적 default. 운영 중 ENV/이 행으로 튜닝.
    pool_pct             NUMERIC(6, 3)  NOT NULL DEFAULT 5,    -- 루프 전용 자본 풀 = 계좌 순자산의 5%
    per_trade_pct        NUMERIC(6, 3)  NOT NULL DEFAULT 20,   -- 건당 진입 캡 = 풀의 20%
    max_positions        INTEGER        NOT NULL DEFAULT 3,    -- 동시 보유 종목수 상한
    daily_loss_pct       NUMERIC(6, 3)  NOT NULL DEFAULT 30,   -- 일일 손실 한도(풀의 %) → 당일 halt
    cum_loss_pct         NUMERIC(6, 3)  NOT NULL DEFAULT 50,   -- 누적 손실 한도(풀의 %) → kill switch
    -- 손실 추적(실현 손실 크기를 양수로 누적). last_reset_date 기준 일일 리셋.
    cum_realized_loss    NUMERIC(20, 4) NOT NULL DEFAULT 0,
    daily_realized_loss  NUMERIC(20, 4) NOT NULL DEFAULT 0,
    last_reset_date      DATE           NOT NULL DEFAULT CURRENT_DATE,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  autopilot_state                     IS 'spec 2026-06-22 §2-0-A/B — autopilot 자율 루프 안전 하니스 account-wide 단일 권위 행(id=1). MAGICJAR_AUTOPILOT_ENABLED=false면 미사용(zero-regression).';
COMMENT ON COLUMN autopilot_state.kill_active         IS 'kill switch(spec §2-0-B) — true면 owner=AUTOPILOT REAL 경로 freeze. 누적손실 자동 ON / 수동 /autopilot stop.';
COMMENT ON COLUMN autopilot_state.pool_pct            IS '루프 전용 자본 풀 상한 = 계좌 순자산 % (default 5). 사용자 수동 전략·후성 노출과 분리 계산.';
COMMENT ON COLUMN autopilot_state.per_trade_pct       IS '건당 진입 캡 = 풀 % (default 20). 초과 시 Guard 가 approvedAmount clamp.';
COMMENT ON COLUMN autopilot_state.max_positions       IS '루프 동시 보유 종목수 상한 (default 3).';
COMMENT ON COLUMN autopilot_state.daily_loss_pct      IS '일일 손실 한도 = 풀 % (default 30) → 도달 시 당일 halt.';
COMMENT ON COLUMN autopilot_state.cum_loss_pct        IS '누적 손실 한도 = 풀 % (default 50) → 도달 시 kill switch 자동 ON.';
COMMENT ON COLUMN autopilot_state.cum_realized_loss   IS '루프 누적 실현 손실(양수 = 손실 크기). cum_loss_pct 와 비교.';
COMMENT ON COLUMN autopilot_state.daily_realized_loss IS '당일 실현 손실(양수 = 손실 크기). last_reset_date 기준 일일 리셋.';

-- seed 단일 행(id=1, account-wide). 모든 한도 default·kill off·손실 0.
INSERT INTO autopilot_state (id, kill_active)
VALUES (1, false)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- ROLLBACK (폐기 매니페스트 Tier A — spec §3-bis · _workspace/autopilot/DISCARD-MANIFEST.md)
--   "v3.14.0 복귀" 시 autopilot 전용 자원 정리:
--     DROP TABLE IF EXISTS autopilot_state;
--   strategy.owner enum 되돌림 노트:
--     owner 컬럼은 VARCHAR(16)(V88)이므로 'AUTOPILOT' 값 추가에 DDL 없음(스키마 무변경).
--     폐기 시 owner='AUTOPILOT' 전략 데이터만 정리(사용자 전략·후성 보존):
--       DELETE FROM strategy WHERE owner = 'AUTOPILOT';
--   (StrategyOwner enum 의 AUTOPILOT 상수 제거는 코드 revert — git log --grep autopilot.)
-- ============================================================================
