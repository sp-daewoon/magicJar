# spec — 초단타 정합 백테스트 + 분봉 on-demand backfill + 캔들 정합성 가드

- 발행: architect · 2026-06-24
- ADR: `_workspace/arch/adr/0132-intraday-realistic-backtest.md`
- 트랙: T-BT (백테스트) · T-MS1/T-CA1 (분봉 데이터) 교차
- 코드 0 (본 문서는 인터페이스·라운드 계획 · 구현은 라운드별 task)
- 롤백 앵커: v3.14.x (게이트 default OFF)

---

## 0. 목표 (한 줄)

백테스트 청산을 **라이브 `TradePlanEvaluator`와 동일 함수**로 교체(parity) + 분봉 부재 시 **KIS on-demand backfill**(BACKFILL priority·1년 lookback bound) + backfill 후 **정합성 가드**(결측률·등급·mock 금지). 신규 최소·LEGACY default zero-regression.

---

## 1. 인터페이스 (도메인 시그니처 — R1 LOCK 후보)

### 1-A. 백테스트 모드 (domain · add-only)

```kotlin
// domain/backtest/BacktestExecutionMode.kt (신규)
enum class BacktestExecutionMode {
    /** 기존 동작 — DSL sell + scale-in/exit 경로 (default · zero-regression LOCK). */
    LEGACY,
    /** 라이브 parity — TradePlanEvaluator 청산 + intrabar SL 우선 + EOD/blackout/min_hold. */
    INTRADAY_REALISTIC,
}

// BacktestRequest 에 add-only (default LEGACY → 기존 호출 무변경)
data class BacktestRequest(
    /* ...기존 필드... */
    val executionMode: BacktestExecutionMode = BacktestExecutionMode.LEGACY,
    /** backfill on-demand 허용(ENV 게이트와 AND). default false. */
    val backfillIfMissing: Boolean = false,
)
```

### 1-B. 정합성 메트릭 (domain · add-only)

```kotlin
// domain/market/candle/CandleIntegrityReport.kt (신규)
enum class CandleConfidence { OK, LOW_CONFIDENCE, INSUFFICIENT_DATA }

data class CandleIntegrityReport(
    val code: String,
    val interval: String,
    val expectedBars: Int,
    val actualBars: Int,
    val missingBars: Int,          // = expectedBars - actualBars (>=0)
    val missingRatio: BigDecimal,  // missingBars / expectedBars (0.0~1.0)
    val confidence: CandleConfidence,
    val tradingDaysCovered: Int,
    val lookbackClamped: Boolean = false, // 요청 from < KIS 1년 bound 였는지
) {
    companion object {
        // ENV 조정 가능 default (ADR 0132 §D3-2)
        val LOW_CONFIDENCE_THRESHOLD = BigDecimal("0.05")    // >5% 결측
        val INSUFFICIENT_THRESHOLD = BigDecimal("0.20")      // >20% 결측 → skip
    }
}

// BacktestResult.Success 에 add-only
data class Success(
    /* ...기존 필드... */
    val candleIntegrity: Map<String, CandleIntegrityReport> = emptyMap(),
    val confidence: CandleConfidence = CandleConfidence.OK, // universe 최저 등급
)
```

### 1-C. plan 구성 순수 함수 hoist (strategy-engine)

```kotlin
// strategy-engine/strategy/TradePlanFactory.kt (신규 — 라이브 buildTradePlan 로직 hoist)
object TradePlanFactory {
    /**
     * StrategyDefinition + 진입가 → TradePlan (라이브 SignalGeneratedKafkaListener.buildTradePlan 과 동일 규칙).
     * 라이브·백테스트가 이 단일 함수를 공유 → drift 0. (라이브는 R1에서 본 함수 호출로 교체 — 동작 동일.)
     */
    fun build(
        strategyId: Long,
        definition: StrategyDefinition,
        entryPrice: BigDecimal,
        bar: String,
        openedAt: String,
        thesis: String? = null,
    ): TradePlan
}
```

### 1-D. 초단타 청산 시뮬 어댑터 (strategy-engine · ScaleInExitSimulation 형제)

