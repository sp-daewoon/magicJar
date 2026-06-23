# ADR 0130 — autopilot 계획 기반 진입(Planned Entry) + lot 소유권 청산

- **상태**: Accepted (사용자=최고 관리자 설계 승인 2026-06-23 — LOCKED)
- **날짜**: 2026-06-24
- **작성**: architect
- **관련**: ADR 0110(전략별 포지션 귀속 lot ledger) · ADR 0111(분할매수·분할익절 DSL) · ADR 0114(EntryPolicy 영속) · ADR 0128(분할매수 자본 재설계) · ADR 0129(autopilot 매매행위 하드닝 5 레버) · ADR 0067(AutoPauseGuard) · 메모리 `feedback_position_limit_account_wide`(계좌 전체 공유풀)
- **롤백 앵커**: v3.14.0 (ADR 0129 와 동일 베이스라인)
- **범위 게이트**: owner=AUTOPILOT 한정 · 데이터 게이트 **default OFF** · USER/SYSTEM **zero-regression**
- **자본 경로**: 최고위험 (자율 매매 청산 라우팅 구조 변경)

---

## 1. 맥락 (왜 지금)

magicjar-autopilot 첫 손실일(2026-06-23, 전종목 −29,382 · KIS matched 확정)의 근본은 **시스템 버그가 아니라 전략 행위 결함**이었다. ADR 0129 가 그 행위를 5 레버(오버나잇·blackout·추격가드·min_hold·auto-pause 거버넌스)로 하드닝했고 배포됐다. 그러나 ADR 0129 는 **개별 행위를 막는 가드의 묶음**이지, 거래 하나하나가 "어떻게 들어가서 어떻게 나올지"를 **계획으로 박제하고 그 계획을 보장 실행하는 구조**는 아니다.

### 1-1. 결정적 실증 사례 — 교차 청산 (001820)

라이브 복기에서 가장 위험한 결함이 드러났다:

> **전략 105 가 매수한 001820 을, 전략 100 의 `vwap-cross` SELL 시그널이 청산했다.**

- 전략 100 은 001820 을 **산 적이 없다**. 그런데 100 의 DSL SELL 시그널(vwap 하향 교차)이 발화하자 그 SELL 이 **계좌의 001820 holding 전체**(=105 가 산 물량)를 청산했다.
- `min_hold`(ADR 0129 레버 4)는 이를 **못 막았다** — min_hold 는 "파는 전략(100)"의 진입 시점 기준인데, 100 은 001820 을 산 적이 없으므로 hold 조건이 통과(또는 무의미)했다.
- 이는 한 전략의 의사결정이 **다른 전략의 자본**을 임의로 처분하는 구조적 사고다(롱온리 §13 청산 정합·ADR 0110 lot ledger 의도와 정면 충돌).

### 1-2. 현행 SELL 라우팅의 부분 보호와 구멍

현행 `RiskGatewayEvaluator.holdingQuantity`(consumer/risk)는 이미 부분 lot-scoping 을 한다:

```
context.strategyPosition
    ?.takeIf { code == signal.code && strategyId == signal.strategyId && quantity > 0 }
    ?.quantity
    ?: context.currentPositions.firstOrNull { code == signal.code && quantity > 0 }?.quantity   // ← 구멍
    ?: 0
```

