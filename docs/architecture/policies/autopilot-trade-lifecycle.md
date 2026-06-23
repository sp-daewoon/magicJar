# autopilot 거래 생애주기 + 3층 책임 (계획 기반 진입)

> **현행 권위 정책** — autopilot(자율 매매) 거래 하나(lot)가 어떻게 진입·관리·청산되는가의 단일 출처.
> ref: ADR 0130(계획 기반 진입) · ADR 0110(lot ledger) · ADR 0111/0128(분할매수) · ADR 0129(매매행위 하드닝) · ADR 0067(AutoPauseGuard) · long-only-trading.md(롱온리).
> 결정 이력은 ADR. 본 문서가 "지금 무엇이 권위인가"의 본문.

---

## 1. 한 줄 원칙

> **산 전략만 자기 lot 을 청산한다. 진입할 때 청산 계획을 박제하고, 시스템이 그 계획을 보장 실행하며, 계획이 틀려도 항상-on 안전망이 손실을 묶는다.**

---

## 2. 거래 생애주기 (진입 → 박제 → 관리 → 청산)

```
[1. 진입 BUY]  전략 DSL BUY 시그널 → Risk chain ALLOW → OrderIntent 발행 성공
       │
       ▼
[2. 계획 박제]  산 전략이 strategy_position.trade_plan(JSONB) write
       │        (thesis·TP·SL·trailing·hold·exitConditions·contingency)
       ▼
[3. PlanExecutor 관리]  매 봉 close/tick 마다 열린 lot 의 plan 평가
       │        ├─ SL·MAX_HOLD·exitConditions·TP·TRAIL 중 충족 → lot-scoped SELL(보장 실행)
       │        └─ 산 전략 DSL SELL → 자기 lot 한정 추가 exit(병행·적응)
       ▼
[4. 청산]  lot-scoped SELL 체결 → plan.status=CLOSED · exitReason 기록
            quantity→0 시 trade_plan reset(다음 사이클 새 plan)
       │
       └─[항상-on L3]  VI·EOD·계좌 dll·종목 floor 가 단계 무관하게 손실 bound
```

---

## 3. 3층 책임 표

| 층 | 이름 | 책임 | 핵심 컴포넌트 | 게이트 |
|----|------|------|---------------|--------|
| **L1** | 전략 권한 + lot 소유권 | SELL 을 `strategy_position.strategy_id` 일치 lot 으로 라우팅. 교차 청산 폐지. min_hold 는 lot 진입 시점 기준 | `RiskGatewayEvaluator.holdingQuantity`/`resolveSellQuantity`(consumer/risk) · `MinHoldGuard`(strategy-engine) | owner=AUTOPILOT + planned-entry.enabled |
| **L2** | 거래 계획 TradePlan | 진입 시 청산 계획 박제 + PlanExecutor 가 보장 실행 + exitReason 추적 | `TradePlan`/`TradePlanSerde`(domain) · `strategy_position.trade_plan`(V95) · `PlanExecutor`(consumer) | owner=AUTOPILOT + planned-entry.enabled |
| **L3** | 시스템 안전망 | 계획·소유권과 무관하게 항상 손실 bound | `PositionViAutoExitWatcher`(VI) · `AutopilotEodLiquidationWorker`(EOD) · Risk chain(계좌 dll) · `ScaleInLimits` floor | **항상 on** (게이트 무관) |

> **핵심**: L1·L2 는 게이트로 켜고 끄지만, **L3 는 항상 작동**한다. 게이트가 off 이거나 plan 이 비어 있어도 VI·EOD·dll·floor 가 손실을 묶는다 — "데이터 게이트 OFF 일 때도 자본은 안전"의 근거.

---

## 4. 교차 청산 금지 원칙 (LOCK)

> **한 전략의 SELL 의사결정이 다른 전략의 자본을 처분할 수 없다.**

- 실증 사고(2026-06-23): 전략 105 가 산 001820 을 전략 100 의 `vwap-cross` SELL 이 청산. 100 은 001820 을 산 적이 없었다. min_hold 도 못 막음(파는 전략 기준이라 무의미).
- **규칙**:
  - SELL 라우팅은 `strategy_position` 에서 `(userId, strategyId, code)` 일치 + `quantity>0` lot 만 대상.
  - owner=AUTOPILOT 이고 그 전략이 그 종목 lot 을 안 가지면 → holding=0(=`SELL_WITHOUT_POSITION` BLOCKED). user+code 실잔고 fallback 을 **교차 청산 통로로 쓰지 않는다**.
  - USER/SYSTEM·게이트 off 는 현행 fallback 유지(canary D2 청산 마비 방지 — zero-regression).
