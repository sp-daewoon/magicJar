-- V90: 분할매수 자본 재설계 — strategy_position 에 base 고정 + 부분매수 잔여 컬럼 추가 (ADR 0128, 2026-06-19 결재 ①②)
--
-- ① base = 1차 진입 시점 가용액 고정: 회차마다 buyable 재계산(전체노출 증가로 줄어듦)을 폐기하고
--    1차 시점 매수가능액(buyable)을 박제해 2·3차 회차 예산을 "고정 base × 회차 비중%"으로 산정한다.
--    외부 주입 추상화 — 미래 "지정 가격 + 목표 금액" 매집은 이 컬럼에 목표 금액을 채워 넣으면 호환.
-- ② 부분 매수 잔여: 회차 예산이 살 수 있는 수량보다 자금이 부족해 일부만 체결됐을 때 못 산 잔여 수량을 기억해
--    자금이 생기면 다음 boundary 에서 마저 채운다.
--
-- floor(catastrophic floor) 전략 설정값은 strategy.scale_in_config JSONB(stopLossFloorPct)로 직렬화되므로 별도 DDL 불필요.
--
-- 하위호환: 두 컬럼 모두 nullable / default 0 — 기존 행은 영향 0(분할 미사용·R2 어댑터 미적용 시 NULL/0 = 현행 동작).
-- 전량 청산(quantity=0) 시 reset 은 체결 listener(R2) 책임 — 본 마이그레이션은 컬럼만 추가한다.

ALTER TABLE strategy_position
    ADD COLUMN IF NOT EXISTS scale_in_base_budget  NUMERIC(19, 4) NULL CHECK (scale_in_base_budget IS NULL OR scale_in_base_budget >= 0),
    ADD COLUMN IF NOT EXISTS scale_in_remainder_qty INTEGER       NOT NULL DEFAULT 0 CHECK (scale_in_remainder_qty >= 0);

COMMENT ON COLUMN strategy_position.scale_in_base_budget IS
    'ADR 0128 ① — 분할매수 base 고정값(원). 1차 진입 시점 매수가능액(buyable)을 박제해 2·3차 회차 예산을 "고정 base × 회차 비중%"으로 산정. NULL=미진입/분할미사용. quantity=0 청산 시 reset.';
COMMENT ON COLUMN strategy_position.scale_in_remainder_qty IS
    'ADR 0128 ② — 부분 매수 잔여 수량(주). 자금 부족으로 회차 일부만 체결됐을 때 못 산 잔여를 기억해 다음 회차에 마저 채움. 0=잔여 없음. quantity=0 청산 시 reset.';
