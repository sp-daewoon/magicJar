-- ============================================================================
-- V92 — autopilot_kill_journal + autopilot_state.lifetime_realized_loss
-- ============================================================================
-- spec: _workspace/specs/2026-06-22-autopilot-self-augmentation.md §2-0-B·§2-0-D
-- plan: _workspace/specs/2026-06-22-autopilot-phase0-plan.md Task 4
-- 목적:
--   1) autopilot_kill_journal — kill switch 발동 **사후분석(post-mortem) 저널**.
--      Claude(자율 루프)가 "왜 kill 됐는가"를 분석하는 evidence 원천. kill 발동 시 1행 스냅샷
--      insert(손실·한도·보유종목·전략수), resume 시 해당 미해제 행에 resumed_at·resume_reason 갱신.
--   2) autopilot_state.lifetime_realized_loss — **하드 플로어** 추적 컬럼.
--      kill-reset(deactivateKill)에도 리셋되지 않는 영구 누적 손실. 무한 reset 출혈을 코드로 막는다
--      (lifetime_realized_loss >= 풀상한 × lifetimeFloorPct/100 이면 deactivateKill 거부 — 사용자 재가 필요).
-- 폐기 철학(spec §3-bis Tier A): autopilot 전용. MAGICJAR_AUTOPILOT_ENABLED=false(default)면
--   어떤 코드도 본 테이블·컬럼을 읽지 않는다 → v3.14.0 zero-regression. 폐기 = 하단 ROLLBACK SQL.
-- 컨벤션: autopilot_ prefix 격리 · IF NOT EXISTS LOCK · ADD COLUMN IF NOT EXISTS.
-- 자원 audit: V91 최신 확인 → V92 충돌 0.
-- ============================================================================

-- ── 1) 하드 플로어 추적 컬럼 — autopilot_state 에 영구 누적 손실 추가 ────────────────
-- cum_realized_loss 와 함께 누적되지만, deactivateKill(kill 해제)에도 절대 리셋되지 않는다.
-- "lifetime floor 도달 = kill 영구(사용자만 해제)" 의 단일 권위 값.
ALTER TABLE autopilot_state
    ADD COLUMN IF NOT EXISTS lifetime_realized_loss NUMERIC(20, 4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN autopilot_state.lifetime_realized_loss IS
    '하드 플로어(spec §2-0-B) — 영구 누적 실현 손실(양수). kill-reset 에도 리셋 안 됨. 풀상한×lifetimeFloorPct/100 도달 시 deactivateKill 거부(무한 reset 출혈 방어, 사용자 재가 필요).';

-- ── 2) kill 사후분석 저널 — 발동 1행 스냅샷 + resume 시 해제 갱신 ──────────────────
CREATE TABLE IF NOT EXISTS autopilot_kill_journal (
    id                       BIGSERIAL      PRIMARY KEY,
    -- kill 발동 스냅샷
    occurred_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    reason                   TEXT           NOT NULL,
    -- 발동 시점 손실(autopilot_state 미러 — 발동 당시 값 박제)
    cum_realized_loss        NUMERIC(20, 4) NOT NULL DEFAULT 0,
    daily_realized_loss      NUMERIC(20, 4) NOT NULL DEFAULT 0,
    lifetime_realized_loss   NUMERIC(20, 4) NOT NULL DEFAULT 0,
    -- 발동 시점 격리 대상 캡처(owner=AUTOPILOT)
    paused_strategy_count    INTEGER        NOT NULL DEFAULT 0,
    held_codes               TEXT           NULL,   -- owner=AUTOPILOT 보유 종목 코드 CSV(분석용)
    -- 발동 시점 한도 config 스냅샷(이후 한도 튜닝과 무관하게 당시 envelope 보존)
    pool_pct                 NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    per_trade_pct            NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    max_positions            INTEGER        NOT NULL DEFAULT 0,
    daily_loss_pct           NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    cum_loss_pct             NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    -- resume(kill 해제) 갱신 — 미해제(active kill)이면 NULL.
    resumed_at               TIMESTAMPTZ    NULL,
    resume_reason            TEXT           NULL,
    corrected_limits_json    TEXT           NULL    -- resume 시 보정 한도 JSON(있으면)
);

COMMENT ON TABLE  autopilot_kill_journal IS
    'spec 2026-06-22 §2-0-D — kill switch 발동 사후분석(post-mortem) 저널. 발동 시 1행 insert, resume 시 해당 미해제 행에 resumed_at·resume_reason 갱신. Claude 자율 루프의 "왜 kill 됐는가" evidence 원천.';
COMMENT ON COLUMN autopilot_kill_journal.held_codes            IS '발동 시점 owner=AUTOPILOT 보유 종목 코드 CSV(분석용 — 어떤 종목에서 출혈했는가).';
COMMENT ON COLUMN autopilot_kill_journal.resumed_at            IS 'kill 해제(deactivateKill) 시각. NULL = 아직 해제 안 됨(active kill 1행은 최대 1개 가정).';
COMMENT ON COLUMN autopilot_kill_journal.corrected_limits_json IS 'resume 시 Claude 가 보정한 한도 JSON(예: {"poolPct":3,"cumLossPct":30}). 없으면 NULL.';

-- 미해제(active) kill 행 빠른 조회용 부분 인덱스(resume 시 최신 미해제 행 lookup).
CREATE INDEX IF NOT EXISTS idx_autopilot_kill_journal_active
    ON autopilot_kill_journal (occurred_at DESC)
    WHERE resumed_at IS NULL;

-- ============================================================================
-- ROLLBACK (폐기 매니페스트 Tier A — spec §3-bis · _workspace/autopilot/DISCARD-MANIFEST.md)
--   "v3.14.0 복귀" 시 autopilot 전용 자원 정리:
--     DROP TABLE IF EXISTS autopilot_kill_journal;
--     ALTER TABLE autopilot_state DROP COLUMN IF EXISTS lifetime_realized_loss;
--   (autopilot_state 자체 DROP 은 V91 ROLLBACK 노트 참조.)
-- ============================================================================
