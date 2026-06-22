-- ============================================================================
-- V93 — autopilot_paper_shadow (개선 G · 슬롯 공정성 · paper-parallel 학습 신호)
-- ============================================================================
-- spec: _workspace/specs/2026-06-22-autopilot-self-augmentation.md §학습조건 A~F · Phase 1 §4(승률·기대값)
-- 진단: _workspace/autopilot/hypotheses-2026-06-22.md §🔬 첫 거래일 휴면 진단 (#1101)
--       → 권장 ② "autopilot 평가를 paper(슬롯 무제한) 병행으로 학습 신호 분리"의 구현.
-- 목적:
--   owner=AUTOPILOT BUY 가 실 RiskConfig 슬롯/풀(maxConcurrentPositions=3·POSITION_LIMIT)에 막혀
--   starve 될 때, **실 자본 0** 으로 가상 진입을 1행 기록하고 후속 캔들 대비 TP/SL 도달을 판정해
--   가설별 승/패/기대값을 누적한다. 6가설(102 fvg·103 ob 포함) 전부 학습 신호가 쌓여 다음 사이클
--   승격/강등 입력이 된다(현행: starve 전략 점수 0 → 다양성 학습 죽음).
-- 자본 안전: 본 테이블·경로는 **실 주문/실 capital 을 절대 만지지 않는다**(섀도 = 장부상 가상 트레이드).
--   실보유 동시 cap 3 은 불변 — 페이퍼는 자본 0 이라 슬롯 cap 과 무관.
-- 폐기 철학(spec §3-bis Tier A): autopilot 전용. MAGICJAR_AUTOPILOT_ENABLED=false(default)면 어떤 코드도
--   본 테이블에 쓰지 않는다 → v3.14.0 zero-regression. 폐기 = 하단 ROLLBACK SQL.
-- 컨벤션: autopilot_ prefix 격리 · CREATE TABLE IF NOT EXISTS LOCK.
-- 자원 audit: V92 최신 확인(autopilot_kill_journal) → V93 충돌 0.
-- ============================================================================

CREATE TABLE IF NOT EXISTS autopilot_paper_shadow (
    id                  BIGSERIAL      PRIMARY KEY,
    -- 격리 식별 — owner=AUTOPILOT 전략 귀속(strategy_position 의 strategyId 와 동일 의미).
    user_id             VARCHAR(64)    NOT NULL,
    strategy_id         BIGINT         NOT NULL,
    code                VARCHAR(16)    NOT NULL,
    interval            VARCHAR(8)     NOT NULL,   -- 평가 tf(3m·5m 등) — 후속 캔들 조회 키.
    -- 가상 진입 스냅샷(starve 된 BUY 시그널 시점 박제).
    signal_id           BIGINT         NULL,       -- 출처 signal.id(추적용 · null 허용).
    entry_price         NUMERIC(20, 4) NOT NULL,   -- priceAtSignal(가상 진입가).
    quantity            INTEGER        NOT NULL,   -- 실 사이징과 동일 산식으로 resolve 된 가상 수량(> 0).
    take_profit_pct     NUMERIC(6, 3)  NOT NULL,   -- 전략 RiskPlan takeProfitPct.
    stop_loss_pct       NUMERIC(6, 3)  NOT NULL,   -- 전략 RiskPlan fixedStopLoss.
    entry_ts            TIMESTAMPTZ    NOT NULL DEFAULT now(),   -- 가상 진입 시각(후속 캔들 시작 anchor).
    block_reason        VARCHAR(64)    NOT NULL,   -- starve 사유(MAX_CONCURRENT_POSITIONS·POSITION_LIMIT 등).
    -- 스코어링 결과(미결=OPEN 이면 NULL).
    status              VARCHAR(16)    NOT NULL DEFAULT 'OPEN',  -- OPEN | WIN | LOSS.
    exit_price          NUMERIC(20, 4) NULL,       -- TP/SL 도달가(판정 시 채움).
    exit_ts             TIMESTAMPTZ    NULL,        -- 도달 캔들 ts.
    realized_pnl        NUMERIC(20, 4) NULL,        -- net 가상 손익(비용 차감 — 부호 그대로).
    resolved_at         TIMESTAMPTZ    NULL,        -- 스코어러가 판정 마킹한 시각.
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  autopilot_paper_shadow IS
    '개선 G(슬롯 공정성) — 실슬롯 starve 된 owner=AUTOPILOT BUY 의 paper-parallel 섀도 트레이드 장부. 실 자본/실 주문 0. 후속 캔들 TP/SL 도달로 가설별 승/패/기대값 누적(학습 신호). spec 2026-06-22 §학습조건·Phase1 §4.';
COMMENT ON COLUMN autopilot_paper_shadow.block_reason IS 'starve 사유 — 이 BUY 가 실 경로에서 왜 막혔는가(MAX_CONCURRENT_POSITIONS·POSITION_LIMIT). 슬롯/풀 경합만 섀도 대상(kill·daily-loss·dedup 은 제외).';
COMMENT ON COLUMN autopilot_paper_shadow.status       IS 'OPEN(미결·다음 주기 재평가) | WIN(TP 먼저 도달) | LOSS(SL 먼저 또는 intrabar 모호 시 손실 우선).';
COMMENT ON COLUMN autopilot_paper_shadow.realized_pnl IS 'net 가상 손익(원) — 왕복 비용(≈0.3%) 차감. 가설 승격/강등의 기대값 입력.';

-- OPEN 섀도 빠른 조회용 부분 인덱스(스코어러가 미결 행만 순회 — 자원 절약).
CREATE INDEX IF NOT EXISTS idx_autopilot_paper_shadow_open
    ON autopilot_paper_shadow (entry_ts ASC)
    WHERE status = 'OPEN';

-- 전략별 점수 집계용(가설별 승률·기대값 rollup).
CREATE INDEX IF NOT EXISTS idx_autopilot_paper_shadow_strategy
    ON autopilot_paper_shadow (strategy_id, status);

-- ============================================================================
-- ROLLBACK (폐기 매니페스트 Tier A — spec §3-bis · _workspace/autopilot/DISCARD-MANIFEST.md)
--   "v3.14.0 복귀" 시 autopilot 전용 자원 정리:
--     DROP TABLE IF EXISTS autopilot_paper_shadow;
-- ============================================================================
