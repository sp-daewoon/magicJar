-- V95: 계획 기반 진입(Planned Entry) — strategy_position 에 거래 계획(trade_plan) JSONB 컬럼 추가 (ADR 0130 L2, R1)
--
-- L2 거래 계획(TradePlan): owner=AUTOPILOT 전략이 진입(BUY intent 발행 성공) 시점에 이 거래(lot)의
--   thesis·목표(TP)·손절(SL)·트레일링·hold 범위·청산 조건·contingency·status·exitReason 를 1회 데이터로
--   박제한다(scale_in_config·entry_policy JSONB 영속 패턴 정합). 박제된 계획은 lot 사이클 동안 유효하며,
--   전량 청산(quantity=0) 시 reset 한다(reset 은 R2 체결 listener 책임 — 본 마이그레이션은 컬럼만 추가).
--
-- 확장성 (ADR 0130 §2 L2 LOCK): 새 청산 유형은 trade_plan 의 exitConditions 배열에 데이터를 추가하면 되고,
--   새 필드는 add-only(스키마 변경 0)다. 도메인 codec(TradePlanSerde)이 Map↔TradePlan 변환을 단일 책임으로 캡슐화.
--
-- 게이트·격리 (ADR 0130 §4 zero-regression LOCK):
--   - owner=AUTOPILOT 한정 — USER/SYSTEM 전략은 trade_plan 을 기록하지 않는다(NULL 잔존 = 현행 청산 라우팅 그대로).
--   - 데이터 게이트 magicjar.autopilot.planned-entry.enabled default false — off 면 박제 skip(컬럼 NULL = 행동 변화 0).
--
-- 하위호환: 컬럼은 nullable / default NULL — 기존 행은 영향 0(NULL = 미기록 = 현행 동작). 컬럼 추가 자체로 거동 불변.
-- 롤백(down) — ADR 0130 §9: 게이트 off 1줄로 거동 byte-동일 복귀(컬럼 NULL 잔존은 무해)하며, 필요 시 아래 down SQL.
--
-- ===== DOWN (rollback) =====
-- ALTER TABLE strategy_position DROP COLUMN IF EXISTS trade_plan;
-- ============================

ALTER TABLE strategy_position
    ADD COLUMN IF NOT EXISTS trade_plan JSONB NULL;

COMMENT ON COLUMN strategy_position.trade_plan IS
    'ADR 0130 L2 — 계획 기반 진입(Planned Entry) 거래 계획 JSONB. owner=AUTOPILOT 진입 시점 박제(thesis·TP·SL·trailing·hold·exitConditions·contingency·status·exitReason). NULL=미기록(USER/SYSTEM·게이트 off=현행 동작). quantity=0 전량 청산 시 reset(R2 listener).';
