# ADR 0132 — 초단타 정합 백테스트(Intraday-Realistic Backtest) + 분봉 on-demand backfill + 캔들 정합성 가드

- 상태: **Accepted** (2026-06-24)
- 작성: architect
- 관련: ADR 0056(백테스트 엔진)·0114(진입 정책 백테스트 정합)·0121(사이징 정합)·0117(과최적화)·0105(캔들 reconciliation/readiness)·0118·0119(1m on-demand)·0124(H0UNCNT0)·0127(TP tick)·0128(분할매수)·0129(매매행위 하드닝)·0130(Planned Entry + TradePlanEvaluator)·0037/0100/0116(KIS rate limit priority bucket)
- spec: `_workspace/specs/2026-06-24-intraday-realistic-backtest.md`
- 롤백 앵커: v3.14.x (게이트 default OFF — 신규 백테스트 모드는 opt-in)
- 코드 0 (본 ADR는 설계만 · 구현은 spec/plan의 라운드별 task)

---

## 0. 한 줄 요약

> autopilot 라이브가 TP(+2~3%)/SL(−2%)/EOD청산(15:15)/개장 blackout(09:00~09:15)/min_hold로 **초단타**를 굴리는데, `/api/backtest/run` 백테스트는 같은 전략을 **DSL sell(close<vwap)** 로만 청산해 **12일 스윙 보유(−9.4%)** 를 시뮬한다. 백테스트가 라이브와 **다른 매매를 검증**하므로 검증이 무의미하다. 본 ADR은 **(기둥1) 라이브와 동일한 `TradePlanEvaluator` 청산 두뇌를 백테스트에 재사용**해 parity를 보장하고, **(기둥2) 분봉 부재 시 KIS에서 on-demand backfill**(최저 `BACKFILL` priority·KIS 1년 lookback bound 내), **(기둥3) backfill 후 정합성 가드**(기대 봉수 대비 결측률 측정·불완전 시 `LOW_CONFIDENCE`/`INSUFFICIENT_DATA` 표기·mock 절대 금지)를 결정한다. 이것은 **전략 ACTIVE 승격 전 검증 게이트(후속 ADR)의 선결 인프라**다.

---

## 1. 배경 — 라이브 실증(2026-06-24 orchestrator)

### 1-A. 결함 evidence

autopilot 6전략을 `/api/backtest/run`으로 백테스트 → **전부 손실(승률 0)**. 거래 상세를 까보니:

| 축 | 라이브(planned-entry ON) | 백테스트(현행 `/api/backtest/run`) |
|---|---|---|
| 진입 | DSL entry(COMPOSITE 등) | DSL entry(동일) ✅ |
| **청산** | **TP(+2~3%)·SL(−2%)·EOD(15:15)·MAX_HOLD·trail** (`TradePlanEvaluator`) | **DSL sell만**(close<vwap) → **12일 스윙 보유 −9.4%** ❌ |
| min_hold | autopilot 시드 3봉 (lot 기준) | 없음 ❌ |
| 개장 blackout | 09:00~09:15 BUY 억제 | 없음 ❌ |
| EOD | allow_overnight=false → 15:15 강제 청산 | 없음 ❌ |
| intrabar | (라이브 tick) | 봉 close만 평가 — SL 우선 무시 ❌ |

근본: **백테스트는 라이브 청산 로직(`TradePlanEvaluator` + EOD/blackout 워커)을 하나도 통과하지 않는다.** scale-in/exit 활성 전략만 `ScaleInExitSimulation`을 타서 TP/SL가 시뮬되고, 그 외 전략(autopilot 6전략 포함·scale-in 비활성 + sellCondition 보유)은 `JvmBacktestEngine`의 **DSL sell 경로**로 빠져 스윙이 된다.

### 1-B. 사용자(최고 관리자) 지시

> "초단타 룰로 백테스트 다시 짜라. 분봉 없으면 백테스트하며 수집. 캔들 정합성 안 맞는 경량 시스템 현실 고려해 계획부터."

### 1-C. 왜 지금 critical 한가 (방향성 가드)

전략 ACTIVE 승격(자본 노출)의 선결 게이트가 백테스트인데, 그 백테스트가 라이브와 다른 매매를 검증하면 **승격 판정 자체가 거짓 음성/거짓 양성**이다. autopilot 자기증대 루프(MEMORY `project_autopilot_initiative`)는 "손실 시 풀 기준 하드칭"을 백테스트 검증에 의존하므로, 백테스트 parity는 자율 자본경로의 토대다.

