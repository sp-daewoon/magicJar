# spec — autopilot 계획 기반 진입(Planned Entry) + lot 소유권 청산

- **권위 ADR**: [ADR 0130](../arch/adr/0130-autopilot-planned-entry.md)
- **운영 권위 정책**: [autopilot-trade-lifecycle.md](../../docs/architecture/policies/autopilot-trade-lifecycle.md)
- **날짜**: 2026-06-24 · **작성**: architect
- **범위 게이트**: owner=AUTOPILOT · 데이터 게이트 default OFF · USER/SYSTEM zero-regression
- **롤백 앵커**: v3.14.0

---

## 1. 목표 (한 줄)

자율 매매 거래 하나(lot)를 **L1 소유권 / L2 TradePlan 박제+보장 실행 / L3 항상-on 안전망** 3층으로 관리해, 교차 청산(001820 사례)을 폐지하고 계획이 틀려도 손실을 bound 한다.

## 2. 비목표 (LOCK)

- USER/SYSTEM 전략 거동 변경 금지(zero-regression).
- 새 DSL 연산자 도입 금지(exitConditions 는 기존 indicator/rule 참조 데이터일 뿐).
- 게이트 off 상태에서 행동 변화 0.
- 신규 KIS endpoint 0(기존 OrderIntent·VI·EOD 청산 경로 재사용).

---

## 3. TradePlan 스키마 v1 (도메인 데이터 — 헥사고날 LOCK)

`strategy_position.trade_plan` JSONB(V95). 도메인 `TradePlan` 은 Jackson import 0, 평탄 데이터 클래스(`ScaleInPlan` 패턴). caller 어댑터가 ObjectMapper 로 JSON↔Map, `TradePlanSerde` 가 Map↔도메인(`EntryPolicySerde` 패턴).

```jsonc
{
  "planVersion": 1,                         // 스키마 버전 (add-only 확장 시 그대로 유지)
  "entry": {
    "strategyId": 105,                      // 이 lot 을 산 전략 (소유권 키)
    "thesis": "vwap reclaim + vol surge",   // 진입 근거 텍스트(가시성·UI)
    "entryPrice": "12340",                  // 1차 진입가(BigDecimal as string)
    "bar": "1m",                            // 평가 봉 단위
    "openedAt": "2026-06-24T01:12:00Z"
  },
  "target":  { "tpPct": "3.0", "tpPrice": "12710" },          // 익절 목표(둘 중 하나 또는 양쪽)
  "stop":    { "type": "FIXED", "slPct": "2.0", "slPrice": "12093" },  // type ∈ FIXED | ATR
  "trailing":{ "enabled": true, "activatePct": "1.5", "trailPct": "1.0" },
  "hold":    { "minHoldBars": 3, "maxHoldBars": 30 },
  "exitConditions": [                        // 추가 청산 조건 (확장점 — 데이터로 add)
    { "rule": "vwap_cross_down", "params": { "src": "1m" } }
  ],
  "contingency": { "viAction": "EXIT_MARKET", "eod": "LIQUIDATE" },  // L3 가 참조
  "status": "OPEN",                          // OPEN | CLOSING | CLOSED
  "exitReason": null                         // TP|SL|TRAIL|MAX_HOLD|EXIT_COND|VI|EOD|MANUAL|DSL
}
```

### 3-1. 필드 제약·검증
- `planVersion` ≥ 1. 미래 스키마 변경은 **필드 add-only**(version bump 없이도 하위호환). 새 청산 유형 = `exitConditions` 배열 항목 추가.
- `entry.strategyId` 필수 = 소유권 키. lot 청산 라우팅이 이 값과 signal.strategyId 를 비교.
- `target`·`stop` 둘 다 optional 가능(둘 다 비면 L3 안전망만 적용). `trailing.enabled=false` 면 trail 무시.
- 모든 BigDecimal 은 string 직렬화(jackson number/string 양측 파싱 — `EntryPolicySerde.bigDecimalOrNull` 패턴).
- `status`·`exitReason` 는 PlanExecutor/청산 listener 가 갱신.

