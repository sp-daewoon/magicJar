# spec — 분할 매수(scale-in) + 비중 조절 + 익절/손절 청산 + 전략별 포지션 귀속 통합

- 작성: architect (2026-06-05)
- 브랜치: `agent/architect/scale-in-exit-position-spec`
- 동반 산출물: ADR 0110(전략별 포지션 귀속) · ADR 0111(RiskPlan scale-in/exit amend) · CHANGELOG 슬롯 4 row
- 트리거: 최고 관리자 원문(2026-06-05) — "분할 매수 해서 익절가랑 손절가 정해서 터는 게 필요해. 분할 매수할 땐 비중 조절도 되어야 하고. 지금 작업하는 게 있으니깐 같이 엮일 수 있다고 보여지거든 다 고려될까?"
- 관계 readiness: `docs/assessments/2026-06-05-autotrading-readiness.html` Phase C-2(전략별 포지션 귀속 +5 P1)

---

## 0. 한 문장 요약

전략당 진입을 **N회 분할(scale-in) + 회당 비중%**로 나누고, 분할로 움직이는 **전략별 평단** 기준으로 **고정 손절가/익절가**(및 옵션 분할 익절)를 산정해 **전략이 산 물량만** 터는 구조를, 기존 단순 매수/매도 전략의 하위호환을 깨지 않고 add-only로 도입한다.

---

## 1. 목적 / 범위 / 비범위

### 1-1. 목적

3개의 사용자 요구를 **하나의 자본 흐름**으로 통합 설계한다.

1. **분할 매수(scale-in)** — 전략당 진입을 1회가 아니라 N회로 나누고, 회당 비중을 조절한다.
2. **익절/손절 청산(exit)** — 분할로 변하는 평단 기준으로 손절가·익절가를 (재)산정하고 보유분을 청산한다(분할 익절 옵션 포함).
3. **전략별 포지션 귀속(ownership)** — 동일 종목을 2전략이 동시 운용해도 "이 전략이 산 물량"만 추적·청산한다(readiness C-2).

세 요구는 따로 떼면 모순이 난다. 분할 매수는 "전략별로 얼마나 쌓였나"를 알아야 하고(=ownership), 익절/손절은 "그 전략의 평단"을 알아야 하며(=ownership + scale-in 평단), 분할 익절은 "그 전략 보유분의 일부"를 팔아야 한다(=ownership). 따라서 **ownership이 토대**이고 그 위에 scale-in·exit가 올라간다.

### 1-2. 범위 (본 spec이 설계하는 것)

- 전략별 포지션 귀속 도메인 모델 + `strategy_position` 테이블(Flyway 번호 LOCK).
- scale-in 설정 모델(`ScaleInPlan`) — N회·회당 비중·추가 진입 트리거.
- RiskPlan exit 모델 확장(ADR 0106 amend) — 평단 기준 stop-loss exit 구현 + 분할 익절(부분 청산) 옵션.
- 자본/데이터 흐름 + 상호작용 매트릭스(canary cap·RiskGateway 룰·리플레이/백테스트 정합·전략 빌더 UI/DSL).
- 라운드 분할 plan(R1 도메인+스키마 → R2 엔진/주문 → R3 UI+리플레이·백테스트 정합+테스트) + 라운드별 담당 에이전트.

### 1-3. 비범위 (NOT in scope — post-MVP로 명시)

- **트레일링 스탑 자동 추적** — `StrategyDefinition.trailingStop` 필드는 이미 있으나 본 사이클 구현 X(분할 평단 + 고정 손절/익절이 MVP). post-MVP 확장 슬롯.
- **ATR·FVG·OB 무효화 기반 동적 손절** — ADR 0106 §Consequences "후속 확장 RiskPlan source" 그대로 유지.
- **KIS 서버사이드 stop/OCO 주문** — 1차는 내부 WS 감시 기반 보호(ADR 0106 §37 정합).
- **분할 매수 시점의 추가 진입을 위한 신규 DSL 연산자** — §6-4에서 "DSL 확장 불요" 판정. 추가 진입 트리거는 설정값(가격 하락폭·재시그널)으로 충분.
- **운영 코드 touch** — 본 PR은 spec/ADR md only(spec 사이클).
- **전략 리서치 "추천 실행 계획" 자동 출력 연결 (item-4 post-MVP 확장 슬롯)** — 오늘 머지된 전략 리서치 화면(`frontend/src/pages/Strategy/StrategyResearchPage.tsx` + `api/strategyResearch.ts` `StrategyResearchCandidateDto.{entryRules,exitRules,riskRules,entryDslDraft,exitDslDraft}` + `lib/strategyResearch/builderDraft.ts`가 이미 `stopLossPct·takeProfitPct·maxHoldBars`를 빌더 draft로 주입)이, 향후 "추천 실행 계획"(예: 3분할 진입·짧은 익절·타이트한 손절)까지 출력하는 연결 고리를 **post-MVP 확장 슬롯**으로 명시한다. 본 spec의 **`ScaleInPlan`·`ExitPlan`(PartialExit 포함) 도메인 모델이 리서치 출력 DTO로 그대로 재사용 가능**하다 — 리서치 후보가 `scaleIn`(분할 회수·회당 비중)·`partialExits`(분할 익절 단계)를 builderDraft에 주입하면 사용자가 빌더에서 검증·저장하는 출발점이 된다(자동매매 결정을 대신하지 않음 — 방향성 가드 정합). 본 사이클은 모델만 정의하고 리서치 연결은 구현 X.