---

## 2. 기존 자원 audit (의무 — 신규 최소 LOCK)

> 헌법 §spec audit + MEMORY `feedback_spec_audit_existing_resources`. main 점유 자원 5종(Flyway·Kafka·Redis·STOMP·REST·ENV)을 audit 했다.

### 2-A. 재사용 자원 (신규 없음 — 이 ADR의 핵심 절약)

| 자원 | 위치 | 본 ADR에서의 역할 |
|---|---|---|
| **`TradePlanEvaluator.evaluate`** | `strategy-engine/.../strategy/TradePlanEvaluator.kt` | ★ **기둥1 핵심** — 라이브 PlanExecutor의 순수 청산 두뇌. 우선순위 `SL>MAX_HOLD>exitConditions>TP>TRAIL`. 백테스트가 **그대로** 호출 → parity 보장. 추가 코드 0(호출만). |
| `buildTradePlan` 매핑 | `consumer/.../risk/SignalGeneratedKafkaListener.kt:1940` | StrategyDefinition(takeProfit/fixedStopLoss/trailingStop/minHold/viAction/allowOvernight) + 진입가 → `TradePlan`. 백테스트가 **동일 규칙**으로 plan을 구성(strategy-engine 순수 함수로 추출 권장 — §6). |
| `ScaleInExitSimulation` | `strategy-engine/.../scalein/ScaleInExitSimulation.kt` | scale-in/exit 활성 전략은 이미 TP/SL/partialTP 시뮬. **유지**(회귀 0). 본 ADR은 비-scale-in 전략의 청산만 교체. |
| `JvmBacktestEngine` | `api/.../service/backtest/JvmBacktestEngine.kt` | t-루프·LookaheadGuard·MTM equity·세금/수수료/슬리피지 모델·진입 정책 게이트(ADR 0114). 본 ADR은 **청산 분기만 add**(모드 옵션). |
| `BacktestRequest`/`Result`/`Performance` | `domain/.../backtest/*` | 모드 플래그 1개 + 정합성 메트릭 필드 add-only. |
| `SymbolDetailMinuteGapFillService` | `consumer/.../reconciliation/` | ★ **기둥2 패턴** — `KisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween(code, from, to)` → save → `AggregateBackfillRunner` 재생성 → readiness 마킹. 백테스트 backfill이 **동일 port·동일 합산 재생성**을 재사용. |
| `KisRestCandleAdapter` (`KisOneMinuteCandleRangePort`) | `consumer/.../infrastructure/kis/` | FHKST03010230 1m fetch(120건/call·일자 페이지네이션·ASC 정규화·EGW backoff). |
| `ActiveStrategyIntradayWarmupRunner` | `consumer/.../ingestion/` | 개장 조기 warmup backfill 패턴(기둥2 페이싱 참고). |
| `KisCallPriority.BACKFILL` | `domain/.../ratelimit/KisCallPriority.kt` | ★ **기둥2 rate limit** — 이미 존재하는 최저 우선순위 bucket. 백테스트 backfill은 이 bucket으로 라이브 매매(USER_PATH)·warmup(REALTIME_MARKET) 쿼터 침범 0. |
| 캔들 readiness/reconciliation (ADR 0105) | `CandleReconciliationStateStore`·`AggregateBackfillRunner` | 기둥3 정합성 표기의 기존 토대(READY/RECONCILING/FAILED). |
| candle 테이블 | `candle(code,interval,ts,open/high/low/close raw·adj,volume,source)` | OHLC·volume 보유 → intrabar SL/TP 시뮬 가능(봉 high/low 사용). Flyway 신규 0. |
| `backtest_run`·`backtest_trade`·`backtest_position` | V(기존) | 결과 영속(현행). 정합성 메트릭은 `backtest_run`에 컬럼 add 또는 결과 JSON 동봉. |

### 2-B. 신규 자원 (최소)