### 3-2. 박제(write) 시점·멱등
- BUY **intent 발행 성공 시점**에 산 전략이 `StrategyPositionPort.upsert` 로 trade_plan write(체결 이전).
- 분할매수 lot: 1차 진입 시 박제, 후속 회차는 평단 갱신만(plan.target/stop 은 PlanExecutor 가 평단 연동 재계산 또는 재발행 — R2 결정).
- 전량 청산(quantity→0) 시 trade_plan reset(다음 사이클 새 plan — `StrategyPosition` 전량 청산 reset 규약 정합).

---

## 4. 인터페이스 (R1·R2)

### 4-1. domain — TradePlan + Serde (R1)

```kotlin
// domain/strategy 또는 domain/position — 순수, Jackson import 0
data class TradePlan(
    val planVersion: Int = 1,
    val entry: TradePlanEntry,
    val target: TradePlanTarget? = null,
    val stop: TradePlanStop? = null,
    val trailing: TradePlanTrailing? = null,
    val hold: TradePlanHold? = null,
    val exitConditions: List<TradePlanExitCondition> = emptyList(),
    val contingency: TradePlanContingency? = null,
    val status: TradePlanStatus = TradePlanStatus.OPEN,
    val exitReason: String? = null,
) { /* init: planVersion>=1, 가격/비율 범위 검증 */ }

object TradePlanSerde {           // EntryPolicySerde 패턴 — Map<->TradePlan, 순수
    fun toMap(plan: TradePlan): Map<String, Any?>
    fun fromMap(map: Map<String, Any?>?): TradePlan?   // null/빈 Map -> null(미기록)
}
```

`StrategyPosition` 에 `tradePlan: TradePlan? = null` 필드 add-only(전량 청산 reset 대상에 합류).

### 4-2. SELL 라우팅 lot 소유권 — RiskGatewayEvaluator (R2)

`holdingQuantity` 의 user+code fallback 을 **owner=AUTOPILOT 한정 차단**:

```
holding =
  strategyPosition?.takeIf { code & strategyId 일치 & quantity>0 }?.quantity
  ?: if (ownerIsAutopilot && gateEnabled) 0           // ← 신규: 산 적 없는 autopilot 전략 SELL = holding 0
     else currentPositions.firstOrNull { code 일치 & quantity>0 }?.quantity   // 현행 fallback(USER/게이트 off)
  ?: 0
```

- ownerIsAutopilot 출처 = `signal.strategyOwner`(owner-on-signal 재사용, 신규 lookup 0). legacy null → 비-AUTOPILOT.
- 게이트 off → 현행 fallback 100% 유지(zero-regression).
- 결과: 산 전략만 자기 lot 청산. 교차 청산 원천 차단.

### 4-3. PlanExecutor (R2)

```kotlin
@Component
class PlanExecutor(
    private val strategyPositionPort: ObjectProvider<StrategyPositionPort>,
    // 가격/봉 입력, OrderIntent 발행, AutoExitReservations, gate
) {
    // 매 봉 close(또는 tick failsafe — ADR 0127 패턴)에 열린 lot 의 plan 평가.
    // 우선순위: SL > MAX_HOLD > exitConditions > TP > TRAIL (자본 보호 우선) — R2 확정.
    // 충족 시 lot-scoped MARKET/marketable SELL 발행 + plan.status=CLOSING + exitReason 기록.
    // 중복 방지: AutoExitReservations + reservedSellQuantity("먼저 오는 쪽이 이긴다") 재사용.
    fun evaluateOpenLots(/* bar/tick ctx */)
}
```

- **lot-scoped only**: 발행 SELL 은 그 lot 의 strategyId 귀속. 타 전략 lot 미접근(L1).
- **DSL 병행**: 산 전략 DSL SELL 은 자기 lot 한정 추가 exit(기존 경로 유지). PlanExecutor 와 같은 lot 동시 청산은 예약 dedup.
- **게이트 off → no-op**.

### 4-4. min_hold lot 기준 (R2)
`MinHoldGuard` 평가를 lot(strategyPosition) 진입 시점 기준으로 적용. 산 적 없는 전략은 그 종목 lot 미보유 → 라우팅 대상 0(min_hold 우회 사례 원천 차단).