- **정상 케이스**: 그 전략이 lot 을 보유(strategyPosition.quantity>0)하면 그 전략 보유분만 본다(교차 청산 방지 의도).
- **구멍**: 그 전략이 lot 을 **안 가진**(strategyPosition null 또는 quantity=0) 경우 → **user+code 실잔고 합산으로 fallback**. 이 fallback 은 canary D2 hotfix(#원장 0→실잔고)로 *청산 마비 방지*를 위해 들어왔지만, **owner=AUTOPILOT 다전략 환경에선 "산 적 없는 전략의 SELL 이 남의 lot 을 청산"하는 통로**가 된다(001820 사례).

→ 부분 보호는 있으나, **"산 전략만 자기 lot 을 청산한다"는 소유권 원칙이 강제되지 않는다**. 이 구멍을 닫는 것이 본 ADR L1 의 핵심이다.

### 1-3. 결정 못 막는 또 하나의 패턴 — 모멘텀 진입 ↔ 회귀 청산 휩쏘

서로 다른 논리(전략 A=모멘텀 진입, 전략 B=평균회귀 청산)가 같은 종목에서 충돌하면, A 가 산 직후 B 의 청산 시그널이 나와 즉시 손절 휩쏘가 난다. 이 역시 **거래 단위 계획**(이 거래는 이 TP/SL/hold 로 관리한다)이 없고 **소유권 없는 청산**이 허용되기 때문이다.

---

## 2. 결정 — 3층 모델 (사용자 승인 LOCKED)

거래 하나(lot)를 **L1 소유권 / L2 계획 / L3 안전망** 3층으로 관리한다. 사용자 핵심 우려("계획이 틀려도 손실이 무한정 나면 안 된다")는 **L3 항상-on 안전망**이 해소한다.

### L1 — 전략 권한 + lot 소유권 (교차 청산 폐지)

> **산 전략만 자기 lot 을 청산한다.**

- SELL 라우팅을 "종목 holding 전체"가 아니라 **`strategy_position.strategy_id` 일치 lot** 으로 resolve.
- owner=AUTOPILOT 의 경우 **§1-2 의 user+code 실잔고 fallback 을 교차 청산 통로로 쓰지 않는다**(아래 §6-1 정밀 설계).
- `min_hold`(ADR 0129 레버 4)는 **lot 진입 시점 기준**으로 평가한다(파는 전략 기준 X). 산 적 없는 전략의 SELL 은 그 종목에 lot 이 없으므로 애초 라우팅 대상이 아니다 → 교차 청산 원천 차단.

### L2 — 거래 계획 TradePlan (진입 시 박제)

> **진입(BUY) 성공 시 이 거래의 청산 계획을 데이터로 박제한다.**

`strategy_position.trade_plan` JSONB 컬럼(V95). 진입 시점에 산 전략의 thesis·목표(TP)·손절(SL)·트레일링·hold 범위·청산 조건·contingency 를 1회 기록(scale_in_config·entry_policy 영속 패턴 정합). 스키마 v1:

```jsonc
{
  "planVersion": 1,
  "entry":   { "strategyId": 105, "thesis": "vwap reclaim + vol surge",
               "entryPrice": "12340", "bar": "1m", "openedAt": "2026-06-24T01:12:00Z" },
  "target":  { "tpPct": "3.0", "tpPrice": "12710" },
  "stop":    { "type": "FIXED",      "slPct": "2.0", "slPrice": "12093" },   // type ∈ FIXED | ATR
  "trailing":{ "enabled": true, "activatePct": "1.5", "trailPct": "1.0" },
  "hold":    { "minHoldBars": 3, "maxHoldBars": 30 },
  "exitConditions": [ { "rule": "vwap_cross_down", "params": { "src": "1m" } } ],  // 데이터로 추가 (확장점)
  "contingency": { "viAction": "EXIT_MARKET", "eod": "LIQUIDATE" },  // L3 가 참조
  "status": "OPEN",       // OPEN | CLOSING | CLOSED
  "exitReason": null      // 청산 시 기록 (TP|SL|TRAIL|MAX_HOLD|EXIT_COND|VI|EOD|MANUAL|DSL)
}
```

**확장성 (LOCK)**: 새 청산 유형은 `exitConditions` 배열에 데이터를 추가하면 되고, 새 필드는 add-only(스키마 변경 0). Serde 는 `EntryPolicySerde`(Map↔도메인 순수 codec) + `ScaleInPlan`(평탄 데이터 클래스 + caller ObjectMapper) 패턴을 재사용한다 — 도메인은 Jackson import 0(헥사고날 LOCK).

### L3 — 시스템 안전망 (항상 on · planned contingency)

> **계획이 틀려도 손실은 bound 된다.**

| 안전망 | 기존 자원 | 책임 |
|--------|-----------|------|
| VI(변동성 완화장치) 시장가 청산 | `PositionViAutoExitWatcher`(consumer/order, 기존) | plan.contingency.viAction 참조해 발동 |
| EOD(장마감 전) 청산 | `AutopilotEodLiquidationWorker`(ADR 0129 레버 1, 기존) | plan.contingency.eod 참조 |
| 계좌 일일 손실 한도(DAILY_LOSS_LIMIT) | 기존 Risk chain(ADR 0024) | 계좌 단위 dll |
| 종목 하드 플로어(catastrophic floor) | `ScaleInLimits` floor + `floorAutoLiquidate`(ADR 0128) | lot 단위 하한 |

L3 는 **계획(L2)이나 소유권(L1)과 무관하게 항상 작동**한다. 계획이 비어 있거나 잘못돼도 VI·EOD·dll·floor 가 손실을 묶는다. 이것이 "데이터 게이트 OFF 일 때도 자본은 안전"한 근거다.

### 실행 모델 — PlanExecutor + 병행 DSL exit

- 신규 `PlanExecutor`(consumer)가 **열린 lot마다 매 봉(bar) plan 을 평가**해 TP/SL/trailing/maxHold/exitConditions/(contingency)를 **lot-scoped SELL 로 보장 실행**한다. 계획대로의 청산을 시스템이 책임진다(전략 DSL 이 SELL 을 안 내도 계획은 실행됨).
- 동시에 **산 전략의 DSL SELL 시그널은 자기 lot 에만** 추가 exit 으로 작동한다(병행·적응 — 전략이 시장을 더 빨리 읽으면 계획보다 먼저 나갈 수 있되, 그 lot 한정). **타 전략 lot 은 절대 건드리지 않는다**(L1).

---

## 3. 대안 비교 (왜 hybrid)

| 대안 | 내용 | 장점 | 단점 | 판정 |
|------|------|------|------|------|
| **A. plan-only** | PlanExecutor 가 모든 청산을 전담, DSL SELL 폐기 | 결정적·단순·교차 청산 원천 0 | 전략의 적응적 청산(시장 급변 시 DSL 이 더 빠름) 상실. 단타 alpha 희생 | ✗ |
| **B. strategy-only(현행 강화)** | TradePlan 없이 lot 소유권만 강제, 청산은 DSL+min_hold | 변경 최소 | "계획 보장 실행" 없음 → DSL 이 SELL 을 안 내면 TP/SL 미실행(0129 의 산발 가드 의존 지속). thesis·exitReason 가시성 0 | ✗ |
| **C. hybrid (채택)** | L1 소유권 + L2 plan 보장 실행 + L3 안전망 + DSL 병행 exit | 계획의 결정성 + 전략의 적응성 + 안전망 bound. 교차 청산 0. exitReason 추적 | 구현 복잡(PlanExecutor 신규·plan 평가 봉 단위) | **✓ 사용자 승인** |

**근거**: 단타 autopilot 은 *계획의 규율*(휩쏘·추격·오버나잇 방지)과 *전략의 적응*(급변 시 즉시 이탈) 둘 다 필요하다. A 는 적응을 버리고 B 는 규율을 못 박는다. C 만이 "계획대로 보장하되 전략이 더 잘 알면 자기 lot 한정으로 먼저 나갈 수 있고, 무엇을 해도 L3 가 손실을 묶는" 구조를 준다. 사용자가 C 를 승인했다.

---

## 4. owner 격리 & 롤아웃 (zero-regression LOCK)

- **owner=AUTOPILOT 먼저.** USER/SYSTEM 전략은 `trade_plan` 을 기록하지 않고 현행 청산 라우팅을 그대로 유지한다(byte-동일).
- **데이터 게이트 default OFF.** 게이트(`magicjar.autopilot.planned-entry.enabled` default false) 가 off 면 plan 박제·PlanExecutor·L1 소유권 강제가 모두 비활성 → **행동 변화 0**. 검증 후 사용자/orchestrator 가 on.
- **owner 판정 출처**: 신호 운반 owner(owner-on-signal #1095/#1096 — `signal.strategyOwner`) 재사용. 신규 lookup 0. legacy(null) 은 보수적으로 비-AUTOPILOT 경로(현행 fallback 유지).
- **롤백**: 게이트 off 1줄. 롤백 앵커 v3.14.0(거동 byte-동일 복귀).

---

## 5. 자원 audit (feedback_spec_audit 의무)

### 신규 (최소 원칙)

| 자원 | 종류 | 비고 |
|------|------|------|
| `V95__strategy_position_trade_plan.sql` | Flyway | 현 최신 V94 → V95. `strategy_position.trade_plan JSONB NULL` add-only(default NULL=미기록). **down SQL 동반**(`ALTER TABLE strategy_position DROP COLUMN trade_plan`). |
| `TradePlan`(+ 하위 record) | domain | 순수 데이터 클래스(헥사고날 LOCK·Jackson import 0). |
| `TradePlanSerde` | domain | Map↔TradePlan codec(`EntryPolicySerde` 패턴). caller 가 ObjectMapper 로 JSON↔Map. |
| `PlanExecutor` | consumer | lot 별 plan 평가 → lot-scoped 청산(R2). |
| `magicjar.autopilot.planned-entry.enabled` | ENV/yml | default **false**(게이트). |

### 재사용 (LOCK — 신규 생성 금지)

| 자원 | 위치 | 본 ADR 활용 |
|------|------|-------------|
| `EntryPolicySerde` | domain/strategy | TradePlanSerde 가 본뜸(Map codec 순수성). |
| `ScaleInPlan` | domain/strategy | 평탄 데이터 클래스 + JSONB 직렬화 패턴. |
| `StrategyPositionPort`(`findByUserStrategyCode`·`upsert`) | domain/position/port | plan 박제 write·lot 조회. |
| `StrategyPosition`(`trade_plan` 필드 추가) | domain/position | lot ledger + plan 박제. **ConsumerStrategy/Position 엔티티 매핑 누락 금지**(ADR 0128 scale_in drift 교훈 LOCK). |
| `RiskGatewayEvaluator.holdingQuantity`/`resolveSellQuantity` | consumer/risk | L1 소유권 강제 지점(§6-1). |
| `AutoExitReservations` | consumer/order | lot-scoped 선주문 예약 정합. |
| `PositionViAutoExitWatcher` | consumer/order | L3 VI 청산 — plan.contingency.viAction 참조(R3). |
| `AutopilotEodLiquidationWorker` | consumer/autopilot | L3 EOD 청산 — plan.contingency.eod 참조(R3). |
| `MinHoldGuard` | strategy-engine | lot 진입 시점 기준 hold(L1). |

### owner 격리·게이트
- USER zero-regression 명시. ENV/게이트 default OFF. Flyway 컬럼 default NULL(미기록=현행).

---

## 6. 정밀 설계 노트

### 6-1. L1 — owner=AUTOPILOT SELL 의 user+code fallback 차단

`holdingQuantity` 의 fallback(§1-2 구멍)은 **owner=AUTOPILOT 일 때만** 닫는다:

- owner=AUTOPILOT 이고 `strategyPosition` 가 그 종목 lot 을 안 가지면 → **holding=0** 으로 본다(=`SELL_WITHOUT_POSITION` BLOCKED). 산 적 없는 전략의 SELL 은 라우팅 대상 0 → 001820 교차 청산 원천 차단.
- USER/SYSTEM·게이트 off 는 **현행 fallback 그대로**(canary D2 청산 마비 방지 유지 — zero-regression).
- 단, owner=AUTOPILOT 의 **원장 오염**(체결 누락으로 strategyPosition.quantity=0 인데 실잔고는 있음) 케이스는 L3 안전망(VI·EOD·floor)이 여전히 청산을 보장하므로, fallback 차단으로 보유분이 영구 잠기지 않는다. + `STRATEGY_POSITION_DRIFT` SystemEvent(WARN) 로 가시화(ADR 0110 불변식).

### 6-2. L2 — plan 박제 시점·멱등

- BUY **intent 발행 성공 시점**에 산 전략이 `strategy_position.trade_plan` 을 write(체결 이전 — 진입 의사가 확정된 시점). 부분 체결/미체결이어도 plan 은 lot 사이클 동안 유효, 전량 청산(quantity→0) 시 reset(다음 사이클 새 plan).
- 분할매수(scale-in) lot 은 1차 진입 시 plan 박제, 후속 회차는 plan 유지(평단 갱신만 — TP/SL 가격은 PlanExecutor 가 평단 연동 재계산 또는 plan.target 재발행, R2 설계).

### 6-3. PlanExecutor 평가 주기·경쟁

- 매 봉 close(또는 tick failsafe — ADR 0127 패턴) 에 lot 의 plan 을 평가. TP/SL/trailing/maxHold/exitConditions 중 먼저 충족된 것이 lot-scoped MARKET/marketable SELL 발행.
- DSL SELL 시그널과 PlanExecutor 가 같은 lot 을 동시에 청산하려 할 때 → `AutoExitReservations` + "먼저 오는 쪽이 이긴다"(reservedSellQuantity) 로 중복 SELL 방지(기존 패턴 재사용).

### 6-4. ADR 0129 auto-pause 신규 룰 미제외 회귀 (같이 닫음)

ADR 0129 가 추가한 신규 RiskRule 3종(`AUTOPILOT_LATE_ENTRY_BLOCKED`·`AUTOPILOT_OPEN_BLACKOUT`·`AUTOPILOT_REENTRY_PRICE_GUARD`)이 `AutoPauseGuard.BENIGN_AUTO_PAUSE_EXCLUDED_RULES` 제외셋에 **없어서**, 이들 자기억제 가드가 BLOCKED 카운트에 기여 → 임계 도달 → 전략 자동 정지(F9·레버5-B 와 **동형 회귀**). 라이브 evidence: **2026-06-23 14:00 재활성 시 `AUTOPILOT_LATE_ENTRY_BLOCKED` 가 auto-pause 를 발동**시킴.

→ 이 3 룰을 `BENIGN_AUTO_PAUSE_EXCLUDED_RULES` 에 add-only 합류(자기억제=자본 위험 아님 → owner 무관 전역 제외, USER/SYSTEM 엔 애초 미발생하는 autopilot 전용 룰이라 격리 안전). 임계 산식 무손상(SQL 카운트 모수만 정정). backend-core 별도 task(R1 과 병행 가능).

---

## 7. 라운드 경계 (구현 plan 요약 — 상세는 spec)

| 라운드 | 책임 에이전트 | 내용 |
|--------|---------------|------|
| **R1** | strategy-engine | V95 `trade_plan` 컬럼 + 도메인 `TradePlan`+`TradePlanSerde` + ConsumerStrategy/Position 엔티티 매핑(drift 교훈 LOCK) + **진입 시 plan 박제**(BUY intent 성공 시 write) |
| **R2** | strategy-engine | `PlanExecutor`(lot별 plan 평가→lot-scoped 청산) + **SELL 라우팅 lot 소유권**(owner=AUTOPILOT fallback 차단·교차 차단) + lot 기준 min_hold |
| **R3** | frontend + backend-core | VI/EOD 가 plan.contingency 참조(backend-core) + UI "예상 계획" 패널 확장(frontend) + 통합 테스트 |
| **R-aux** | backend-core | §6-4 auto-pause 신규 룰 3종 benign 제외 회귀 fix (R1 과 병행) |

---

## 8. 검증·증명 의무

- 단위 테스트로 끝내지 않는다(메모리 `feedback_verification_strict` — "단위테스트≠라이브"). 게이트 on 후 MOCK→canary 에서 ① 교차 청산 0(산 전략만 자기 lot 청산) ② plan 박제 strategy_position.trade_plan write 확인 ③ PlanExecutor TP/SL 실행 로그 ④ exitReason 기록 ⑤ L3(VI/EOD) plan.contingency 참조 동작을 **로그·DB·Redis·화면 evidence** 로 증명.
- KIS 진실원 크로스체크(메모리 `feedback_kis_ground_truth_crosscheck`): lot 청산 후 KIS 잔고·실현손익과 strategy_position 대조.

## 9. 롤백

게이트 `magicjar.autopilot.planned-entry.enabled=false` 1줄 → plan 박제·PlanExecutor·L1 소유권 강제 전부 비활성 = v3.14.0 거동 byte-동일. V95 컬럼은 NULL 잔존(무해) — 필요 시 down SQL.