| 종류 | 신규 | 사유 |
|---|---|---|
| Flyway | **0~1** | 정합성 메트릭을 `backtest_run`에 영속하면 V96 1건(컬럼 add-only). 결과 응답 JSON에만 담으면 0. **권장: 0**(R1은 응답 JSON·DB 영속은 검증 게이트 ADR에서). |
| 도메인 | `BacktestExecutionMode` enum(LEGACY/INTRADAY_REALISTIC)·`CandleIntegrityReport` 값 객체·`buildTradePlanFromDefinition` 순수 함수 추출 | parity 모드·정합성 메트릭. |
| ENV | `magicjar.backtest.intraday-realistic.{enabled,backfill-enabled,max-lookback-days}` 3건 | 게이트·backfill on/off·KIS bound. |
| Kafka/Redis/STOMP | **0** | 백테스트는 동기 REST 경로. backfill도 기존 port 직호출. |

---

## 3. 결정 — 3 기둥

### 기둥 1 — 초단타 정합 백테스트 (라이브 parity)

**D1-1. 청산을 `TradePlanEvaluator`로 교체 — "intraday-realistic 모드" (신규 경로 X · 기존 엔진 옵션 O)**

`JvmBacktestEngine.run`에 `BacktestExecutionMode` 분기를 add한다(신규 엔진/신규 컨트롤러 X — 회귀 표면 최소).

- **LEGACY**(default·기존 동작 무변경): DSL sell + scale-in/exit 경로 그대로 — zero-regression LOCK.
- **INTRADAY_REALISTIC**(신규·opt-in): 진입은 기존 DSL entry(COMPOSITE 등) 유지, **청산만** TradePlan 기반으로 교체:
  1. BUY 체결 시 `buildTradePlanFromDefinition(definition, entryPrice, bar, openedAt)`로 **라이브와 동일** TradePlan을 박제(가상 lot).
  2. 매 봉마다 `TradePlanEvaluator.evaluate(plan, PlanEvalState(price, avgPrice, elapsedBars, highWatermark))` 호출 → 청산 결정(`SL>MAX_HOLD>exitConditions>TP>TRAIL`). **라이브 PlanExecutor와 동일 함수 = parity 보장.**
  3. `elapsedBars`는 진입 봉 index 차이로 산출, `highWatermark`는 봉 high 누적 추적(트레일링).
  4. DSL sell은 **hybrid**로 병행 허용(라이브가 plan보장+DSL 병행 자기 lot exit — ADR 0130). 단 plan 청산이 우선 short-circuit(보수적).

**D1-2. intrabar SL 우선 시뮬 (봉 high/low 사용)**

봉 close만 보면 장중 SL 도달을 놓친다(라이브 tick은 잡음). candle 테이블은 high/low 보유 → 한 봉 안에서:
- **SL 우선 가정(보수적·LOCK)**: 봉 low ≤ SL 라인이면 SL 체결(가격=SL 라인). 동시에 봉 high ≥ TP 라인이어도 **SL을 우선**한다(같은 봉에서 둘 다 도달 시 손실을 먼저 — 낙관 편향 차단).
- TP 단독 도달(봉 high ≥ TP, 봉 low > SL): TP 라인 체결.
- 둘 다 미도달: close로 trailing/exitCond/MAX_HOLD 평가.
- 1m 베이스(또는 3m/5m 집계)에서 intrabar 범위가 작을수록 이 근사 오차가 작다 → 초단타는 1m/3m 권장.

> **정직 한계**: 같은 1m 봉 안의 SL→반등→TP 순서는 알 수 없으므로 **SL 우선**이 보수적 정답이다(실현 손익을 과대평가하지 않음). 호가/체결 시퀀스가 없는 백테스트의 구조적 한계 — mock으로 메우지 않는다.

**D1-3. EOD 청산·개장 blackout·min_hold parity (워커 로직을 순수 헬퍼로 복제)**

이 3개는 `TradePlanEvaluator` 밖(라이브 워커: `AutopilotEodLiquidationWorker` 15:15·SignalGeneratedKafkaListener open-blackout·MinHoldGuard)에 있다. 백테스트는 봉 ts(KST)만으로 판정 가능하므로 **순수 시간 헬퍼**로 복제한다(라이브 상수와 동일 값 공유):
- **EOD**: `allowOvernight=false`이고 봉 ts ≥ 15:15 KST → 그 봉 close로 강제 청산(exitReason=EOD). late-entry cutoff(14:30↑ 진입 금지)도 동일.
- **개장 blackout**: 봉 ts ∈ [09:00, 09:15) KST → BUY 신호 무시(진입 0). SELL/SL/TP는 영향 0.
- **min_hold**: 진입 후 `minHoldBars` 봉 동안 DSL sell·EOD 외 청산 보류(SL/TP는 항상 작동 — 라이브 규약 정합).