---

## 2. 현행 실측 (추측 0 — 실제 파일 인용)

| 영역 | 코드/스키마 | 현행 동작 (실측) | 본 spec과의 관계 |
|------|------------|------------------|------------------|
| Sizing | `domain/.../strategy/StrategyDefinition.kt` `Sizing(type, fraction)` | `FIXED_FRACTIONAL` + `fraction`(default 0.05) — **단일 비중 1회 진입** | scale-in 회당 비중의 base. 확장 진입점 |
| RiskPlan | `StrategyDefinition.fixedStopLoss`·`takeProfitPct` (V59·V60) | BUY 전 fail-closed(ADR 0106). exit는 takeProfit만 구현 | 평단 기준 stop-loss exit 추가 + 분할 익절 |
| exit 구현 | `consumer/.../signal/RealtimeSignalGenerator.kt` `evaluateTakeProfitExit` L362~ | `takeProfitPct` 도달 시 전량 SELL. **stop-loss exit 미구현 · 부분 청산 없음** | exit 엔진 확장 본체 |
| position | `position` 테이블 `UNIQUE(user_id, code)` (V1) | **user+code 단일 행** — 전략별 분리 0 | ownership 핵심 갭. `strategy_position` 신설 |
| Position 도메인 | `domain/.../position/Position.kt` | `userId·code·quantity·avgPrice`. strategyId 없음 | ownership 확장 대상 |
| RiskGateway | `consumer/.../risk/RiskGatewayEvaluator.kt` | `holdingQuantity`·`currentCodeExposure`·`reservedSellQuantity` 모두 **user+code 기준** | 전략별 분기 영향 매트릭스(§6-2) |
| OrderIntent | `domain/.../order/execution/OrderIntent.kt` | `strategyId` **이미 보유** | ownership 갱신 키. 추가 변경 불요 |
| Signal | `domain/.../signal/Signal.kt` | `strategyId`·`priceAtSignal` 보유 | scale-in 회차 reason 확장 |
| canary cap | ADR 0109 `CanaryHardCapGate`(후속 구현) | REAL 주문 1건 수량/notional + 일일 횟수 cap | 분할=다건 주문 → §6-1 상호작용 |
| 리플레이 | ADR 0107 `ScenarioLedger`·Fill model §2-6 | stop/take-profit 동시 터치 보수 규칙 이미 있음 | scale-in/exit 동일 로직 재사용(§6-3) |
| 백테스트 | ADR 0056 Backtest Engine | 룩어헤드 가드 + cost model | scale-in/exit 동일 평가(§6-3) |
| 전략 빌더 UI | `frontend/.../strategy/StrategySaveModal.tsx`·`RiskPlanPreviewPanel.tsx` | Sizing·RiskPlan 입력 패널 존재 | scale-in/exit 입력 add-only(§6-4) |

**Flyway main 최신 = V63**(`scenario_replay`). 작업트리의 V64(`market_calendar_2026_special_holidays`)는 별 브랜치(codex/candle-backfill) 산출물 — 본 spec은 **V64 점유 가정**하고 신규 마이그레이션을 **V65부터** 채번한다(§4-LOCK). R1 dispatch 시점에 `git ls-tree origin/main | grep V64` 재확인 의무(ADR 0102).

---

## 3. 도메인 모델 (신규/확장)

### 3-1. 전략별 포지션 귀속 — `StrategyPosition` (신규, ownership 토대)

기존 `Position`(user+code 합산, KIS 잔고 권위)은 **유지**한다. 그 아래에 전략별 lot ledger를 둔다.

```
StrategyPosition  (도메인, 신규)
  id            Long?
  userId        String
  strategyId    Long
  code          String
  quantity      Int           // 이 전략이 현재 보유한 수량 (분할 누적 - 청산)
  avgPrice      BigDecimal    // 이 전략의 평단 (분할 매수로 가중평균 갱신)
  filledLots    Int           // 지금까지 체결된 분할 회차 수 (scale-in 진행도)
  executedExitStages  Set<Int>  // 이미 발화된 PartialExit 단계 인덱스 집합 (부분 익절 중복 발화 방지 — scale-in filledLots와 대칭)
  realizedPnl   BigDecimal    // 이 전략의 누적 실현 손익 (부분 청산 누적)
  openedAt      Instant
  updatedAt     Instant
```

- **부분 익절 중복 발화 방지 불변식 (item-1 보강)**: PartialExit 각 단계는 **1회만 발화**한다. 가격이 trigger 위에 머무는 동안 매 boundary/tick마다 같은 단계 SELL이 재발화되는 risk를 `executedExitStages`로 차단한다. `evaluatePartialTakeProfit`는 `stageIndex !in executedExitStages` AND `profitPct >= partialExits[stageIndex].triggerProfitPct`일 때만 SELL을 발행하고, **체결 확정 시** `executedExitStages += stageIndex`(체결 listener 책임 — 발행 시점이 아니라 체결 시점에 마킹해 미체결 reject 시 재시도 가능). scale-in의 `filledLots`(진입 진행도)와 정확히 대칭 구조다(진입=단조 증가 카운터, 청산=발화 단계 집합).
- **전량 청산 시 reset**: `quantity==0`(STOP_LOSS·최종 TAKE_PROFIT으로 전량 청산)이면 `filledLots=0`·`executedExitStages={}` reset(행 유지 — 재진입 시 새 사이클). 부분 청산만으로는 reset 안 함(단계 단조 진행 보존).

