# ADR 0111 — RiskPlan scale-in / 평단 기준 exit 확장 (ADR 0106 amend)

- 상태: **Accepted (2026-06-05)**
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 분할 매수·익절/손절 통합 요구 결재
- 영역: 전략 도메인 + 시그널/주문 엔진 + 백테스트/리플레이 정합 (자본 path)
- 관계:
  - spec: `_workspace/specs/2026-06-05-scale-in-exit-ownership-spec.md`
  - **ADR 0106 amend** (RiskPlan 필수 — 본 ADR이 exit 모델 확장)
  - ADR 0110(전략별 포지션 귀속 — 평단/exit의 토대)
  - ADR 0107(Scenario Replay — Fill model §2-6 stop/take 우선순위 정합) · ADR 0056(Backtest) · ADR 0022(DSL)
  - ADR 0109(canary hard cap — 분할 주문 상호작용 §6-1)

## 1. Context

ADR 0106은 자동매매 BUY 전 RiskPlan(`fixed_stop_loss_pct`·`take_profit_pct`)을 필수화했다. 그러나 현행 exit 구현(`RealtimeSignalGenerator.evaluateTakeProfitExit`)은:

- **익절(take-profit)만 청산** — 손절가는 BUY 게이트 검증에만 쓰이고 실제 stop-loss exit는 미구현.
- **단일 진입 + 전량 청산** — 분할 매수(scale-in)·부분 청산(분할 익절) 없음.
- exit가 user+code 합산 평단 기준 — 전략별 평단 아님(ADR 0110에서 토대 신설).

사용자 요구(2026-06-05): "분할 매수 해서 익절가랑 손절가 정해서 터는 게 필요해. 분할 매수할 땐 비중 조절도 되어야 하고." → RiskPlan을 분할 진입 + 평단 기준 손절/익절 + 분할 익절로 확장해야 한다.

## 2. Decision

ADR 0106 RiskPlan을 깨지 않고 add-only로 확장한다. 미설정 전략은 현행 단일 진입 + 전량 익절 그대로(하위호환).

### 2-1. 분할 매수 — `ScaleInPlan` (StrategyDefinition.scaleIn 신규, nullable)

- `totalSlices`(N회, 1~10 cap) + `sliceWeights`(회당 비중% 리스트, 합=100 / null이면 균등) + `reentryTrigger`(`SIGNAL_REPEAT` default | `PRICE_DROP_PCT`) + `priceDropPct` + `minSliceIntervalSec`(default 60).
- 회차 수량: `floor(종목예산 × sliceWeight[filledLots]% / priceAtSignal)`. 비중은 RiskConfig POSITION_LIMIT budget 분배(budget 초과 금지).
- 진행도: `strategy_position.filled_lots < totalSlices`일 때만 추가 진입. 완료 시 BUY 무시(평단 오염 방지).
- **매 회차 RiskPlan preflight 통과 의무**(ADR 0106 fail-closed 유지) — 손절/익절 없는 추가 진입 차단.

### 2-2. 평단 기준 exit 확장

- **stop-loss exit 신규**: `evaluateStopLossExit` — 현재가 ≤ `strategy_position.avgPrice` × (1 - `fixedStopLossPct`/100) → 전량 SELL(reason `STOP_LOSS`).
- **분할 익절 신규**: `partialExits: List<PartialExit>?`(StrategyDefinition 신규, nullable). 각 `PartialExit(triggerProfitPct, exitWeightPct)` 도달 시 보유분 weight%만 SELL(reason `PARTIAL_TAKE_PROFIT`, slice index). weight 합 ≤ 100.
- **전량 익절(기존)**: `takeProfitPct` 도달 시 잔량 전량 SELL.
- **평단 권위**: 모든 exit는 `strategy_position.avgPrice`(전략 평단) 기준 — ADR 0110 토대.

### 2-3. 동시 터치 우선순위 (ADR 0107 §2-6 정합)

같은 boundary에서 stop-loss·take-profit 동시 조건이면 **stop-loss 우선**(보수적). live·리플레이·백테스트 모두 동일.

### 2-4. `ScaleInExitCalculator` 단일 순수 함수 (PR #730 패턴)

scale-in 회차 진입 + 평단 갱신 + exit 평가 로직을 도메인/strategy-engine **순수 함수**로 추출한다. 입력(현재 strategy_position·현재가·ScaleInPlan·ExitPlan) → 출력(다음 액션: `ENTER_SLICE(qty)`·`STOP_LOSS(qty)`·`PARTIAL_TP(qty)`·`TAKE_PROFIT(qty)`·`HOLD`). **live(RealtimeSignalGenerator)·리플레이(ScenarioLedger)·백테스트(ADR 0056)가 이 단일 함수를 호출** → "리플레이에선 분할 익절됐는데 라이브는 안 됨" 의심 원천 차단(readiness P1 정합).