> 라이브 상수(15:15·14:30·09:00~09:15·minHoldBars)는 **단일 출처**(strategy-engine 상수 또는 ENV)에서 라이브·백테스트가 공유 — drift 0.

**D1-4. allow_overnight=true(스윙) 전략은?**

allow_overnight=true이면 EOD 청산이 없으므로 백테스트도 봉 close 보유를 이어간다(라이브와 동일). 즉 본 모드는 **초단타 전용이 아니라 라이브 parity 일반화** — 초단타는 그 중 EOD=LIQUIDATE 케이스다.

---

### 기둥 2 — 분봉 on-demand backfill (백테스트 중 수집)

**D2-1. 동기 선수집(synchronous pre-fetch) — 비동기 대기 X**

백테스트는 사용자가 결과를 기다리는 동기 REST다. 따라서 backfill도 **백테스트 시작 직전 동기 선수집**한다(비동기 큐+폴링 대기는 UX 복잡·결과 일관성 risk). 흐름:
1. 백테스트 요청(universe·interval·from~to) 수신.
2. 각 code에 대해 DB의 해당 interval 봉 범위를 확인 → gap(부재 구간) 산출.
3. gap이 있으면 `KisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween(code, gapFrom, gapTo)`로 1m fetch → save → `AggregateBackfillRunner.backfill(code)`로 3m/5m 등 집계 재생성(`SymbolDetailMinuteGapFillService`와 동일 경로).
4. backfill 완료 후 정합성 검사(기둥3) → 충분하면 백테스트 진행, 불충분하면 표기/skip.

**D2-2. KIS 분봉 lookback 한계 (정직 명시 — KIS-first raw fetch 의무)**

KIS 공식 출처 raw fetch 결과(`open-trading-api/.../inquire_time_dailychartprice.py`):
- **endpoint**: `FHKST03010230` (inquire-time-dailychartprice)
- **최대 120건/call**, 입력 cursor = `FID_INPUT_DATE_1`(YYYYMMDD) + `FID_INPUT_HOUR_1`(HHMMSS)
- **보관 한계**: KIS 원문 — *"과거 분봉 조회 시, 당사 서버에서 보관하고 있는 만큼의 데이터만 확인이 가능합니다. (최대 1년 분봉 보관)"*

→ **초단타 백테스트 윈도우는 KIS 1m 가용 범위(최대 1년)로 bound**한다. 단 1년 backfill은 호출 폭증(종목당 약 252 거래일 × 4~6 call/일 = ~1,000~1,500 call/종목)이므로 ENV `max-lookback-days`로 **기본 30 거래일**(약 30일 × 5 call = ~150 call/종목)로 클램프하고, 사용자가 명시 확장 가능. 요청 from이 bound보다 과거면 **bound로 클램프 + `LOOKBACK_CLAMPED` 경고**(거짓 결과 차단).

> **"가능한 최대 기간" 산출**: 본 시스템은 ADR 0105에서 1m 기본 6개월·admin 최대 1년 retention을 둔다. 백테스트 backfill은 그 정책 위에서 동작 — 이미 DB에 있는 1m은 재fetch 0, gap만 fetch. 실효 최대 = min(KIS 1년, ADR 0105 retention, ENV max-lookback-days).

**D2-3. rate limit 보호 (KisCallPriority.BACKFILL)**

분봉 대량 수집은 KIS 초당 한도(~10~20 req/s) 압박 → **반드시 `KisCallPriority.BACKFILL`**(최저)로 발행. 라이브 매매(USER_PATH)·warmup(REALTIME_MARKET)·ingestion이 항상 우선. EGW00201(초당 거래건수 초과) 발생 시 exponential backoff(기존 어댑터 500 retry 정책 + EGW backoff 재사용). 백테스트가 라이브 자본경로를 굶기면 안 되므로, 장중(09:00~15:30)에는 backfill 페이싱을 더 보수적으로(또는 사용자 경고) 둔다.

---

### 기둥 3 — 캔들 정합성 가드 (경량 시스템 현실)