- **불변식**: `sum(StrategyPosition.quantity WHERE user+code) == Position.quantity` (정합 검증 — drift 시 KIS 잔고가 권위, ADR 0106 §6 SELL 가용 수량 계산 정합). drift 발견 시 `STRATEGY_POSITION_DRIFT` SystemEvent(WARN) + 보수적으로 Position(KIS 권위) 우선.
- **평단 갱신**: BUY 체결 시 `avgPrice = (avgPrice*quantity + fillPrice*fillQty) / (quantity+fillQty)`, `quantity += fillQty`, `filledLots += 1`.
- **부분/전량 청산**: SELL 체결 시 `realizedPnl += (fillPrice - avgPrice)*sellQty`, `quantity -= sellQty`. `quantity==0`이면 평단 reset(행 유지 — Position 패턴 정합).
- **귀속 키**: OrderIntent·order_execution은 이미 `strategyId` 보유 → 체결 listener가 strategyId로 StrategyPosition upsert.

### 3-2. 분할 매수 — `ScaleInPlan` (Sizing 확장)

`Sizing`을 깨지 않고 add-only로 감싼다. scale-in 미설정 전략은 현행 1회 진입 그대로(하위호환).

```
ScaleInPlan  (도메인, 신규 — StrategyDefinition.scaleIn: ScaleInPlan? = null)
  totalSlices       Int            // 분할 회수 N (예: 3). null/1이면 단일 진입(현행)
  sliceWeights      List<BigDecimal>?  // 회당 비중% 리스트 (합=100). null이면 균등 분할
  reentryTrigger    ReentryTrigger // 추가 진입 조건
  priceDropPct      BigDecimal?    // PRICE_DROP_PCT 시 하락폭 (예: -3.0)
  minSliceIntervalSec Int?         // 회차 간 최소 간격(과다 진입 방지, default 60)

ReentryTrigger (enum)
  SIGNAL_REPEAT      // BUY 시그널이 다시 발화하면 다음 회차 진입 (default — DSL 재사용)
  PRICE_DROP_PCT     // 직전 회차 체결가 대비 X% 하락 시 다음 회차 (averaging down)
```

- **회당 비중**: `sliceWeights` 명시 시 그대로(예: [50,30,20] = 첫 진입 50% 비중), null이면 `100/totalSlices` 균등. 비중은 **전략 종목 예산(RiskConfig POSITION_LIMIT budget)에 대한 분배**다 — RiskConfig budget을 넘기지 않는다(§6-2).
- **회차 수량 산정**: `sliceQuantity = floor((종목 예산 × sliceWeight%) / priceAtSignal)`. 기존 `resolveBuyQuantity`(전체 budget 소진)를 회차 weight 적용으로 확장.
- **진행도**: `StrategyPosition.filledLots < totalSlices`일 때만 추가 진입. `filledLots == totalSlices`면 scale-in 완료(이후 BUY 시그널 무시 — 평단 오염 방지).
- **하위호환**: `scaleIn == null` → `totalSlices=1` 동작 = 현행 단일 진입. 기존 전략 0 변경.

### 3-3. 청산 — RiskPlan exit 확장 (ADR 0106 amend)

기존 RiskPlan(`fixedStopLoss`·`takeProfitPct`)을 평단 기준 exit로 확장한다.

```
ExitPlan  (개념 — StrategyDefinition 기존 필드 + 신규 add-only)
  fixedStopLossPct   BigDecimal   // 기존 — 평단 대비 손절% (BUY 게이트 + exit 트리거)
  takeProfitPct      BigDecimal   // 기존 — 평단 대비 익절%
  partialExits       List<PartialExit>?  // 신규 — 분할 익절 (옵션)

PartialExit
  triggerProfitPct   BigDecimal   // 평단 대비 이 수익률 도달 시
  exitWeightPct      BigDecimal   // 보유 수량의 이 비율% 청산 (합 <= 100)
```

- **평단 기준**: stop-loss·take-profit·partialExit 모두 **`StrategyPosition.avgPrice`** 기준(user+code 합산 평단이 아니라 전략 평단). 이것이 ownership을 토대로 하는 이유.
- **stop-loss exit 신규 구현**: 현행 `evaluateTakeProfitExit`는 익절만. 동일 패턴으로 `evaluateStopLossExit`(현재가 ≤ 평단×(1-stop%) → 전량 SELL, reason `STOP_LOSS`) 추가.
- **분할 익절**: `partialExits` 설정 시 각 trigger 도달마다 보유분의 weight%만 SELL(reason `PARTIAL_TAKE_PROFIT`, slice index 기록). **단계당 1회만 발화** — `StrategyPosition.executedExitStages`(§3-1)에 기록된 단계는 재발화 금지(가격이 trigger 위에 계속 머물러도 중복 SELL 0). 마지막 익절·손절은 잔량 전량.
- **우선순위(동시 터치)**: ADR 0107 Fill model §2-6 정합 — **stop-loss 우선**(보수적). 같은 boundary에서 stop·take 동시 조건이면 손절 먼저.
- **하위호환**: `partialExits == null` → 현행 전량 익절 그대로.

---

## 4. 스키마 (Flyway 번호 LOCK)