- **원장 오염 안전망**: autopilot lot 이 체결 누락으로 quantity=0 이면 보유분이 잠길 수 있으나, L3(VI·EOD·floor)가 청산을 보장 + `STRATEGY_POSITION_DRIFT` WARN 으로 가시화(영구 잠금 방지).

---

## 5. TradePlan 스키마 + 확장법

`strategy_position.trade_plan` JSONB(V95). 도메인 `TradePlan` 은 **순수**(Jackson import 0 — 헥사고날 LOCK). caller 어댑터가 ObjectMapper 로 JSON↔Map, `TradePlanSerde` 가 Map↔도메인(`EntryPolicySerde` 패턴).

```jsonc
{
  "planVersion": 1,
  "entry":   { "strategyId": 105, "thesis": "...", "entryPrice": "12340", "bar": "1m", "openedAt": "..." },
  "target":  { "tpPct": "3.0", "tpPrice": "12710" },
  "stop":    { "type": "FIXED", "slPct": "2.0", "slPrice": "12093" },
  "trailing":{ "enabled": true, "activatePct": "1.5", "trailPct": "1.0" },
  "hold":    { "minHoldBars": 3, "maxHoldBars": 30 },
  "exitConditions": [ { "rule": "vwap_cross_down", "params": { "src": "1m" } } ],
  "contingency": { "viAction": "EXIT_MARKET", "eod": "LIQUIDATE" },
  "status": "OPEN",
  "exitReason": null
}
```

### 확장법 (스키마 변경 0)
- **새 청산 유형 추가** → `exitConditions` 배열에 `{rule, params}` 항목 데이터로 추가. PlanExecutor 가 rule 디스패치.
- **새 필드 추가** → add-only(`planVersion` 그대로, 하위호환). 구 plan 은 새 필드 null 로 안전 폴백.
- BigDecimal 은 string 직렬화(number/string 양측 파싱). 모든 필드 optional 은 null=현행 default 폴백.

---

## 6. L3 안전망 목록 (항상 on)

| 안전망 | 발동 | plan.contingency 참조 | 컴포넌트 |
|--------|------|----------------------|----------|
| VI 시장가 청산 | 변동성 완화장치(VI) 활성 이벤트(H0UNMKO0) | `viAction` ∈ EXIT_MARKET/ALERT_ONLY/HOLD (없으면 현행 ViActionPolicy) | `PositionViAutoExitWatcher` |
| EOD 청산 | 장마감 전 cutoff(default 15:15 KST) | `eod` ∈ LIQUIDATE/HOLD (없으면 allow_overnight 토글) | `AutopilotEodLiquidationWorker` |
| 계좌 일일 손실 한도 | DAILY_LOSS_LIMIT 도달 | (계좌 단위 — plan 무관) | Risk chain(ADR 0024) |
| 종목 하드 플로어 | 평단 대비 catastrophic floor 도달 | (lot 단위 — ADR 0128 `floorAutoLiquidate`) | `ScaleInLimits` floor |

> plan 이 없거나 게이트 off 면 각 안전망은 **현행 동작 100% 유지**. plan.contingency 는 lot 단위 override(add-only).

---

## 7. owner 격리 & 게이트 (zero-regression LOCK)

- **owner=AUTOPILOT 한정**: USER/SYSTEM 전략은 trade_plan 미기록 + 현행 청산 라우팅 유지(byte-동일).
- **게이트**: `magicjar.autopilot.planned-entry.enabled`(default **false**). off → plan 박제·PlanExecutor·L1 fallback 차단 전부 비활성 = 행동 변화 0.
- **owner 판정**: 신호 운반 owner(`signal.strategyOwner` — owner-on-signal). legacy null → 비-AUTOPILOT(보수적).
- **롤백**: 게이트 off 1줄 = v3.14.0 거동 byte-동일.

---

## 8. 운영 체크 (게이트 on 시 evidence 의무)

`feedback_verification_strict`·`feedback_kis_ground_truth_crosscheck`:
1. 교차 청산 0(산 전략만 자기 lot 청산) — 로그·BLOCKED 확인.
2. plan 박제 — strategy_position.trade_plan DB row.
3. PlanExecutor TP/SL/trail/maxHold/exitCond 발화 로그 + lot-scoped SELL.
4. exitReason 기록(plan.status=CLOSED).
5. L3 VI/EOD plan.contingency 참조 동작.
6. KIS 대조 — lot 청산 후 KIS 잔고·실현손익 vs strategy_position drift 0.

---

## 9. 변경 이력

| 날짜 | 변경 | ADR |
|------|------|-----|
| 2026-06-24 | 최초 작성 — 3층 모델(L1 소유권/L2 TradePlan/L3 안전망)·교차 청산 금지·TradePlan 스키마 v1·owner 격리·게이트 default OFF | ADR 0130 |
