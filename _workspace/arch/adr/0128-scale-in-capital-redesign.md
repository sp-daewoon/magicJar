# ADR 0128 — 분할매수(scale-in) 자본 로직 재설계 — base 고정·잔여추적·floor 알람강등·한도/0% 버그 fix

- 상태: **Accepted (2026-06-19)** — strategy-engine R1 로직(domain 필드·순수 함수·consumer 사이징·알람 강등) + V90 Flyway 컬럼. **라이브 자본 critical** — 단위테스트 GREEN 후 배포는 orchestrator가 사용자 승인 후.
- 작성자: strategy-engine
- 결정자: 사용자(최고 관리자) — 2026-06-19 canary 후성(093370) 라이브 케이스에서 도출한 9개 설계 결재(메모리 `project_scalein_strategy_design.md` 권위).
- 영역: 자동매매 진입(scale-in) 자본 사이징 + 청산 보류/알람 — **자본 path (사용자 결재)**
- 입력 권위:
  - ① 사용자 결재 9건 설계(메모리 `project_scalein_strategy_design.md`) — base 고정·잔여추적·익절가 평단연동·회차진입 트리거·분할 미완 손절 보류·분할 완료 손절·floor 알람만·totalSlices 초과 버그·weight 0 엣지.
  - ② 라이브 실증 — 전략47(qa_scalein, ma3/ma8 whipsaw) 4회차 sliceIndex=3 초과에도 마지막 가중으로 계속 매수(⑧), 후성 분할 1/3 손절선 도달 청산(⑤⑥⑦ 도출).
  - ③ 현행 구현 — `RiskGatewayEvaluator.resolveBuyQuantity`·`ScaleInExitCalculator`·`StrategyPosition`·`ScaleInPlan`.
  - ④ 메모리 `feedback_position_limit_account_wide`(계좌 전체 공유 풀 사이징) — base 의 상위 산식 정합.
- 관계:
  - **ADR 0111 amend** — 0111이 scale-in/partial-exit 도메인·순수 함수를 신설했다. 본 ADR은 그 위에 ① base 고정·② 잔여추적·⑦ floor 알람강등·⑧⑨ 버그 fix 를 보강한다. 우선순위 LOCK(STOP_LOSS>PARTIAL_TP>TAKE_PROFIT>ENTER_SLICE>HOLD)·순수성 LOCK 불변.
  - **PR #1057 정합** — drift reconcile 깎기 방지(분할 N회차 체결 직후 stale 양수 잔고 깎기 방지)와 함께 분할 라이브가 drift 0으로 완주한 토대.
  - **PR #1058 강등** — #1058은 분할 미완 손절 보류 + catastrophic floor에서 **자동 청산**(StopLoss)을 배포했다. 본 ADR ⑦로 floor를 **알람만**(FloorBreachAlert)으로 강등한다(자동 청산 X).
  - **메모리 `feedback_position_limit_account_wide`** — base = 1차 시점 buyable(계좌 전체 공유 풀 사이징 결과)을 박제. base 외부주입 추상화로 미래 "지정 가격 + 목표 금액" 매집 트랙(메모리 §미래 트랙)과 호환.
- README 인덱스: §0 총건수·최근10건 prepend·§6 헤더 건수·§14 row 동시 갱신(ADR 0030 SoT 정합).

---

## 1. 배경 — 분할매수 자본 로직의 5개 결함

분할매수는 "하락 시 물타기로 평단을 낮추는" 스윙 전략이다. 2026-06-19 canary 후성 라이브에서 5개 결함이 드러났다.