| 번호 | 파일 | 내용 | 모듈 |
|------|------|------|------|
| **V65** | `V65__strategy_position.sql` | `strategy_position` 테이블 신설 — `UNIQUE(user_id, strategy_id, code)` + FK(user_account·strategy·instrument) + `quantity·avg_price·filled_lots·realized_pnl·opened_at·updated_at` + **`executed_exit_stages JSONB NOT NULL DEFAULT '[]'`**(발화된 PartialExit 단계 인덱스 배열 — 부분 익절 중복 발화 방지·§3-1 item-1). 인덱스 `(user_id, code)`(정합 검증용)·`(strategy_id)` | api (db/migration) |
| **V66** | `V66__strategy_scale_in_exit.sql` | `strategy` 테이블 add-only — `scale_in_config JSONB NULL`(ScaleInPlan 직렬화)·`partial_exits JSONB NULL`(PartialExit 리스트). 기존 `fixed_stop_loss_pct`·`take_profit_pct` 유지 | api (db/migration) |

**LOCK 근거**: main 최신 V63 + 작업트리 V64(market_calendar, 별 브랜치) 점유 가정 → V65·V66. R1 dispatch 시점 `git ls-tree origin/main --name-only | grep 'db/migration/V6[4-9]'` 재확인 의무. V64가 main 미머지면 V64·V65로 당김(orchestrator 판단).

- **JSONB 선택 근거**: scale-in/partial-exit는 가변 길이 리스트 + 선택적 구조 → 정규화 테이블(scale_in_slice 등)은 MVP 과설계. `user_preference.extra` JSONB 패턴(ADR 0101) 정합. 조회는 항상 strategy 단위(부분 쿼리 불요).
- **add-only**: 기존 strategy 행은 두 JSONB 모두 NULL → scale-in 없는 단일 진입 + 전량 익절(현행) 동작. rollback 시 컬럼 사용만 제거.

---

## 5. 데이터 / 자본 흐름

### 5-1. 분할 매수 흐름

```
BUY 시그널 (RealtimeSignalGenerator)
  → StrategyPosition 조회 (strategyId+code)
  → filledLots >= totalSlices ? → 무시(scale-in 완료, 평단 오염 방지)
  → ReentryTrigger 평가:
       SIGNAL_REPEAT → BUY 시그널 발화 자체가 트리거
       PRICE_DROP_PCT → 직전 회차 체결가 대비 priceDropPct 이상 하락 확인
  → sliceQuantity = floor(종목예산 × sliceWeight[filledLots]% / priceAtSignal)
  → OrderIntent(strategyId, quantity=sliceQuantity) publish
  → [RiskGateway → canary cap(§6-1) → OrderRouter → KIS]
  → 체결 listener → StrategyPosition 평단 갱신(filledLots++) + Position(KIS 합산) 갱신
```

### 5-2. 청산 흐름

```
boundary close / tick (RealtimeSignalGenerator)
  → StrategyPosition 조회 (strategyId+code, quantity>0)
  → evaluateStopLossExit: 현재가 ≤ avgPrice×(1-stop%) → 전량 SELL(STOP_LOSS)  [우선]
  → evaluatePartialTakeProfit: profitPct >= partialExit.trigger → 보유분×weight% SELL(PARTIAL_TAKE_PROFIT)
  → evaluateTakeProfitExit: profitPct >= takeProfitPct → 잔량 전량 SELL(TAKE_PROFIT)
  → SELL OrderIntent(strategyId, quantity=청산수량) publish
  → [RiskGateway SELL_QUANTITY 검증(§6-2) → OrderRouter → KIS]
  → 체결 → StrategyPosition realizedPnl 누적 + quantity 감소(0이면 평단 reset)
```

- **자본 보호 불변**: 손절가 없는 BUY는 ADR 0106 fail-closed 유지. scale-in 추가 회차도 RiskPlan preflight 통과 의무(매 회차 손절/익절 유효).

### 5-3. leg(회차/단계) 단위 idempotency 정합 ★ (item-2 보강)

분할 매수 N회·분할 익절 M단계는 **하나의 전략당 다건 주문**을 만든다. 각 leg가 자본 중복(같은 회차 2번 체결) 없이, 그러나 정당한 다음 회차는 막히지 않게 발행되는 경로를 명문화한다.

**(0) 현행 idempotency 권위 실측 (추측 0)** — 본 spec 설계는 아래 main 코드 실측 위에 선다:

| 자원 | V20 본문(최초) | **V38 현행(LOCK)** | 권위 |
|------|---------------|-------------------|------|
| `order_request.signal_id` | `NOT NULL REFERENCES signal(id)` FK | **FK DROP** (manual sentinel 0L 허용) | FK 강제 없음. AUTO는 여전히 실제 signal_id 채움 |
| `uq_order_signal_user (signal_id, user_id)` | UNIQUE 제약 | **DROP** (manual 다건 허용) | **더 이상 자본 중복 보호 권위 아님** |
| `uq_order_idempotency (idempotency_key)` | UNIQUE | **유지** | ★ **자본 중복 보호 단일 권위** (Redis SETNX 60s + DB UQ 이중 — V20 §5·V38 §22 LOCK) |

→ dispatch 지시 전제("`uq_order_signal_user` 제약 정합 / signal_id NOT NULL FK라 시그널 없이 OrderIntent 못 만든다")는 **V38에서 이미 완화됨**. 따라서 leg 중복 방지의 권위는 `uq_order_signal_user`가 아니라 **`idempotencyKey` UQ + Redis SETNX**다. 본 spec은 이 실측 권위 위에서 leg 경로를 설계한다.

**(a) SIGNAL_REPEAT 회차 = 새 시그널** — BUY 시그널이 다시 발화하면 `signal` UQ `(strategy_id, code, interval, ts)`(V15)에서 **다른 ts → 새 행**이라 자연 통과. 새 signalId → AUTO idempotencyKey가 자연히 distinct. 별도 처리 불요.