**D3-1. 정합성 메트릭 (`CandleIntegrityReport`)**

backfill 후 거래일별 **기대 봉 수 대비 실제 봉 수**로 완결성을 측정한다:
- 기대 봉 수: interval별 거래일당 bar 수(예 1m KRX 정규장 390 bar/일·NXT 통합 720 bar/일 — candle-data-policy §3-B). 거래일은 KRX 영업일 캘린더(주말/공휴일 제외 근사·post-MVP 정밀).
- 메트릭: `expectedBars`·`actualBars`·`missingBars`·`missingRatio`(=missing/expected)·거래일별 갭 목록.

**D3-2. 판정 등급 (mock 절대 금지 — 방향성 가드 LOCK)**

| 등급 | 조건(권장 default·ENV 조정) | 동작 |
|---|---|---|
| **OK** | missingRatio ≤ 5% | 정상 백테스트. |
| **LOW_CONFIDENCE** | 5% < missingRatio ≤ 20% | 백테스트 **진행하되** 결과에 `confidence=LOW_CONFIDENCE` + missingRatio 동봉(UI 경고 배지). |
| **INSUFFICIENT_DATA** | missingRatio > 20% **또는** 최소 거래일 수 미달 | 해당 **종목 skip**(부분 결과·skip 사유 동봉). universe 전부 불충분이면 `BacktestResult.Aborted(reason="INSUFFICIENT_CANDLE_INTEGRITY")`. |

> **mock 금지**: 결측 봉을 직전봉 복제·선형보간 등으로 **절대 메우지 않는다**(`feedback_no_mock_data`). 데이터가 없으면 없는 대로 등급으로 노출 — 거짓 수익률보다 "신뢰도 낮음/데이터 부족"이 정직하다.

**D3-3. 결과 동봉 — 투명성**

`BacktestResult.Success`에 `candleIntegrity: Map<String, CandleIntegrityReport>`(code별) + `confidence`를 add-only로 동봉. UI는 "몇 % 봉 결측"을 항상 보여준다. 정합성 보고 없는 백테스트 수치는 신뢰 금지(사용자 강조 — 경량 시스템 현실).

---

## 4. 이것은 "검증 게이트"의 선결 인프라다 (후속 ADR 연결)

본 ADR(parity 백테스트 + 분봉 backfill + 정합성)은 **전략 ACTIVE 승격 전 백테스트 통과 게이트**의 **데이터/엔진 토대**다. 게이트 자체(승격 임계치·G1~G3·OOS·과최적화 — ADR 0117/0121)의 **판정 규칙 결정은 후속 ADR**(가칭 "전략 ACTIVE 승격 검증 게이트")로 분리한다. 본 ADR은 "게이트가 신뢰할 수 있는 라이브-동치 백테스트 결과를 만든다"까지만 책임진다. 후속 게이트 ADR은 본 ADR의 `INTRADAY_REALISTIC` 모드 + `confidence≥OK` 결과를 입력으로 쓴다.

---

## 5. 대안 · 트레이드오프

| 대안 | 내용 | 채택? | 사유 |
|---|---|---|---|
| **A. 신규 백테스트 엔진/컨트롤러** | 초단타 전용 엔진 별도 구축 | ❌ | 코드 중복·세금/슬리피지/MTM/룩어헤드 재구현·회귀 표면 폭증. 기존 엔진 모드 옵션이 보수적. |
| **B. (채택) 기존 엔진 모드 옵션 + TradePlanEvaluator 재사용** | `BacktestExecutionMode` 분기·라이브 청산 함수 호출 | ✅ | parity는 "같은 함수"로만 보장된다. 신규 최소·zero-regression(LEGACY default). |
| **C. 청산 로직을 백테스트에 재구현** | TP/SL/trail를 백테스트용으로 다시 작성 | ❌ | drift 필연(라이브 우선순위·KRX tick·평단 연동을 또 구현 → 또 다른 버그). MEMORY `feedback_verification_strict` "단위테스트≠라이브"의 정확한 함정. |
| **D. 비동기 backfill + 폴링 대기** | backfill 큐잉 후 준비되면 백테스트 | ❌ | 동기 REST UX에 폴링/상태 복잡. 동기 선수집이 결과 일관성·단순성 우위. (대용량 1년 backfill만 별도 admin 경로로 — post-MVP). |
| **E. 결측 봉 보간(forward-fill)** | 직전봉 복제로 메움 | ❌❌ | 방향성 가드 mock 금지 정면 위반. 거짓 OHLC가 거짓 TP/SL 체결을 만듦 — 자본 판정 오염. |
| **F. 일봉으로 초단타 근사** | 1m 없으면 1d로 | ❌ | 일봉은 intrabar TP/SL/EOD를 시뮬할 수 없음(초단타 본질 소실). interval이 부족하면 INSUFFICIENT_DATA가 정직. |