1. **base 재계산으로 회차 비중 왜곡(①)** — `resolveBuyQuantity`가 회차마다 `buyable = min(남은한도, 예수금)`을 **재계산**한다. 1차 매수가 전체노출을 늘려 남은 한도가 줄면 2·3차 회차 예산이 의도보다 작아진다. 예: 1차 시점 20만 base로 [40,35,25]를 의도해도, 1차 8만 매수 후 buyable이 12만으로 줄면 2차는 12만×35%=4.2만으로 쪼그라든다.
2. **부분 매수 시 잔여 망각(②)** — 회차 예산이 살 수 있는 수량보다 자금이 부족하면 "살 수 있는 만큼만" 사고 못 산 잔여를 잊었다.
3. **floor 자동 청산(⑦)** — #1058이 분할 미완 catastrophic floor(평단 -15%)에서 **자동 청산**했다. 스윙 매매라 변동성이 커서 floor에서 기계적 청산하면 반등을 놓치고 큰 손실만 확정한다.
4. **totalSlices 한도 초과 진입(⑧)** — `weights.getOrElse(filledLots){weights.last()}`가 `filledLots >= totalSlices`에도 마지막 가중으로 계속 매수했다(전략47 4회차 sliceIndex=3 초과 실증).
5. **weight 0 엣지(⑨)** — `[50,50,0]`은 도메인 검증(`weights.all{it>0}`)에서 거부됐고, 허용하더라도 0% 회차가 filledLots를 totalSlices에 못 닿게 해 분할 완료 판정 불가(손절 영구 보류) 위험이 있었다.

---

## 2. 결정 — 9개 설계 (사용자 결재값)

| # | 설계 | 구현 |
|---|------|------|
| ① | **base = 1차 진입 시점 가용액 고정** (외부주입 추상화) | `StrategyPosition.scaleInBaseBudget` 박제 → `resolveBuyQuantity`가 박제 base × 회차 비중. 박제 부재(1차)면 라이브 buyable이 base 후보. |
| ② | **부분 매수 + 잔여 추적** | `StrategyPosition.scaleInRemainderQty` → 다음 회차 수량에 잔여 더함(buyable로 clamp). |
| ③ | **익절가 = 매 체결 평단 따라 재발행** | 이미 작동(application.yml exit + 평단변동 재발행). 정합만 — 본 ADR 코드 변경 0. |
| ④ | **회차 진입 = 시그널/트리거 도달 시** | `ScaleInExitCalculator.resolveScaleInSlice`(SIGNAL_REPEAT / PRICE_DROP_PCT) — 기구현. |
| ⑤ | **분할 미완(filledLots<effectiveSliceCount) 손절 일절 안 함** | `stopLossVerdict` → DEFER(보류). |
| ⑥ | **분할 완료 후 평단 -손절% 손절** | `stopLossVerdict` → FIRE(StopLoss). |
| ⑦ | **floor = 전략 설정값 + 알람만(자동 청산 X)** | floor StopLoss → **FloorBreachAlert**(SELL 미발행). floor<분할최저진입가 저장검증. |
| ⑧ | **totalSlices 한도 초과 진입 0** | `ScaleInPlan.sliceAt(filledLots)` null → 진입 0(consumer·engine 양쪽). |
| ⑨ | **weight 0 엣지 skip** | `sliceWeights >= 0` 허용, `effectiveSliceCount`/`sliceAt`가 0% 회차 skip → `[50,50,0]`=`[50,50]`. |

### 2-1. base 고정 — 외부주입 추상화 (① + 미래 트랙)

base는 "1차 시점 buyable"의 박제값이지만, 의미는 **"분할 전체에 쓸 종목 예산"**으로 추상화돼 있다. 미래 "지정 가격 + 목표 금액" 매집 트랙에선 base = 사용자 지정 목표 금액으로 채워 넣으면 **회차 산식 변경 0**으로 호환된다. 회차 산식은 백테스트(`JvmBacktestEngine.evaluateScaleInExitSlice`)와 동일: `회차예산 = floor(고정 base × weight%/100)` → `수량 = floor(회차예산 / price)`.

### 2-2. floor 알람강등 — worst-case 손실 bound (⑦)

분할 미완 손절 보류가 **무한**이 되면 교착(분할도 안 되고 손절도 안 됨)이 생긴다. 단일 평가에서 "EnterSlice 영영 불가능"은 결정론적으로 증명 불가(미래 BUY/가격 미지)하므로 **직접 감지하지 않는다**. 대신 보류에 손실폭 상한(floor)을 둬 모든 경로의 손실을 bound한다 — 단 floor 도달 시 **자동 청산이 아니라 Slack 알람만** 발행하고 청산은 사용자 수동 판단에 맡긴다. 결과: "분할 미완 + 시그널無 + 폭락" 시 자동 손절 0(알람만) = **의도된 수동 리스크 관리**. floor ≤0이면 알람도 없는 순수 보류.

