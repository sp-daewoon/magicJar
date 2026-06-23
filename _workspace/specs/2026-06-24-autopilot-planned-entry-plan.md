# 구현 plan — autopilot 계획 기반 진입 (ADR 0130 · 에이전트별 task)

- **권위**: [ADR 0130](../arch/adr/0130-autopilot-planned-entry.md) · [spec](2026-06-24-autopilot-planned-entry.md) · [정책](../../docs/architecture/policies/autopilot-trade-lifecycle.md)
- **공통 제약**: owner=AUTOPILOT 한정 · 게이트 `magicjar.autopilot.planned-entry.enabled` default **false** · USER/SYSTEM zero-regression · `--base main` · rebase 머지 · 배포는 orchestrator(장마감 후). 각 task = `node scripts/task.mjs start --agent <a>` 로 task 파일 발행 + 머지 PR 에 포함.
- **자원 재사용 LOCK**: `EntryPolicySerde`·`ScaleInPlan`·`StrategyPositionPort`·`AutoExitReservations`·`PositionViAutoExitWatcher`·`AutopilotEodLiquidationWorker`·`MinHoldGuard`·`RiskGatewayEvaluator`. 신규 최소(`TradePlan`·`TradePlanSerde`·`PlanExecutor`·V95).
- **drift 교훈 LOCK**: 도메인 필드 추가 시 ConsumerStrategy/Position 엔티티 매핑 **반드시 동반**(ADR 0128 scale_in_config 매핑 누락 → 라이브 미작동 재발 금지).

---

## Task 1 — strategy-engine · R1 (도메인 + 영속 + plan 박제)

**브랜치**: `agent/strategy/planned-entry-r1` · **base** main

### 결과물
1. `V95__strategy_position_trade_plan.sql` — `ALTER TABLE strategy_position ADD COLUMN trade_plan JSONB NULL`(add-only·default NULL=미기록). **down SQL 동반**(`DROP COLUMN trade_plan`).
2. domain `TradePlan`(+ `TradePlanEntry/Target/Stop/Trailing/Hold/ExitCondition/Contingency`, `TradePlanStatus` enum) — 순수 데이터 클래스(Jackson import 0·`ScaleInPlan` 패턴·init 범위 검증).
3. domain `TradePlanSerde`(Map↔TradePlan codec — `EntryPolicySerde` 패턴·caller ObjectMapper).
4. `StrategyPosition` 에 `tradePlan: TradePlan? = null` 필드 + 전량 청산 reset 규약 합류.
5. **ConsumerStrategy/Position 엔티티 매핑**(trade_plan ↔ TradePlan, persistence — drift LOCK).
6. **진입 시 plan 박제**: BUY intent 발행 성공 시 산 전략이 `StrategyPositionPort.upsert` 로 trade_plan write. **게이트 off → 박제 skip**(현행 byte-동일).

### 테스트 (자본 경로 핵심 단위 동반)
- TradePlanSerde 라운드트립(toMap→fromMap 동치·빈 Map→null·BigDecimal string/number 양측 파싱).
- 엔티티 매핑(write→read trade_plan 보존).
- plan 박제 write(게이트 on 박제·off skip·owner=AUTOPILOT 한정·USER 미기록).

### 검증·CHANGELOG
- `:domain:check`·`:strategy-engine:check`·`:consumer:check`·`:api:check`·`ktlintCheck` GREEN.
- CHANGELOG row + anchor `adr-0130-slot-strategy-r1`.

---

## Task 2 — strategy-engine · R2 (PlanExecutor + SELL 소유권 + min_hold)

**브랜치**: `agent/strategy/planned-entry-r2` · **base** main (R1 머지 후)

### 결과물
1. `PlanExecutor`(consumer) — 매 봉 close/tick(ADR 0127 패턴)에 열린 lot plan 평가. 우선순위 **SL > MAX_HOLD > exitConditions > TP > TRAIL**(자본 보호 우선). 충족 시 lot-scoped MARKET/marketable SELL + plan.status=CLOSING·exitReason 기록. 중복 방지 `AutoExitReservations`+reservedSellQuantity 재사용. **게이트 off → no-op**.
2. **SELL 라우팅 lot 소유권** — `RiskGatewayEvaluator.holdingQuantity` user+code fallback 을 owner=AUTOPILOT + 게이트 on 한정 차단(산 적 없는 전략 SELL → holding 0 → BLOCKED). USER/게이트 off 현행 fallback 유지.
3. **min_hold lot 기준** — `MinHoldGuard` 를 lot(strategyPosition) 진입 시점 기준 평가.