**(b) PRICE_DROP_PCT 회차의 발행 경로 확정** — averaging-down 회차는 BUY DSL 재발화 없이 "직전 체결가 대비 X% 하락"으로 트리거된다. 그러나 **현행 AUTO 흐름은 OrderIntent를 시그널 없이 직접 만들지 않는다**(OrderIntent.signalId·order_request.signal_id 컬럼 = AUTO 감사 lineage의 출발점). 따라서:
  - PRICE_DROP_PCT 트리거도 `RealtimeSignalGenerator`가 **시그널을 발행**하고(reason에 `SCALE_IN_SLICE#{filledLots+1}:PRICE_DROP` 회차 명시), 그 시그널로 주문하는 **기존 AUTO 경로를 그대로 통과**한다(시그널 없이 OrderIntent 생성하는 신규 우회 경로 0 — 흐름 LOCK).
  - 시그널 UQ `(strategy_id, code, interval, ts)`와의 중복 방지 관계: PRICE_DROP 회차의 `ts`는 트리거가 발생한 boundary/tick의 ts → 직전 회차와 다른 ts라 UQ 자연 통과. 같은 boundary에서 같은 회차가 두 번 평가돼도(중복 tick) **같은 ts → UQ가 시그널 단에서 1차 차단**, 그 뒤 idempotencyKey UQ가 주문 단에서 2차 차단(이중).

**(c) 분할 익절 SELL도 단계별 시그널 발행** — `evaluatePartialTakeProfit`도 SELL **시그널을 발행**하고(reason `PARTIAL_TAKE_PROFIT#{stageIndex}`), 그 시그널로 SELL 주문. STOP_LOSS·최종 TAKE_PROFIT도 동일(reason `STOP_LOSS`·`TAKE_PROFIT`). 즉 모든 leg는 "시그널 → OrderIntent" AUTO 경로 단일.

**(d) idempotencyKey 형식에 회차/단계 포함 결정** — 현행 AUTO 키 `"{signalId}:{userId}"`(OrderIntent.kt L22 실측)는 leg마다 signalId가 달라(b·c) 이미 distinct하다. 다만 **방어적으로 회차/단계를 명시 포함**해 같은 signalId 재사용 사고(예: 시그널 재처리)에도 중복 차단을 보장한다:
  - scale-in BUY: `"{signalId}:{userId}:slice#{filledLots+1}"`
  - 분할 익절 SELL: `"{signalId}:{userId}:ptp#{stageIndex}"`
  - stop/최종 익절: `"{signalId}:{userId}:exit#{STOP_LOSS|TAKE_PROFIT}"`
  - **하위호환**: scale-in 미설정(단일 진입) 전략은 기존 `"{signalId}:{userId}"` 그대로 유지(suffix 0) — 기존 키 동작 불변. suffix는 scale-in/exit 활성 leg에만 add.

→ **결론**: leg 중복 방지 권위 = `idempotencyKey` UQ + Redis SETNX(V38 실측). PRICE_DROP·분할 익절 모두 시그널 발행 → AUTO 경로 단일(시그널 없이 OrderIntent 생성 0). idempotencyKey에 회차/단계 suffix를 add(하위호환 보존). 신규 Flyway·신규 우회 경로 0.

---

## 6. 상호작용 매트릭스 ★ 핵심

### 6-1. ADR 0109 canary hard cap × 분할 주문

| 항목 | 상호작용 | 결정 |
|------|----------|------|
| `MAGICJAR_CANARY_MAX_ORDER_QTY` (1건 수량) | 분할은 **회당 1 주문** → 회차 수량이 cap 검사 대상. 회차 수량 ≤ cap이면 통과. 분할이 cap을 우회하지 않음(회차별 독립 검사) | **독립 유지** — scale-in은 cap을 회피하지 않는다. CanaryHardCapGate는 OrderRouter 단 1건 단위라 회차마다 정상 작동 |
| `MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW` (1건 notional) | 회차 notional(sliceQty×price) 검사. 분할로 작아진 notional이 오히려 cap 통과 쉬움 | **독립 유지** — 회차별 검사 |
| `MAGICJAR_CANARY_MAX_ORDERS_PER_DAY` (일일 횟수) | ★ **핵심 상호작용**: 분할 N회 = REAL 주문 N건 → 일일 횟수 cap 빠르게 소진. 3분할 전략 1개 = 진입 3건 + 청산 1~N건 | **명시 의무**: canary 시 일일 횟수 cap은 **분할 회차를 합산 카운트**한다(주문 1건=1카운트, DB `order_request mode=REAL` count 정합 — ADR 0109 §2-4). runbook에 "N분할 전략의 canary는 일일 cap ≥ N×(진입+청산 예상) 설정" 가이드 추가. GAP-3과 RiskConfig `maxTradesPerDay`(전략당)도 분할 회차를 합산 — 이미 회차=주문이므로 자동 반영, 별도 변경 불요 |

**결론**: canary cap 3종은 **모두 분할 주문에 자동 정합**(회차=1주문 단위라 독립 작동). 단 일일 횟수 cap은 분할로 소진 속도가 빨라지므로 **runbook 가이드 1줄 + RiskConfig maxTradesPerDay 재점검**만 필요. 코드 변경 0(CanaryHardCapGate는 주문 1건 단위라 분할 인지 불요).

### 6-2. RiskGatewayEvaluator 룰 × 전략별 포지션

