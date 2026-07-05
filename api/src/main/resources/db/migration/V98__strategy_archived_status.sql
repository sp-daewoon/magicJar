-- V98: 전략 아카이브(보관함) — ARCHIVED status + archived_at 컬럼 (전략 아카이브 트랙)
-- 목적: DELETED(영구 audit·목록 제외)와 별개로, 사용자가 전략을 "보관함"으로 치워두는 상태.
--   ARCHIVED 는 평가 완전 제외·신규 BUY 불가지만 목록(status <> 'DELETED')에는 포함되어 프론트가 탭 분리.
--   보관 해제(unarchive)는 PAUSED 로 복귀(즉시 자동매매 재개 금지 — 보수적).
-- 자본 안전: 보관은 보유 포지션·미체결 주문이 없을 때만 허용(관리 주체 없는 포지션 고아화 방지 — StrategyService.archive 가드).
-- 기존 데이터 무변경 — 컬럼 추가(nullable) + CHECK 제약에 'ARCHIVED' 만 추가.

ALTER TABLE strategy
    ADD COLUMN archived_at TIMESTAMPTZ;

-- V14 도입 → V61 확장(BUY_BLOCKED)된 status CHECK 제약에 'ARCHIVED' 를 추가한다.
ALTER TABLE strategy
    DROP CONSTRAINT IF EXISTS chk_strategy_status;

ALTER TABLE strategy
    ADD CONSTRAINT chk_strategy_status
        CHECK (status IN ('ACTIVE', 'BUY_BLOCKED', 'PAUSED', 'DISABLED', 'ARCHIVED', 'DELETED'));

COMMENT ON COLUMN strategy.archived_at
    IS '전략 보관(ARCHIVED) 전환 시각 — 보관함 이동. 평가 제외·신규 BUY 불가. unarchive 시 PAUSED 로 복귀(archived_at 은 audit 로 유지)';

COMMENT ON COLUMN strategy.status
    IS 'ACTIVE·BUY_BLOCKED·PAUSED·DISABLED·ARCHIVED·DELETED. ARCHIVED=사용자 보관함(평가 제외, 목록 포함), DELETED=soft delete(목록 제외)';