---

## 6. 구현 방향 (spec 상세 — 여기선 모듈 경계만)

- **strategy-engine**(순수): `buildTradePlanFromDefinition` 순수 함수 추출(현 `SignalGeneratedKafkaListener.buildTradePlan` 로직을 strategy-engine으로 hoist → 라이브·백테스트 공유·drift 0). EOD/blackout/min_hold 상수 단일 출처. `IntradayExitSimulation`(TradePlanEvaluator + intrabar SL 우선 + EOD/blackout/min_hold를 묶는 얇은 시뮬 어댑터 — `ScaleInExitSimulation` 형제).
- **api**(`JvmBacktestEngine`): `BacktestExecutionMode` 분기 add. INTRADAY_REALISTIC일 때 `IntradayExitSimulation` 경로. backfill 선수집 훅 + 정합성 검사 호출. 결과에 `candleIntegrity`/`confidence` 동봉.
- **market-data**(consumer 자원·기둥2·3): backfill 선수집 서비스(`SymbolDetailMinuteGapFillService` 패턴 재사용·`BACKFILL` priority·lookback clamp·EGW backoff) + `CandleIntegrityChecker`(기대/실제 봉수·등급 산정). api가 호출하는 port로 노출(헥사고날).
- **domain**: `BacktestExecutionMode`·`CandleIntegrityReport`·`CandleConfidence` enum·`BacktestRequest.executionMode`·`BacktestResult.Success.{candleIntegrity,confidence}` add-only.
- **frontend**(R3·선택): 백테스트 결과에 confidence 배지 + 결측률 표시(mock 금지 노출 — 후속).

> 모듈 경계: 청산 두뇌·plan 구성·시간 헬퍼는 **strategy-engine 순수**(라이브와 동일 코드). KIS fetch·DB·집계는 **consumer**(어댑터). 백테스트 오케스트레이션은 **api**.

---

## 7. 게이트·롤백 (자본 안전)

- 신규 모드는 ENV `magicjar.backtest.intraday-realistic.enabled` default **false** → 미설정 시 백테스트는 LEGACY 그대로(byte-동일·zero-regression). orchestrator가 검증 후 on.
- backfill은 `backfill-enabled` 별도 게이트 + `BACKFILL` priority로 라이브 자본경로 보호. off면 DB 기존 봉만으로 백테스트(부족 시 INSUFFICIENT_DATA).
- 롤백: 게이트 false 복귀 → 신규 경로 전부 no-op. 도메인 add-only 필드는 default로 무해.
- **자본 직접 영향 0**: 백테스트는 시뮬레이션·주문 발행 0. 단 **승격 판정의 입력**이므로 정합성 가드(거짓 결과 차단)가 자본 간접 보호.

---

## 8. 검증 (정직 evidence — 할루시네이션 금지)

- parity 단위: 동일 전략·동일 봉 시퀀스에서 `TradePlanEvaluator` 백테스트 청산 == PlanExecutor 라이브 청산 사유/가격 일치(SL/TP/MAX_HOLD/TRAIL 각 케이스).
- intrabar: 같은 봉 SL∧TP 동시 도달 → SL 우선 체결 단언.
- EOD/blackout/min_hold: 시간 경계 봉에서 발화/억제 단언.
- backfill: gap 산출·KIS fetch·집계 재생성·lookback clamp·EGW backoff(mock KIS port).
- 정합성: missingRatio 등급 경계(5%/20%)·skip·Aborted·**보간 0 단언**.
- 라이브 재현(MOCK): autopilot 6전략을 INTRADAY_REALISTIC로 재백테스트 → 거래가 12일 스윙이 아니라 **TP/SL/EOD 초단타**로 닫히는지 거래 상세 evidence 인용(라이브 거동과 대조).
```