---

## 5. L3 안전망 — plan.contingency 참조 (R3, backend-core)

| 안전망 | 기존 컴포넌트 | 변경 |
|--------|--------------|------|
| VI 청산 | `PositionViAutoExitWatcher` | lot 의 plan.contingency.viAction(EXIT_MARKET/ALERT_ONLY/HOLD) 우선 참조(없으면 현행 ViActionPolicy) |
| EOD 청산 | `AutopilotEodLiquidationWorker` | plan.contingency.eod(LIQUIDATE/HOLD) 참조(없으면 현행 allow_overnight 토글) |
| 계좌 dll | 기존 Risk chain | 변경 0(계좌 단위 유지) |
| 종목 floor | `ScaleInLimits` floor + `floorAutoLiquidate` | 변경 0(ADR 0128 유지) |

**원칙**: plan 이 없거나 게이트 off 면 **현행 동작 100% 유지**. plan.contingency 는 lot 단위 override(add-only).

---

## 6. 라운드 경계 (단일 책임·API 한 스텝 — development-rules §2·§3)

| 라운드 | 에이전트 | 산출(영역) | 테스트 |
|--------|----------|-----------|--------|
| **R1** | strategy-engine | V95 컬럼+down · domain TradePlan/Serde · StrategyPosition 필드 · ConsumerStrategy/Position 엔티티 매핑(drift LOCK) · 진입 시 plan 박제(BUY intent 성공 write) | R1 테스트 최소(Serde 라운드트립·엔티티 매핑·박제 write) — development-rules §4 "R1 코드 먼저" 정합하되 자본 경로라 핵심 단위는 동반 |
| **R2** | strategy-engine | PlanExecutor(lot 평가→lot-scoped 청산) · SELL 라우팅 owner=AUTOPILOT fallback 차단(교차 차단) · min_hold lot 기준 | PlanExecutor TP/SL/trail/maxHold/exitCond 발화·lot 격리(타 전략 lot 미접근)·게이트 off no-op·교차 청산 0·USER zero-regression |
| **R3** | frontend + backend-core | (BE) VI/EOD plan.contingency 참조 · (FE) "예상 계획" 패널 확장(thesis·TP/SL/trail/hold·exitReason) · 통합 테스트(데이터 흐름 시나리오 일괄) | E2E 진입→박제→PlanExecutor 청산→exitReason 표시 |
| **R-aux** | backend-core | ADR 0129 신규 룰 3종(LATE_ENTRY/OPEN_BLACKOUT/REENTRY_PRICE_GUARD) benign 제외 회귀 fix | benign 합류·자기억제 정지 0·USER 격리 회귀 0 |

각 라운드 = 한 PR(또는 영역 분리 시 사유 명기). `--base main` · rebase 머지 · 배포는 orchestrator(장마감 후).

---

## 7. 게이트·ENV

| 키 | default | 효과 |
|----|---------|------|
| `magicjar.autopilot.planned-entry.enabled` | **false** | off → plan 박제·PlanExecutor·L1 fallback 차단 전부 비활성(행동 변화 0) |

ENV: `MAGICJAR_AUTOPILOT_PLANNED_ENTRY_ENABLED`(yml binding). 검증 후 사용자/orchestrator 가 on.

## 8. 검증 의무 (feedback_verification_strict · kis_ground_truth_crosscheck)

게이트 on 후 MOCK→canary evidence:
1. 교차 청산 0 — 산 전략만 자기 lot 청산(001820 회귀 재현 시도 → BLOCKED 확인).
2. plan 박제 — strategy_position.trade_plan write 로그·DB row.
3. PlanExecutor — TP/SL/trail/maxHold/exitCond 발화 로그 + lot-scoped SELL 발행.
4. exitReason — 청산 후 plan.status=CLOSED·exitReason 기록.
5. L3 — VI/EOD 가 plan.contingency 참조 동작.
6. KIS 대조 — lot 청산 후 KIS 잔고·실현손익 vs strategy_position drift 0.