```kotlin
// strategy-engine/strategy/IntradayExitSimulation.kt (신규)
object IntradayExitSimulation {
    /** EOD/blackout/min_hold 상수 단일 출처 (라이브 워커와 동일 값 공유). */
    val EOD_LIQUIDATE_KST: LocalTime = LocalTime.of(15, 15)
    val LATE_ENTRY_CUTOFF_KST: LocalTime = LocalTime.of(14, 30)
    val OPEN_BLACKOUT_FROM: LocalTime = LocalTime.of(9, 0)
    val OPEN_BLACKOUT_TO: LocalTime = LocalTime.of(9, 15)

    /** 이 봉 ts(KST)에서 owner=AUTOPILOT BUY 가 개장 blackout 으로 억제되는가. */
    fun isOpenBlackout(barTimeKst: LocalTime): Boolean

    /** allowOvernight=false 이고 봉 ts >= 15:15 → EOD 강제 청산 신호. */
    fun isEodLiquidate(allowOvernight: Boolean, barTimeKst: LocalTime): Boolean

    /**
     * 한 봉(OHLC)에서 plan 청산 평가 — intrabar SL 우선.
     * 1) 봉 low <= SL → SL 체결(가격=SL). (봉 high>=TP 여도 SL 우선 — 보수적 LOCK)
     * 2) 봉 high >= TP, 봉 low > SL → TP 체결(가격=TP).
     * 3) 둘 다 미도달 → close 로 TradePlanEvaluator.evaluate(MAX_HOLD/exitCond/TRAIL).
     * 4) min_hold 구간이면 DSL sell·non-SL/TP 청산 보류(SL/TP 는 항상).
     */
    fun evaluateBar(
        plan: TradePlan,
        bar: Candle,              // OHLC raw/adj
        avgPrice: BigDecimal,
        elapsedBars: Int,
        highWatermark: BigDecimal?,
        minHoldBars: Int?,
        allowOvernight: Boolean,
        barTimeKst: LocalTime,
    ): IntradayExitDecision     // reason(SL/TP/MAX_HOLD/TRAIL/EXIT_COND/EOD) + triggerPrice + null(hold)
}
```

### 1-E. backfill + 정합성 port (market-data — consumer 어댑터)

```kotlin
// domain/backtest/port/BacktestCandlePreparePort.kt (신규 — api 가 호출)
interface BacktestCandlePreparePort {
    /**
     * universe × interval × [from,to] 의 분봉 gap 을 KIS on-demand backfill 후 정합성 보고.
     * - KIS 1m lookback bound(최대 1년·ENV max-lookback-days)로 from clamp.
     * - fetch 는 KisCallPriority.BACKFILL. EGW backoff. 집계봉 재생성(AggregateBackfillRunner).
     * - mock 보간 0. 결측은 등급으로만.
     */
    fun prepareAndReport(
        universe: List<String>,
        interval: String,
        from: LocalDate,
        to: LocalDate,
        backfillEnabled: Boolean,
    ): Map<String, CandleIntegrityReport>
}
```

---

## 2. 라운드 (개발 룰 §3 — 한 스텝씩)

| 라운드 | 영역 | 내용 | 테스트 |
|---|---|---|---|
| **R1** | domain + strategy-engine | §1-A·B·C·D 시그니처: `BacktestExecutionMode`·`CandleIntegrityReport`/`CandleConfidence`·`BacktestRequest/Result` add-only·`TradePlanFactory` hoist(라이브 호출 교체·동작 동일)·`IntradayExitSimulation` 골격. | strategy-engine 순수 테스트(R1 일괄 OK·룰 §4): TradePlanFactory == 기존 buildTradePlan 동치·IntradayExitSimulation intrabar SL 우선·EOD/blackout 경계. |
| **R2a** | market-data (consumer) | `BacktestCandlePreparePort` 구현 — backfill 선수집(`SymbolDetailMinuteGapFillService` 패턴·BACKFILL priority·lookback clamp·EGW backoff·집계 재생성) + `CandleIntegrityChecker`(기대/실제 봉수·등급). | mock KIS port: gap fetch·clamp·backoff·등급 경계·보간 0 단언. |
| **R2b** | api (`JvmBacktestEngine`) | `BacktestExecutionMode` 분기 add. INTRADAY_REALISTIC → backfill 선수집 호출 → 정합성 등급 분기(OK/LOW/skip/Abort) → `IntradayExitSimulation` 청산 경로. 결과 `candleIntegrity`/`confidence` 동봉. LEGACY 무변경. ENV 3건. | (R3에서 일괄) |
| **R3** | api 통합 + frontend(선택) | 데이터 흐름 일괄 테스트: autopilot 6전략 INTRADAY_REALISTIC 재백테스트 → TP/SL/EOD 초단타로 닫힘 evidence. frontend confidence 배지(후속 가능). | 시나리오 일괄(룰 §4): parity·intrabar·EOD·정합성 등급·라이브 대조. |

> R2a(market-data)·R2b(api)는 port 인터페이스(R1 LOCK) 기준 **병렬 가능**(add-only·파일 분리). orchestrator가 충돌 행렬 검토 후 병렬 디스패치.

---

## 3. KIS 제약 (KIS-first — 자체 추론 0)

