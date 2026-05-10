# ADR 0056 — Backtest Engine Architecture (룩어헤드 가드 + 세·수수료·슬리피지 모델 + ADR 0047 sandbox 통합 + abort quota)

- 상태: Accepted
- 작성일: 2026-05-11
- 결정자: architect (사용자 위임 + orchestrator dispatch · W3 T-BT 진입)
- 관계: ADR 0022 정합(Strategy DSL .kis.yaml superset — 평가 대상 식) · ADR 0023 정합(Signal Engine 룰 only — AI 호출 0건 의무 보존) · ADR 0047 정합(DSL Evaluation Sandbox §2-2-A backtest timeout 1000ms + §2-5 abort 정책) · ADR 0021 정합(Indicator Source — 백테스트 평가 시 IndicatorEngine 단일 진입점)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

마스터 §B.9는 Backtest Engine을 "DSL → 과거 시계열 simulation → 매매 기록 + 성과 지표"로 정의하고 **룩어헤드 차단·생존자 편향 보정·세금/수수료/슬리피지 모델** 4건을 의무 요건으로 못박았다. 그러나 §B.9는 트랙 ID(T-BT)와 책임 영역만 명시할 뿐 **구체적 아키텍처 명세(엔진 구조·모델 인터페이스·룩어헤드 가드 메커니즘·timeout/abort 정책)는 침묵**.

ADR 0047 §2-2-A는 백테스트 평가 timeout 1000ms를 결정했으나 **백테스트 엔진 자체의 구조·트레이드오프**는 본 ADR에서 결정 의무.

**핵심 위험 (사용자 정정 lens — 자동매매 적합성)**:
1. **룩어헤드 누설**: t 시점 평가 시 t+1 데이터 우연 접근 → "과거에 미래를 본" 신뢰 불가 백테스트
2. **세·수수료·슬리피지 누락**: 실전 매매 시 비용으로 손익 역전. 백테스트 결과만 신뢰하면 실전 손실
3. **timeout 무제한**: 사용자 부주의로 식이 무거우면 한 백테스트가 수십 분 hang → 자동매매 본체 정지 (consumer JVM 점유)
4. **자체 추론 세율**: KIS·KRX raw 출처 미인용 + 자체 추론 세율 사용 시 실전 손익 차이 + 사용자 비판 risk

**필요한 결정**:
- BacktestEngine 인터페이스 + 1차 구현체 구조
- 룩어헤드 가드 메커니즘 (compile-time vs runtime)
- 세·수수료·슬리피지 모델 인터페이스 + default 구현 + 사용자 파라미터화
- ADR 0047 sandbox 통합 방식 (strategy-eval-pool 공유 vs 분리)
- abort quota (timeout 누적 임계)
- 결과 영속 vs stateless

---

## 2. 결정

### 2-1. BacktestEngine 아키텍처 — 단일 모델 인터페이스 + open extension

`BacktestEngine` port를 단일 도메인 인터페이스로 정의 + `JvmBacktestEngine` api 모듈 구현체 1차 채택. 모델은 인터페이스 3종(Tax · Commission · Slippage)으로 open extension.

```kotlin
// domain/backtest/port/BacktestEngine.kt
interface BacktestEngine {
    fun run(request: BacktestRequest): BacktestResult
}

interface BacktestTaxModel {
    val name: String
    fun calculate(side: TradeSide, price: BigDecimal, quantity: Long, market: String): BigDecimal
}

interface BacktestCommissionModel {
    val name: String
    fun calculate(side: TradeSide, notional: BigDecimal): BigDecimal
}

interface BacktestSlippageModel {
    val name: String
    fun apply(side: TradeSide, rawPrice: BigDecimal): BigDecimal
}
```

**구현 위치**: api 모듈 `api/.../service/backtest/JvmBacktestEngine.kt`. 1차는 단일 thread 순차 실행 (universe 종목별 시뮬). 병렬 시뮬은 post-MVP (universe ≥ 100 종목 도달 시 검토).

**모델 default 구현**:
- `KoreanStockTaxModel` (거래세 0.18% + 농특세 0.15% KOSPI + VAT 10%)
- `KisDirectCommissionModel` (위탁 0.015% + 유관기관 0.0036% + VAT 10%)
- `FixedRateSlippageModel` (매수·매도 ±0.05%)

모델 추가는 `BacktestModelRegistry` lookup으로 `BacktestRequest.taxModel` 등 문자열 키 → 구현체 매핑.

### 2-2. 룩어헤드 가드 — slice-by-time + runtime check

#### 2-2-A. 메커니즘

BacktestEngine은 **시간 슬라이스 진행** 구조를 의무:

```kotlin
val allCandles: List<Candle> = candleRepo.findByCodeAndRange(code, from, to)
for (t in 0 until allCandles.size) {
    val slice = allCandles.subList(0, t + 1)  // t 시점까지의 캔들만
    val signal = evaluatorBridge.evaluateBacktest(strategyId, ast, slice)
    // … 시뮬 진행
}
```

#### 2-2-B. Runtime check (LookaheadGuard)

`LookaheadGuard` utility 클래스 — 디버그 모드(opt-in) 시 slice 외 캔들 접근 시도를 감지. 1차는 단순 size check + 단위 테스트 의무. 실제 reflection·바이트코드 hook은 over-engineering — 폐기.

#### 2-2-C. 단위 테스트 의무 (negative case 5건)

- t=10 시점 평가 시 evaluator 내부에서 t+1 캔들 접근 시도 → `IllegalStateException` 또는 단순 list out-of-bounds (수용)
- corp_action ex-date 적용 검증: t < ex-date 시점에 t 캔들의 adjusted_price = raw_price 동일 (재조정 미반영)
- 등 5건

### 2-3. 세·수수료·슬리피지 모델 정책

#### 2-3-A. Default 값 (KIS·KRX·국세청 raw 출처)

| 항목 | default | 출처 |
|---|---|---|
| 증권거래세 | 0.18% (매도 시 KOSPI·KOSDAQ) | 국세청 · KRX https://www.krx.co.kr |
| 농어촌특별세 | 0.15% (매도 시 KOSPI 한정) | 국세청 https://www.nts.go.kr |
| VAT | 10% (수수료 위에 부과) | 부가가치세법 |
| 위탁 수수료 | 0.015% | KIS https://securities.koreainvestment.com |
| 유관기관 수수료 | 0.0036% | KRX·KSD·증선위 합산 |
| 슬리피지 | ±0.05% (매수 +, 매도 −) | magicJar own (KIS raw 부재) |

#### 2-3-B. 사용자 파라미터화

`BacktestRequest.taxModel`·`commissionModel`·`slippageModel` 문자열 키로 사용자가 모델 선택. 1차 등록 모델:

| 키 | 모델 |
|---|---|
| `"korean_default"` | `KoreanStockTaxModel` |
| `"kis_direct"` | `KisDirectCommissionModel` |
| `"fixed_0.05"` | `FixedRateSlippageModel` (rate=0.0005) |
| `"none"` (3 영역 공통) | `NoOpTaxModel`·`NoOpCommissionModel`·`NoOpSlippageModel` (모두 0 반환) |

후속 ADR `0080+`에서 거래량 기반 동적 슬리피지·대주주 양도세 분기 모델 검토.

### 2-4. ADR 0047 Sandbox 통합

#### 2-4-A. strategy-eval-pool 공유

BacktestEngine은 **반드시** `JvmStrategyEvaluator.evaluateBacktest()` 경유. 직접 `walkRoot()` 호출 절대 금지.

```kotlin
// api/.../service/backtest/BacktestEvaluatorBridge.kt
@Component
class BacktestEvaluatorBridge(
    private val evaluator: JvmStrategyEvaluator,  // consumer 모듈 빈
) {
    fun evaluateBacktest(strategyId: Long, ast: DslNode, slice: List<Candle>): StrategyEvalResult =
        evaluator.evaluateBacktest(strategyId, ast, slice)
}
```

`JvmStrategyEvaluator.evaluateBacktest()`은 이미 코드상 존재 (consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/strategy/JvmStrategyEvaluator.kt:87):
- timeout 1000ms 적용 (`@Value("\${magicjar.dsl.backtest-timeout-ms:1000}")`)
- `autoDisable = false` LOCK
- strategy-eval-pool 경유 (4 fixed thread, queue 100)

#### 2-4-B. 백테스트 전용 pool 분리 검토 → 폐기 (1차)

`backtest-eval-pool` 별도 분리안 검토 → **1차 폐기**. 사유:
- 백테스트는 ad-hoc 실행 (사용자 트리거) → 동시 진행 1~2건 수준 — 4-thread 공유 충분
- pool 분리 시 ThreadPoolTaskExecutor 추가 + StrategyEvalMetricRecorder 분리 등 코드 복잡도 증가
- post-MVP 사용자 동시 백테스트 ≥ 5건 도달 시 재검토 (후속 ADR `0080+`)

### 2-5. Abort Quota — timeout 누적 50회 시 백테스트 abort

ADR 0047 §2-5 정합. BacktestEngine은 한 백테스트 내 timeout 누적 카운트 유지:

```kotlin
var timeoutCount = 0
for (t in 0 until allCandles.size) {
    try {
        val signal = evaluatorBridge.evaluateBacktest(...)
        // ...
    } catch (e: StrategyException.StrategyTimeoutException) {
        timeoutCount++
        if (timeoutCount >= 50) {
            return BacktestResult.Aborted(
                request = request,
                partialTrades = trades.toList(),
                reason = "TIMEOUT_QUOTA_EXCEEDED",
                timeoutCount = timeoutCount,
                executedAt = Clock.System.now(),
                durationMs = durationMs(),
            )
        }
        // 50회 미만 시 본 시점 건너뛰고 계속 진행 (signal=HOLD 간주)
    }
}
```

abort 시 부분 결과(trades 일부) 보존 + 사유 응답. 사용자 UI는 "백테스트 중단" 모달 + 식 단순화 권장 메시지 (ADR 0047 §2-5 정합).

### 2-6. 결과 영속 vs Stateless — 1차 stateless

1차 결정: **stateless REST run**. POST /api/backtest/run 시 즉시 응답, DB 영속 0.

**사유**:
- 1차 분량 우선 (~1500 LOC) — backtest_run 테이블 + GET 조회 endpoint 추가 시 ~500 LOC 추가
- 결과 영속은 사용자 가치 (지난 백테스트 기록 보존)이지만 **fitness gap 미달이 아님** — 본 PR-47 사이클 비범위
- backlog §27-2 합류

**후속**: backlog §27-2 발동 시 별도 PR로 분리.

### 2-7. 성과 지표 산출 명세

`pr-47-w3-t-bt-design.md` §7 정합 — 본 ADR에서는 계산식 LOCK만 인용:

| 지표 | 식 |
|---|---|
| 총수익 | `(finalCapital - initialCapital) / initialCapital` |
| CAGR | `(finalCapital / initialCapital)^(1/years) - 1`, `years = days / 365.25` |
| MDD | `max((peak_at_t - equity[t]) / peak_at_t for all t)` |
| Sharpe | `(mean - risk_free / 252) / stdev * sqrt(252)`, n<30 시 null |
| 승률 | `winning_trades / closed_trades` |
| Profit Factor | `sum(win) / sum(abs(loss))`, sum(loss)==0 시 null |
| 매매 빈도 | `trades.size / business_days` |

위 식의 변경은 backlog 의제 합류 + 별도 ADR 필요.

### 2-8. 검증 의무 (PR-48 backend-core sub-agent)

| 항목 | 검증 방법 |
|---|---|
| 룩어헤드 가드 | 단위 테스트 5 negative case 통과 (§2-2-C) |
| 세·수수료 raw 출처 | 각 모델 클래스 KDoc 헤더에 raw URL 인용 (자체 추론 0) |
| ADR 0047 sandbox 정합 | BacktestEngine 코드에 `evaluator.evaluateBacktest()` 호출 라인 인용 — 직접 walkRoot 호출 0건 |
| 룰 5 정합 | `grep -r "anthropic\|openai\|spring-ai" api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/` 0건 |
| 모듈 경계 | `grep -r "import org.springframework\|import jakarta\|import org.apache.kafka" domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/backtest/` 0건 |
| 사용자 메모리 정합 | candle 0건 시 503 "데이터 없음" (feedback_no_mock_data.md), Swagger 4단 한국어 (feedback_swagger_pattern.md) |

---

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-BT (W3 진입)** — 본 ADR이 트랙 권위. domain backtest 도메인 11 파일 + api adapter 7 파일 add-only.
- **T-DSL (W2 완료)** — 영향 0 (이미 evaluateBacktest 코드상 존재).
- **T-RISK (W3 후속)** — 영향 0 (백테스트는 룰 시뮬만, risk guard chain 비적용 — 실전 매매 한정).
- **T-O (W3 후속)** — 영향 0 (백테스트는 mock execution, KisOrderSender 비호출).
- **T-PERF (W3 후속)** — 백테스트 성과 계산식은 본 ADR §2-7 LOCK — T-PERF 일별 실전 매매 통계와는 별개 (실전 측정은 T-PERF, 시뮬 측정은 T-BT 본 ADR).

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `domain` | `backtest/` 디렉토리 신설 — 11 파일 (5 sealed/data + 4 port + 1 exception + 1 LookaheadGuard) |
| `api` | `controller/BacktestController.kt` + `service/backtest/` 디렉토리 신설 (`JvmBacktestEngine`·`KoreanStockTaxModel`·`KisDirectCommissionModel`·`FixedRateSlippageModel`·`BacktestService`·`BacktestModelRegistry`·`BacktestEvaluatorBridge` 7 파일) |
| `consumer` | 영향 0 (기존 `JvmStrategyEvaluator.evaluateBacktest()` 호출만 — public API 이미 존재) |
| `batch` | 영향 0 (1차 stateless — batch job 없음. post-MVP §27-5에서 매주 자동 백테스트 job 검토) |
| `frontend` | 영향 0 (backlog §27-1 합류 — 별도 사이클) |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| 백테스트 abort 빈도 | 1주 ≥ 3회 → 사용자 식 품질 가이드 검토 (ADR 0047 §3-3 정합) |
| 백테스트 평균 소요 시간 | 모니터링 metric — 1년 일봉 × 5 universe 기준 < 5초 권장 |
| strategy-eval-pool 점유 | 1 백테스트 진행 중 4-thread 점유 가능 → 동시 사용자 ≥ 5건 시 CallerRunsPolicy backpressure (ADR 0047 §2-3-A) |