현행 RiskGateway는 user+code 기준. 전략별 ownership 도입 시 영향:

| 룰 | 현행(user+code) | 전략별 도입 후 | 결정 |
|----|-----------------|----------------|------|
| `SELL_WITHOUT_POSITION` | user+code holding 0이면 차단 | 전략이 산 물량을 그 전략으로 팔 때 **StrategyPosition** 기준 필요 | **전략별 분기** — SELL은 `StrategyPosition(strategyId).quantity` 기준 검사. user+code 합산엔 있어도 그 전략 물량 0이면 차단(다전략 교차 청산 방지) |
| `SELL_QUANTITY_UNAVAILABLE` (ADR 0106 §7) | `holding - reservedSell` 기준 | 전략 보유분 - 그 전략 미체결 SELL | **전략별 분기** — `StrategyPosition.quantity - reservedSell(strategyId)`. 단 **KIS 잔고 하드 게이트(ADR 0106 §7)는 user+code 합산 유지**(KIS는 전략 모름 — 실잔고가 상위 권위) |
| `POSITION_LIMIT` (종목 예산) | user+code exposure vs RiskConfig budget | scale-in 회차 누적이 budget 초과 못함 | **유지 + 분할 정합** — `currentCodeExposure`는 user+code 합산 유지(RiskConfig는 사용자 단위 예산). 회차 누적 exposure가 budget 넘으면 POSITION_LIMIT 차단(scale-in 자동 상한). 단 비중은 **전략 budget 분배**이므로 회차 weight는 budget 내에서만 |
| `MAX_CONCURRENT_POSITIONS` | user 동시 보유 종목 수 | 다전략이 같은 종목 보유 시 1종목으로 셀지 N으로 셀지 | **종목 단위 유지**(user+distinct code) — 다전략이 같은 종목이면 1 포지션. 자본 노출 기준 정합 |
| `DAILY_LOSS_LIMIT`·`TRADE_FREQUENCY` | user/전략 단위 | 분할=다건 → frequency 빠르게 소진 | **유지** — frequency는 이미 전략 단위(maxTradesPerDay). 분할 회차 합산 자동 반영(§6-1 정합) |

**결론**: ownership 도입의 RiskGateway 영향은 **SELL 계열 2룰(SELL_WITHOUT_POSITION·SELL_QUANTITY_UNAVAILABLE)을 StrategyPosition 기준으로 분기**하되, **KIS 잔고 하드 게이트·POSITION_LIMIT 예산·동시보유는 user+code 합산 유지**(KIS·자본 노출은 전략 무관). 이 분리가 "전략이 산 물량만 그 전략으로 청산" + "실잔고 초과 SELL 차단" 둘 다 보존하는 핵심.

### 6-3. 백테스트 · 시나리오 리플레이 정합 (PR #730 패턴 재사용)