- 1m endpoint = `FHKST03010230` inquire-time-dailychartprice (raw: open-trading-api 검증).
- 120건/call · cursor `FID_INPUT_DATE_1`+`FID_INPUT_HOUR_1` · **보관 최대 1년**(KIS 원문: "최대 1년 분봉 보관").
- → `max-lookback-days` default 30 거래일(~150 call/종목), 상한 = KIS 1년 ∧ ADR 0105 retention.
- rate limit: `KisCallPriority.BACKFILL`(최저) + EGW00201 backoff. 라이브 매매·warmup 쿼터 침범 0.

---

## 4. 정합성 등급 (ADR 0132 §D3 — mock 금지 LOCK)

| 등급 | missingRatio | 동작 |
|---|---|---|
| OK | ≤ 5% | 정상 |
| LOW_CONFIDENCE | 5~20% | 진행 + 배지 |
| INSUFFICIENT_DATA | > 20% or 최소 거래일 미달 | 종목 skip / 전부면 Aborted |

결측 봉 보간·복제·합성 **절대 0**. 등급으로만 노출.

---

## 4-A. 구현 plan — 에이전트별 task

> 의존: R1(strategy-engine/domain) → R2a(market-data)∥R2b(api) → R3(api 통합·frontend). orchestrator가 R2 병렬 디스패치.

### strategy-engine (R1 — parity 두뇌)
- `TradePlanFactory.build` 신규(라이브 `SignalGeneratedKafkaListener.buildTradePlan:1940` 로직 hoist) + 라이브가 본 함수 호출하도록 교체(동작 byte-동일).
- `IntradayExitSimulation`(`TradePlanEvaluator.evaluate` 재사용 + intrabar SL 우선 봉 평가 + EOD/blackout/min_hold 시간 헬퍼). EOD/blackout/late-entry 상수는 라이브 워커와 단일 출처 공유(drift 0).
- 테스트(R1 일괄 OK): TradePlanFactory 동치·intrabar SL∧TP 동시→SL 우선·EOD 15:15·blackout 09:00~09:15·min_hold 구간 SL/TP는 작동.
- author `bot+strategy@magicjar.local`.

### domain (R1 — strategy-engine task에 동봉 가능)
- `BacktestExecutionMode`·`CandleConfidence`·`CandleIntegrityReport`·`BacktestCandlePreparePort` 신규.
- `BacktestRequest.{executionMode,backfillIfMissing}`·`BacktestResult.Success.{candleIntegrity,confidence}` add-only(default→기존 호출 무변경).

### market-data (R2a — backfill + 정합성)
- `BacktestCandlePreparePort` consumer 구현 — gap 산출 → `KisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween`(BACKFILL priority) → save → `AggregateBackfillRunner` 집계 재생성(`SymbolDetailMinuteGapFillService` 패턴 재사용). lookback clamp(KIS 1년·ENV)·EGW backoff.
- `CandleIntegrityChecker` — interval별 기대 봉수(candle-data-policy §3-B) 대비 실제 봉수·결측률·등급 산정·거래일 캘린더 근사.
- 테스트: mock KIS port gap fetch·clamp·backoff·등급 경계(5%/20%)·**보간 0 단언**.
- author `bot+market-data@magicjar.local`.

### api / backend-core (R2b — 엔진 분기)
- `JvmBacktestEngine` `BacktestExecutionMode` 분기: INTRADAY_REALISTIC → `prepareAndReport` 선수집 → 등급 분기(OK 진행·LOW 진행+배지·INSUFFICIENT skip·전부면 Aborted) → `IntradayExitSimulation` 청산. LEGACY 무변경.
- 결과 `candleIntegrity`/`confidence` 동봉. ENV 3건(`magicjar.backtest.intraday-realistic.{enabled,backfill-enabled,max-lookback-days}`).
- author `bot+backend-core@magicjar.local` (또는 strategy — orchestrator 판단).

### frontend (R3 — 선택·후속 가능)
- 백테스트 결과 confidence 배지 + 종목별 결측률 표시(mock 금지 노출). 데이터 부족 종목 skip 사유 표시.
- author `bot+frontend@magicjar.local`.

### qa (R3 — 라이브 대조 검증)
- autopilot 6전략 INTRADAY_REALISTIC 재백테스트 → 거래가 12일 스윙이 아니라 TP/SL/EOD 초단타로 닫히는지 거래 상세 evidence(라이브 거동과 대조). parity·intrabar·정합성 등급 시나리오 일괄.
- author `bot+qa@magicjar.local`.

### 검증 게이트 (정직 evidence)
- `:domain :strategy-engine :consumer :api :ktlintCheck` GREEN.
- R3 MOCK 라이브 재백테스트 거래 상세 인용(할루시네이션 금지 — `feedback_verification_strict`).

---

## 5. CHANGELOG 슬롯 (사전 발행 — 헌법 §4)

CHANGELOG.md head에 ADR 0132 슬롯 row + sub-agent별 anchor 사전 prepend(이 spec PR에서). 구현 PR이 해당 anchor 위에 row를 채운다.