### 3-4. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| (없음) | 본 PR-47 사이클 변경 0건 — 1차 stateless | (해당 없음) |
| `backtest_run`·`backtest_trade` (선택) | backlog §27-2 합류 — 후속 PR | 후속 Vxx |

**환경변수**: 기존 `MAGICJAR_DSL_BACKTEST_TIMEOUT_MS=1000` (ADR 0047) 재사용 — 신규 환경변수 0건.

### 3-5. 보안·격리

- 백테스트는 read-only — DB 조회 + in-memory 시뮬만. KIS REST·WS 호출 0
- 사용자 본인 계정 strategy + universe만 접근 (api Service 차원 권한 검증 의무)
- 결과 응답에 시그널 reason 맵 포함 — 외부 노출 시 민감 정보 0 (전략 식별자만)

### 3-6. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **Stateless REST run** | ✅ 1차 | 분량 우선. backtest_run 테이블 + 영속은 backlog §27-2 |
| (b) Full 영속 (테이블 2건 + GET endpoint) | 폐기 (1차) | ~500 LOC 추가 — 1차 사이클 분량 초과. 사용자 가치는 인정하나 백로그 |
| (c) **단일 모델 인터페이스 + open extension** | ✅ | KoreanStockTaxModel·KisDirectCommissionModel·FixedRateSlippageModel 1차 + 후속 확장 |
| (d) Hardcoded 세·수수료 상수 | 폐기 | 사용자 파라미터화 불가 — fitness 미달 |
| (e) **strategy-eval-pool 공유** | ✅ 1차 | 백테스트 동시 진행 1~2건 수준 — 4 thread 공유 충분. backtest-eval-pool 분리는 post-MVP |
| (f) backtest-eval-pool 별도 분리 | 폐기 (1차) | 코드 복잡도 증가 + 1차 분량 초과 |
| (g) **abort quota 50** | ✅ | ADR 0047 §2-5 정합. 50회 timeout 시 식 자체 문제 추정 — 즉시 중단 + 사용자 식 단순화 권장 |
| (h) abort quota 무제한 (사용자 자율) | 폐기 | 식 부주의 1회로 백테스트 수십 분 hang — 자동매매 본체 정지 risk |
| (i) **룩어헤드 가드 = slice-by-time + runtime check** | ✅ | JDK 표준 API + 단위 테스트 5 negative case. bytecode hook은 over-engineering |
| (j) Bytecode-level guard (ASM·ByteBuddy) | 폐기 | over-engineering — 의존 추가 + 학습 곡선 |
| (k) **세·수수료 raw 출처 KDoc 의무 인용** | ✅ | KIS-first 정책 (헌법 §3). 자체 추론 0 |
| (l) 세·수수료 자체 추론 | 폐기 | 사용자 비판 risk + fitness gap |

### 3-7. 후속 ADR 후보

- **0080+ 후보** — Backtest 결과 영속 + 조회 endpoint (backlog §27-2 발동 시)
- **0081+ 후보** — Backtest 거래량 기반 동적 슬리피지 모델 (backlog §27-3)
- **0082+ 후보** — Backtest 대주주 양도세 분기 모델 (backlog §27-4)
- **0083+ 후보** — Backtest 자동 batch job (매주 활성 strategy 자동 시뮬 + 리포트, backlog §27-5)
- **0084+ 후보** — backtest-eval-pool 분리 (사용자 동시 백테스트 ≥ 5건 도달 시, backlog §27-6)

---

## 4. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-11 | ADR 0056 신규 발행 — W3 T-BT 진입. BacktestEngine 단일 인터페이스 + open extension 모델 3종(Tax·Commission·Slippage), 룩어헤드 slice-by-time 가드, 세·수수료 KIS/KRX/국세청 raw 출처 인용 의무, 슬리피지 magicJar own 명시, ADR 0047 strategy-eval-pool 공유, timeout 1000ms + abort quota 50, 1차 stateless REST run (backlog §27-2 결과 영속 합류), 후속 ADR 0080+ 5건 예약. (architect)