| 경로 | scale-in/exit 적용 방식 | 패턴 |
|------|------------------------|------|
| **시나리오 리플레이** (ADR 0107) | `ScenarioLedger`가 이미 가상 포지션 추적 → **전략별 lot**으로 확장(가상 StrategyPosition). scale-in 회차·평단 갱신·분할 익절·stop-loss 동일 로직 적용. Fill model §2-6 stop/take 동시 터치 보수 규칙 **그대로 재사용** | live 평단/exit 산정 로직을 **도메인 순수 함수로 추출**(PR #730의 `TradingHoursGate` 추출 패턴 1:1) → live·리플레이·백테스트 3곳 단일 구현 공유 |
| **백테스트** (ADR 0056) | 동일 추출 함수로 scale-in 회차 진입 + 평단 갱신 + exit 평가. 룩어헤드 가드(slice-by-time) 유지 | 동일 순수 함수 재사용 |
| **라이브** (RealtimeSignalGenerator) | 추출 함수 호출 + StrategyPosition port 조회 | 추출 함수 원본 |

**핵심 추출**: `ScaleInExitCalculator`(가칭, domain 또는 strategy-engine 순수 함수) — 입력(현재 StrategyPosition·현재가·ScaleInPlan·ExitPlan) → 출력(다음 액션: ENTER_SLICE(qty)·STOP_LOSS(qty)·PARTIAL_TP(qty)·TAKE_PROFIT(qty)·HOLD). live·리플레이·백테스트가 **이 단일 함수**를 호출 → "리플레이에선 분할 익절됐는데 라이브는 안 됨" 의심 원천 차단(PR #730 readiness P1 정합).

### 6-4. 전략 빌더 UI / DSL 확장 범위

| 영역 | 확장 | 결정 |
|------|------|------|
| **DSL** (`.kis.yaml` superset) | scale-in 추가 진입 트리거 | **신규 연산자 불요** — `SIGNAL_REPEAT`는 기존 BUY DSL 재발화 재사용, `PRICE_DROP_PCT`는 설정값(평단/직전체결가 대비 비교는 엔진 책임). DSL은 진입/청산 **조건**만, scale-in/exit는 **포지션 사이징·리스크 정책**이라 DSL 밖(StrategyDefinition 확장 필드) — 기존 Sizing·RiskPlan과 동일 레이어 |
| **전략 빌더 UI** | `StrategySaveModal`·`RiskPlanPreviewPanel`에 입력 add-only | 분할 회수 N + 회당 비중(균등/수동) 입력 + 분할 익절 단계 입력(trigger%·weight%). 미설정 시 현행 단일 진입+전량 익절 default(하위호환 가시화). 평단 시뮬 미리보기(선택, post-MVP) |
| **API DTO** | `StrategySaveRequest`에 `scaleIn`·`partialExits` 필드 add-only | Bean Validation: sliceWeights 합=100±0.01, partialExits weight 합≤100, totalSlices 1~10 cap(과분할 방지) |

**결론**: scale-in/exit는 DSL이 아니라 **StrategyDefinition 정책 필드**(Sizing·RiskPlan과 동일 레이어). DSL parser/evaluator 변경 0. UI는 기존 패널에 입력 add-only.

---

## 7. 자원 audit (ADR 0102 — 5단계)

| 자원 | 신설 제안 | main 점유 audit (실측) | 충돌 |
|------|-----------|------------------------|------|
| **Flyway** | V65(strategy_position)·V66(strategy scale_in/partial_exits JSONB) | main 최신 V63. 작업트리 V64(market_calendar, 별 브랜치) | R1 시점 V64 머지 여부 재확인. 미머지면 V64·V65로 당김 |
| **Kafka** | 없음 | order.intent/requested/filled·signal.generated 재사용. 분할=다건 OrderIntent(기존 토픽) | 충돌 0 |
| **Redis** | 없음 (StrategyPosition은 DB 권위. 회차 간격 가드는 DB updatedAt 또는 기존 SignalDedupeGuard 재사용) | — | 충돌 0 |
| **STOMP** | 없음 (포지션 갱신은 기존 position fanout 재사용, 전략별 표시는 frontend가 조회) | — | 충돌 0 |
| **REST** | 없음 (strategy save/get 기존 엔드포인트에 필드 add-only. 전략별 포지션 조회는 기존 position API에 strategyId 옵션 또는 신규 GET 1건 — R3 판단) | `/api/strategies`·`/api/positions` 존재 | add-only 또는 신규 1건(R3) |
| **ENV** | 없음 | — | 충돌 0 |
| **도메인 port** | `StrategyPositionPort`(신규) — RiskPositionPort 패턴 정합 | `RiskPositionPort`·`PositionRepository` 존재(헌법 §6 abstract만) | add-only port |

→ 신규 = **Flyway 2건 + 도메인 모델/port + JSONB 직렬화**. Kafka·Redis·STOMP·ENV 신설 0. REST는 add-only 또는 신규 1건(R3).

---

## 8. 라운드 분할 plan (개발 룰 3 정합 — 한 PR 4 layer 동시 변경 금지)

| 라운드 | 범위 | 담당 에이전트 | 산출물 | 테스트 |
|--------|------|--------------|--------|--------|
| **R1** | 도메인 + 스키마 + port 시그니처 | **backend-core** (도메인·Flyway·port) + **strategy-engine** (StrategyDefinition·ScaleInPlan·ExitPlan 도메인 모델) | `StrategyPosition`·`ScaleInPlan`·`ExitPlan`·`PartialExit` 도메인 + `StrategyPositionPort` abstract + V65·V66 Flyway + JSONB 직렬화 매핑 | R1 테스트 0건 OK(개발 룰 4) |
| **R2** | 엔진 + 주문 path + RiskGateway 분기 | **strategy-engine** (`ScaleInExitCalculator` 순수 함수 + RealtimeSignalGenerator scale-in/stop-loss/partial-tp 평가) + **backend-core** (체결 listener StrategyPosition upsert + RiskGateway SELL 룰 전략별 분기 + StrategyPositionPort 어댑터) | scale-in 회차 OrderIntent 발행 + 평단 기준 exit + RiskGateway SELL_WITHOUT_POSITION·SELL_QUANTITY 전략별 분기 | R2 테스트 0건 OK |
| **R3** | UI + 리플레이/백테스트 정합 + 일괄 테스트 | **frontend** (전략 빌더 scale-in/exit 입력 + 전략별 포지션 표시) + **strategy-engine** (리플레이 ScenarioLedger 전략별 lot + 백테스트 ScaleInExitCalculator 재사용) + **qa** (전 흐름 시나리오 테스트) | UI add-only + 리플레이/백테스트 단일 함수 공유 + 데이터 흐름 시나리오 테스트 일괄(개발 룰 4) | **R3 일괄 테스트** — scale-in 평단 갱신·stop-loss·partial-tp·다전략 교차 청산·canary cap×분할·하위호환(scale-in null=현행) |

**선후행**: R1 → R2 → R3 직렬(스키마·도메인이 엔진의 전제, 엔진이 UI/리플레이의 전제). R1 내부는 backend-core(Flyway·port)와 strategy-engine(도메인 모델)이 **add-only 병렬 가능**(다른 파일). R2·R3는 단일 함수 공유 의존이라 순차.

### 8-1. "리플레이 먼저" 활성화 게이트 ★ (item-3 보강 — Codex 원칙 정합)

Codex 원칙(ADR 0107 sandbox 정합): **리플레이 정합이 통과되기 전에는 실주문 경로에 scale-in/exit가 연결되면 안 된다.** R2에서 라이브 엔진을 구현하더라도 R3 리플레이 정합 + qa 시나리오가 통과하기 전까지는 scale-in/exit 전략이 실제 자본을 움직이지 못하도록 다음 순서를 LOCK한다:

1. **R2 (라이브 엔진 구현 시점)** — `ScaleInExitCalculator` + RealtimeSignalGenerator scale-in/stop-loss/partial-tp 평가를 구현하되, **scale-in 평가 feature toggle**(`magicjar.strategy.scale-in.enabled`, **default `false`**)로 게이트한다. toggle OFF면 scale-in 설정 전략도 현행 단일 진입 + 전량 익절로 평가(엔진은 들어왔으나 자본 경로 미연결). 동시에 **scale-in 설정 전략의 활성화(ACTIVE) 차단 게이트**를 둔다 — scale-in/partialExits가 설정된 전략은 toggle OFF 동안 `status=ACTIVE` 전이를 거부(`SCALE_IN_NOT_ENABLED` reject)하여 라이브 자본 경로 진입을 원천 차단.
2. **API 직접 호출 구멍 차단 (명시)** — `POST/PUT /api/strategies`로 scale-in 설정 전략을 만들 수는 있으나(DRAFT 저장은 허용 — 리플레이/백테스트 검증 대상), **활성화(ACTIVE)는 toggle OFF면 API 레벨에서도 거부**한다. UI 미노출만으로 막지 않고 서버 검증으로 막는다(UI 우회·직접 API 호출 방어).
3. **R3 (리플레이/백테스트 정합 + qa)** — ScenarioLedger 전략별 lot + 백테스트 ScaleInExitCalculator 재사용으로 "리플레이 = 라이브 decision" 회귀 테스트 통과 + qa 전 흐름 시나리오(§8 R3 테스트 목록) 통과.
4. **toggle 해제** — R3 정합이 GREEN인 뒤에야 `magicjar.strategy.scale-in.enabled=true` + 활성화 게이트 해제. 그 이후 scale-in 전략 ACTIVE 전이 허용.

→ 이 게이트로 "엔진은 R2에 있으나 리플레이로 검증되기 전엔 실주문 0" + "API 직접 호출로도 활성화 불가"를 보장한다(readiness P1 "리플레이≠라이브" 의심 + 무검증 실주문 둘 다 차단).

---

## 9. 트레이드오프

| 결정 | 채택 | 거부 대안 / 사유 |
|------|------|------------------|
| `strategy_position` 신규 테이블 (Position 유지) | KIS 잔고 권위 Position 보존 + 전략 lot 분리 추적 + 정합 검증 가능 | Position에 strategyId 추가 → UNIQUE(user,code) 깨짐 + KIS 잔고 매핑 모호 + 기존 데이터 마이그레이션 |
| scale-in/exit를 JSONB 정책 필드 | 가변 리스트 MVP 단순 + ADR 0101 패턴 정합 + 조회 항상 strategy 단위 | 정규화 테이블(scale_in_slice·partial_exit) → MVP 과설계 + JOIN 비용 |
| DSL 미확장 (정책 필드) | scale-in/exit는 사이징·리스크지 진입조건 아님 + DSL parser 0 변경 + Sizing·RiskPlan 레이어 정합 | DSL 신규 연산자 → parser/evaluator 변경 + .kis.yaml 호환 risk + 과추상화 |
| `ScaleInExitCalculator` 순수 함수 추출 (live/리플레이/백테스트 공유) | PR #730 패턴 정합 + "리플레이≠라이브" 의심 원천 차단 | 3곳 각자 구현 → drift + readiness P1 재발 |
| SELL 룰만 전략별 분기 (KIS게이트·예산·동시보유 합산 유지) | 전략별 청산 정합 + KIS 실잔고 권위 보존 + 자본 노출 합산 정합 | 모든 룰 전략별 → KIS 잔고 매핑 깨짐 + budget 중복 + 복잡도 폭증 |
| canary cap 코드 0 변경 (회차=1주문 자동 정합) | CanaryHardCapGate 주문 단위라 분할 자동 인지 + runbook 가이드만 | cap에 분할 인지 로직 → ADR 0109 독립성 훼손 |
| trailingStop·ATR·KIS OCO post-MVP | MVP 분할+고정 손절/익절로 사용자 요구 충족 + 과설계 회피(v1/v2 회귀 금지) | 본 사이클 전부 → 범위 폭증 + 자동매매 적합성 의심 |

---

## 10. ADR 발행 결정

| ADR | 제목 | 사유 |
|-----|------|------|
| **0110** | 전략별 포지션 귀속 — `strategy_position` 1급 도메인 | 스키마·도메인 신설 = architect ADR 필수(readiness C-2 명시). RiskGateway SELL 룰 분기 + 불변식 LOCK |
| **0111** | RiskPlan scale-in/exit 확장 (ADR 0106 amend) | scale-in 모델 + 평단 기준 stop-loss exit + 분할 익절 + ScaleInExitCalculator 공유. ADR 0106 RiskPlan 모델 확장 |

(canary cap은 ADR 0109 정합 — 본 spec §6-1에서 "코드 0 변경 + runbook 가이드"로 처리, 별 ADR 불요)

---

## 11. 후속 dispatch 권고 (orchestrator)

1. **R1 병렬** — backend-core(V65·V66 Flyway + StrategyPosition 도메인 + StrategyPositionPort) ∥ strategy-engine(ScaleInPlan·ExitPlan·PartialExit 도메인 모델). add-only 충돌 0.
2. **R2 순차** — strategy-engine(ScaleInExitCalculator + RealtimeSignalGenerator exit) → backend-core(체결 listener upsert + RiskGateway SELL 분기 + 어댑터).
3. **R3 순차** — frontend(빌더 UI + 전략별 포지션 표시) ∥ strategy-engine(리플레이·백테스트 단일 함수 공유) → qa(전 흐름 시나리오 + Visual).
4. **사용자 결재** — scale-in 회수 cap(권고 1~10), 분할 익절 단계 cap, 비중 기본 정책(균등 vs 수동).