### 테스트
- PlanExecutor: TP/SL/trail/maxHold/exitCond 각 발화·우선순위·lot 격리(타 전략 lot 미접근)·게이트 off no-op·DSL 병행 dedup.
- SELL 소유권: 교차 청산 0(001820 회귀 재현→BLOCKED)·owner=AUTOPILOT fallback 차단·USER zero-regression·게이트 off 현행.
- min_hold lot 기준.

### 검증·CHANGELOG
- `:domain:check`·`:strategy-engine:check`·`:consumer:check` GREEN.
- CHANGELOG row + anchor `adr-0130-slot-strategy-r2`.

---

## Task 3 — backend-core · R-aux (ADR 0129 신규 룰 benign 제외 회귀 fix) — R1 과 병행 가능

**브랜치**: `agent/backend-core/autopause-new-rules-benign` · **base** main

### 배경 (라이브 evidence)
ADR 0129 가 추가한 `AUTOPILOT_LATE_ENTRY_BLOCKED`·`AUTOPILOT_OPEN_BLACKOUT`·`AUTOPILOT_REENTRY_PRICE_GUARD` 3 룰이 `AutoPauseGuard.BENIGN_AUTO_PAUSE_EXCLUDED_RULES` 에 없어 BLOCKED 카운트에 기여 → 임계 도달 → 전략 자동 정지(F9·레버5-B 동형 회귀). **2026-06-23 14:00 재활성 시 LATE_ENTRY 가 auto-pause 발동**.

### 결과물
- 이 3 룰을 `BENIGN_AUTO_PAUSE_EXCLUDED_RULES` 에 add-only 합류(자기억제=자본 위험 아님·owner 무관 전역 제외·USER/SYSTEM 엔 미발생 autopilot 전용 룰이라 격리 안전). 임계 산식 무손상.
- KDoc 에 회귀 evidence·근거 기재(기존 §54-63 주석 패턴 정합).

### 테스트
- 3 룰 benign 합류 확인 · 3 룰만으로 임계 도달해도 auto-pause 정지 0 · 기존 AutoPauseGuardTest 회귀 0 · 실위험 룰(DAILY_LOSS 등)은 여전히 카운트.

### 검증·CHANGELOG
- `:consumer:test`·`:consumer:ktlintCheck` GREEN.
- CHANGELOG row + anchor `adr-0130-slot-backend-aux`.

---

## Task 4 — frontend + backend-core · R3 (L3 contingency wiring + UI + 통합)

**브랜치**: `agent/backend-core/planned-entry-l3` · `agent/frontend/planned-entry-ui` · **base** main (R1·R2 머지 후)

### 결과물 (backend-core — L3 wiring)
- `PositionViAutoExitWatcher` 가 lot plan.contingency.viAction 우선 참조(없으면 현행 ViActionPolicy).
- `AutopilotEodLiquidationWorker` 가 plan.contingency.eod 참조(없으면 현행 allow_overnight 토글).
- **plan 없음/게이트 off → 현행 동작 100% 유지**(add-only override).

### 결과물 (frontend — UI "예상 계획" 패널 확장)
- 기존 "예상 매수 계획" 패널(ADR 0128 R3, ScaleInPlanPreviewInline)에 TradePlan 표시 확장: thesis·TP(tpPct/tpPrice)·SL(type/slPct/slPrice)·trailing·hold(min/max bars)·exitConditions·contingency·status·exitReason.
- 청산 후 exitReason 가시화. **plan 미기록(USER·게이트 off) → 패널 미표시**(zero-regression).

### 테스트
- (BE) VI/EOD plan.contingency 참조·plan 없음 현행 유지.
- (FE) 패널 렌더(plan 있음/없음)·exitReason 표시. qa Visual(헌법 §9-2 — 핵심 화면 변경) 동반.
- **통합 E2E**: 진입→plan 박제→PlanExecutor 청산→exitReason 표시 데이터 흐름 일괄(development-rules §4 R3 일괄).

### 검증·CHANGELOG
- BE `:consumer:check` GREEN · FE `pnpm typecheck/build` GREEN.
- CHANGELOG row + anchor `adr-0130-slot-r3`.

---

## 머지 순서·게이트

```
R1(strategy) ─┐
              ├─→ R2(strategy) ─→ R3(BE L3 + FE UI + 통합)
R-aux(backend)┘  (R1·R-aux 병행 — add-only 충돌 0)
```

- R1·R-aux 는 영역 비충돌(domain/persistence vs consumer/risk) → 병렬 가능.
- 전 라운드 머지 후 **게이트 off 상태**로 배포(행동 변화 0). 사용자/orchestrator 가 MOCK→canary evidence(spec §8) 확인 후 게이트 on.