### 2-5. DSL 미확장

scale-in/exit는 **포지션 사이징·리스크 정책**(Sizing·RiskPlan 레이어)이지 진입/청산 **조건**(DSL)이 아니다. `SIGNAL_REPEAT`는 기존 BUY DSL 재발화 재사용, `PRICE_DROP_PCT`는 엔진 설정값 비교. **DSL parser/evaluator 변경 0**. `.kis.yaml` 호환 보존.

### 2-6. 영속 (Flyway V66)

`strategy` 테이블 add-only — `scale_in_config JSONB NULL`·`partial_exits JSONB NULL`(spec §4 LOCK). 가변 리스트 → JSONB(ADR 0101 user_preference 패턴 정합). 조회 항상 strategy 단위.

## 3. Consequences

- 사용자가 전략당 N회 분할 + 회당 비중 + 평단 기준 손절/익절 + 분할 익절을 설정·실행 가능.
- 손절가 없는 BUY는 ADR 0106 fail-closed 유지 — 분할 추가 회차도 매번 검증.
- scale-in `null` + partialExits `null` → 현행 단일 진입 + 전량 익절(하위호환 — 기존 전략 0 변경).
- canary 시 분할 N회 = REAL 주문 N건 → ADR 0109 일일 횟수 cap 빠르게 소진(spec §6-1 runbook 가이드 — 코드 0 변경).

## 4. 운영 ADR 양식 3 슬롯 (헌법 §9-5)

### 4-1. SLO 영향

직접 SLO 수치 영향 0. stop-loss exit 신규로 자본 보호 강화(손실 자동 청산). 분할 = 다건 주문이라 S-1(시그널→주문) 빈도 증가하나 회차 간 `minSliceIntervalSec`(default 60s) + RiskConfig `maxTradesPerDay`로 제어.

### 4-2. rollback plan

- 본 PR(ADR + spec + README + CHANGELOG)은 운영 코드 0 touch.
- 후속 구현: V66 JSONB는 add-only NULL — rollback 시 scale-in/exit 코드 사용만 제거하면 현행 takeProfit 전량 익절로 복귀(자본 보호 공백 0 — fixedStopLoss BUY 게이트 유지). ScaleInExitCalculator는 순수 함수라 revert 안전.

### 4-3. 관측 매트릭스

| 측정 항목 | 목표 | 측정 방법 |
|----------|------|----------|
| stop-loss exit 발사 | 손절가 도달 시 청산 | `risk_event`/signal reason `STOP_LOSS` |
| 분할 진입 회차 추적 | filled_lots ≤ totalSlices | `strategy_position.filled_lots` |
| 분할 익절 부분 청산 | weight% 정확 | signal reason `PARTIAL_TAKE_PROFIT` + slice index |
| 리플레이 = 라이브 decision | 동일 | ScaleInExitCalculator 공유 회귀 테스트 |
| Visual 회귀 (frontend) | 빌더 scale-in/exit 입력 | qa Visual R3 시나리오 |

## 5. 트레이드오프

| 결정 | 채택 사유 | 거부 대안 |
|------|----------|----------|
| ADR 0106 amend (별 ADR 아님) | RiskPlan 모델 확장이라 0106 계보 정합 | 신규 독립 ADR → RiskPlan 권위 분산 |
| JSONB 정책 필드 | 가변 리스트 MVP 단순 + ADR 0101 정합 | 정규화 테이블 → 과설계 + JOIN |
| ScaleInExitCalculator 순수 함수 공유 | 리플레이≠라이브 의심 차단 + PR #730 정합 | 3곳 각자 구현 → drift |
| DSL 미확장 (정책 레이어) | DSL parser 0 변경 + .kis.yaml 호환 | DSL 신규 연산자 → 과추상화 |
| stop-loss 우선(동시 터치) | ADR 0107 §2-6 보수 정합 | take-profit 우선 → 수익 유리 추론(금지) |
| trailingStop·ATR post-MVP | MVP 고정 손절/익절로 요구 충족 | 본 사이클 전부 → 범위 폭증(v1/v2 회귀 금지) |

## 6. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-06-05 | **ADR 0111 Accepted (ADR 0106 amend)** — ScaleInPlan(N회·회당 비중·재진입 트리거, V66 JSONB) + 평단 기준 stop-loss exit 신규 + 분할 익절(PartialExit) + ScaleInExitCalculator 단일 순수 함수(live·리플레이·백테스트 공유) + 동시 터치 stop 우선(ADR 0107 정합) + DSL 미확장(정책 레이어). 하위호환(scale-in null=현행). 운영 코드 0 touch. | architect (사용자 분할매수 요구) |