**floor < 분할 최저 진입가 저장검증** — floor가 분할 최저 진입가보다 위(얕)면 마지막 회차 진입 전에 floor 알람이 떠 분할의 의미가 깨진다. PRICE_DROP_PCT 플랜은 누적 하락 `(1-(1+drop/100)^(N-1))×100` 보다 floor가 깊어야(`ScaleInLimits.validateFloorBelowDeepestEntry`) 저장 허용(api 저장 경로 400 거부). SIGNAL_REPEAT은 가격 조건 없어 항상 통과.

### 2-3. 분할 완료 판정 = effectiveSliceCount (⑨ 정합)

`filledLots`는 양수 비중 회차만 증가하므로, 분할 완료·진입 가부 판정은 `totalSlices`가 아니라 **양수 비중 회차 수**(`effectiveSliceCount`)로 한다. `[50,50,0]`은 filledLots가 totalSlices(3)에 영영 못 닿아 손절이 영구 보류되던 위험을 제거한다.

---

## 3. 자원 audit (Flyway·ENV·#1057/#1058 정합)

- **Flyway**: `V90__strategy_position_scale_in_base_remainder.sql` — `scale_in_base_budget`(NUMERIC nullable) + `scale_in_remainder_qty`(INTEGER default 0). V89가 최신(충돌 0, 형제 브랜치 V90 미점유 확인). floor 설정값은 `strategy.scale_in_config` JSONB(`stopLossFloorPct`)에 직렬화 — DDL 불필요.
- **ENV**: 신규 ENV 0. 기존 `MAGICJAR_STRATEGY_SCALE_IN_ENABLED`(토글) 불변.
- **#1057 정합**: drift reconcile 깎기 방지와 충돌 0 — 본 ADR은 사이징/청산 판정만 변경, reconcile 경로 무관.
- **#1058 정합**: floor 자동 청산 → 알람 강등이 #1058의 유일한 방향 변경. 손절 보류(⑤)·분할 완료 손절(⑥)은 #1058 그대로 계승.

---

## 4. 라운드/책임 경계

- **R1 (본 PR — strategy-engine)**: domain 필드(`ScaleInPlan.stopLossFloorPct`·`StrategyPosition.scaleInBaseBudget`·`scaleInRemainderQty`)·순수 함수(`ScaleInExitCalculator` floor 강등·sliceAt·effectiveSliceCount)·consumer 사이징(`resolveBuyQuantity` base 고정·잔여·⑧⑨)·`RealtimeSignalGenerator` 알람 emit·V90 컬럼·projector DTO·단위테스트.
- **R2 (backend-core 후속)**: 체결 listener가 **base 박제**(1차 체결 시 그 시점 buyable 저장)·**잔여 갱신**(부분 체결 시 미체결분 기록)·**전량 청산 reset**(quantity=0 시 base·remainder reset)·JPA 엔티티 컬럼 매핑. R2 전까지 `scaleInBaseBudget`=null → 라이브 buyable fallback(현행 동작 = zero-regression). 즉 **① ②는 R2 wiring 후 활성**, ⑦⑧⑨는 본 PR 즉시 활성(filledLots·scaleIn config만 의존).
- **R3 (frontend 후속)**: `ScaleInPlanProjector` 결과(0% 회차 제외 계약)를 전략 카드·보유 현황에 노출.

---

## 5. 검증

단위테스트(배포 X): base고정·잔여추적·분할미완 손절0·분할완료 손절·floor 알람만·floor<분할최저·⑧초과진입0·⑨weight0 skip — `ScaleInExitValidationTest`·`ScaleInExitCalculatorTest`·`ScaleInPlanProjectorTest`·`RiskGatewayEvaluatorTest`. `:domain:test :strategy-engine:test :consumer:test ktlintCheck` GREEN.
