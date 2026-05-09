# PR-32 T-IND 설계 가이드 (W2 진입 — 80지표 일괄 + Source 추상화 + Redis 캐시)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 대상 PR 시리즈: PR-33a (strategy-engine — 80지표 일괄 + IndicatorEngine + KisStrategyBuilderSource + REST controller + Redis 캐시) · PR-33b (backend-core — domain port + NativeIndicatorSource 5종 + cross-check audit) · PR-34 (R3 — frontend 차트 overlay + 통합 테스트 일괄)
- 진입 prerequisite: v3.1.0 W1 풀세트 클리어 + v3.1.1 hotfix 컷 완료. main HEAD = `d811743` (Living Policy Docs 도입). T-IM1 instrument master + T-CA1 corp action adjusted candle + T-MS1 market session FSM + T-W1·T-W2·T-S1 watchlist·ingestion·symbol-detail 모두 main 위 정상 동작.
- 정합 SoT:
  - **ADR 0021** Indicator Source 추상화 (`IndicatorSource` plug-in 인터페이스 — 본 PR-32 spec이 implementation 명세로 풀어냄, **신규 ADR 0건**)
  - **ADR 0020** KIS Strategy Builder 흡수 = 명세 port (80지표 + DSL 11 클래스 + 10 프리셋 흡수 결정)
  - **ADR 0019** 캔들 저장 정책 (Watchlist 기반 + Yahoo 분봉 DB 영속) — 본 PR-32는 candle DB read 의존 (T-W2 영속 결과물 활용)
  - **ADR 0029** 당일 진행 중 캔들 Redis (1m closing 후 DB INSERT — IndicatorEngine은 DB candle + Redis 보조 사용)
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (본 spec PR이 sub-agent 슬롯 row 사전 발행 의무)
  - **ADR 0051** Repository default fun 함정 (Spring Data JPA Repository interface body 함수 금지 — 본 PR-32에서 IndicatorCacheRepository 신설 시 정독 의무)
  - `docs/architecture/master-architecture.md` v2 §B.5(80지표) · §5-1(domain/indicator/) · §6-2(`indicator:cache` Redis key) · §7-3(Source 추상화 v2 핵심) · §10 ADR 인덱스
  - `docs/architecture/tracks-roadmap.md` §1 W2 · §2 T-IND row · §3 T-IND outline · §5 W2 진행 순서 · §6 T-IND 라운드 분할(R1·R2·R3)
  - `docs/architecture/development-rules.md` 5건(특히 룰 3 라운드 분할 + 룰 4 R3 일괄 테스트 + 룰 5 AI 호출 0)
  - `docs/architecture/code-convention.md`
  - `docs/architecture/external-api-policy.md` (KIS Rate Limit · Strategy Builder 출처 의무)
  - `docs/architecture/policies/kis-first-raw-fetch.md` (자체 추론 0 — Strategy Builder `indicators.py` raw fetch 의무)
  - `_workspace/strategy/indicator-catalog.md` (80지표 Kotlin port 시 source-of-truth)
  - `_workspace/arch/agent-charter.md` §3(KIS-first) · §4(CHANGELOG 슬롯) · §5(rebase) · §6(Repository default fun 함정)

- 작업 원칙 (개발 룰북 정합):
  - **룰 2 — 단일 책임**: PR-33a = strategy-engine 단일 (consumer indicator/ + api 컨트롤러). PR-33b = backend-core 단일 (domain port + Native source + audit). 영역 LOCK.
  - **룰 3 — 라운드 분할**: 본 PR-32 = R1 spec only(코드 0). PR-33 = **R1+R2 통합** (80지표 일괄이라 인터페이스만 따로 하면 비효율 — sub-agent 1=KIS source 일괄, sub-agent 2=Native + port). PR-34 = R3(frontend overlay + 통합 테스트 일괄).
  - **룰 4 — 코드 먼저, 테스트 마지막 일괄**: PR-33a/b는 정확성 fixture 단위 테스트 5종(SMA·EMA·RSI·MACD·BB)만 의무 + 80지표 전수 검증은 PR-34 R3 일괄.
  - **룰 5 — AI 호출 0**: T-IND는 W2 — LLM provider import 0건 의무.

---

## §1. T-IND 한 줄 목표 + DoD

### 1-1. 목표

> **사용자가 화면에서 종목 차트를 보거나 전략을 평가할 때 80지표가 in-process로 즉시 계산되어 응답한다. domain `IndicatorEngine`은 등록된 `IndicatorSource` 후보 중 spec 지원 + priority 우선으로 단일 source를 선택하여 라우팅한다. 1차에는 KIS Strategy Builder의 `indicators.py` 80개 함수를 그대로 Kotlin port한 `KisStrategyBuilderSource`(priority=100)와 magicJar 자체 5종(SMA·EMA·RSI·MACD·BB) `NativeIndicatorSource`(priority=50)이 등록되어, Native 활성 영역은 자체 구현이 KIS보다 우선 사용된다. 미지원 spec 요청 시 `IllegalArgumentException("No source supports {name}")` 명확 에러. 결과는 Redis `indicator:cache:{code}:{interval}:{name}:{paramsHash}` (분봉 5분 / 일봉 1일 TTL) 캐시. REST `GET /api/indicators?code=...&name=...&interval=...&params=...&from=...&to=...` 단일 endpoint로 frontend 차트 overlay·StrategyBuilder picker·DSL 평가기 모두에게 동일 인터페이스 제공. 정확성은 5종 fixture 단위 테스트 (R3 일괄에서 80종 전수 + drift 0.001% 검증).**

### 1-2. DoD (Definition of Done) — PR-33 + PR-34 모두 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | IndicatorEngine 부팅 등록 | `docker logs magicjar-consumer-app | grep IndicatorEngine` | "Registered indicator sources: [native (priority=50), kis-strategy-builder (priority=100)]" log |
| 2 | REST GET 80지표 (KIS source) | `curl 'http://localhost:8080/api/indicators?code=005930&name=adx&interval=1d&params=period=14&from=2024-01-01&to=2024-12-31'` | 200 OK + `{"spec":{"name":"adx","params":{"period":14}},"series":[...],"source":"kis-strategy-builder"}` |
| 3 | REST GET 5종 (Native source 우선) | `curl '...name=ma&interval=1d&params=period=20...'` | `"source":"native"` (priority=50) |
| 4 | 미지원 spec 에러 | `curl '...name=unknown_xyz&...'` | 400 + `{"error":"No source supports unknown_xyz"}` |
| 5 | Redis 캐시 hit | 같은 호출 2회 — 두 번째는 SET 호출 0건 | `redis-cli MONITOR` 첫 호출 SET, 두 번째 GET only |
| 6 | 캐시 TTL 일봉 1일 | `redis-cli TTL indicator:cache:005930:1d:rsi:1f3a...` | ≤ 86400, ≥ 86000 |
| 7 | 캐시 TTL 분봉 5분 | `redis-cli TTL indicator:cache:005930:1m:rsi:1f3a...` | ≤ 300, ≥ 280 |
| 8 | DSL 평가기 통합 (T-DSL prerequisite) | (T-DSL 진입 후 검증) | DSL `MA(20) > MA(60)` 표현이 IndicatorEngine 호출 |
| 9 | frontend 차트 overlay (R3 PR-34) | UI에서 RSI overlay 토글 | LWC 차트에 RSI line 추가 + 5분 캐시 hit |
| 10 | Yahoo cold-start candle 호환 | T-W2 등록한 종목 → IndicatorEngine 호출 | candle source='YAHOO'+source='KIS' 둘 다 read OK |
| 11 | Adjusted candle 사용 (T-CA1 정합) | `code=005930&adjusted=true` (옵션) | candle.close_adjusted 사용 — 주식 분할 보정 정합 |
| 12 | 동시성 — 30 종목 × 80 지표 | smoke 부하 30 RPS | response p95 ≤ 200ms (캐시 적중 시 ≤ 30ms) |
| 13 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer,batch,domain}/src/main` | 0건 |
| 14 | 80지표 정확성 (R3 PR-34 일괄) | `./gradlew :consumer:test --tests "*IndicatorAccuracyTest"` | 80개 fixture 케이스 GREEN + drift 0.001% 미만 |
| 15 | Repository default fun 함정 부재 (ADR 0051) | `grep -rE "interface.*JpaRepository.*\{" -A 100 api/src consumer/src` review | body 가진 fun 0건 |
| 16 | Graceful shutdown (SIGTERM) | `docker stop magicjar-consumer-app` | IndicatorEngine in-flight 호출 5s 이내 완료 + caches flush 안 함 (Redis 영속) |

### 1-3. 비즈니스 가치

- **W2 시작점 — T-PAT·T-DSL·T-SB·T-BT·T-SIG 모든 후속 트랙의 prerequisite**. 80지표 없이는 W2·W3 어떤 트랙도 못 시작.
- **사용자 가치 1**: 차트에 80개 지표 overlay 토글 가능 (RSI·MACD·BB·SuperTrend 등 주요 지표 차트 위 시각화).
- **사용자 가치 2**: StrategyBuilder GUI에서 80지표 picker 사용 가능 (T-SB R2).
- **사용자 가치 3**: DSL `.kis.yaml` 파일에 사용된 모든 KIS 지표 그대로 평가 가능 (T-DSL).
- **자동매매 fitness (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)**: 80지표는 quant trading 본체의 진정한 신경계 — 룰 본체(W2-W3)의 "왜 매매하는가" 답변. v1/v2 잔재 0건 (PoC 시점에는 RSI 단일 1차 구현만 존재했으나 v2 비전 정합 위해 80개 일괄 결정 — ADR 0020·0021).

---

## §2. ADR 0021·0020 준수 항목 체크리스트

본 PR-32(spec) → PR-33(코드)이 ADR 0021 §2-1~§2-6 + ADR 0020 §3-2를 그대로 implementation 한다. 신규 ADR 0건 — ADR 본문은 이미 충분.

### 2-1. ADR 0021 (Indicator Source 추상화) — 의무 항목

| ADR 0021 §위치 | 결정 사항 | PR-33 구현 위치 | 담당 |
|---|---|---|---|
| §2-1 Port 정의 | `IndicatorSource` interface (`name`·`priority`·`supports(spec)`·`calculate(spec, candles)`) | `domain/.../indicator/port/IndicatorSource.kt` | **PR-33b backend-core** |
| §2-1 IndicatorSpec/Result/Point | `IndicatorSpec(name, params)` + `IndicatorResult(spec, series, source)` + `IndicatorPoint(time, values)` data class | `domain/.../indicator/{IndicatorSpec,IndicatorResult,IndicatorPoint}.kt` | **PR-33b backend-core** |
| §2-2 Engine | `IndicatorEngine(sources)` filter+supports → minByOrNull priority | `domain/.../indicator/IndicatorEngine.kt` | **PR-33b backend-core** |
| §2-3 1차 구현 priority 표 | KisStrategyBuilderSource=100, NativeIndicatorSource=50 | `consumer/.../application/indicator/source/*.kt` | **PR-33a strategy-engine + PR-33b backend-core** |
| §2-4 결과 컬럼 일관성 | `MA→{ma}`·`MACD→{macd, signal, histogram}`·`BB→{upper, middle, lower}` 등 80지표 schema 명세 | `_workspace/strategy/indicator-catalog.md` 보강 (multi-output 컬럼 명세 추가 — 본 spec §5에 매트릭스 첨부) | **PR-32 spec (본 문서)** |
| §2-5 캐시 | Redis `indicator:cache:{code}:{interval}:{name}:{paramsHash}` TTL 분봉 5분 / 일봉 1일 | `consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt` | **PR-33a strategy-engine** |
| §2-6 cross-check (옵션, default off) | `MAGICJAR_INDICATOR_CROSSCHECK=true` 시 모든 supports 가능 source 동시 계산 + drift 0.001% 이상 ALERT | `consumer/.../application/indicator/CrossCheckEngine.kt` (optional, PR-33b add) | **PR-33b backend-core (옵션)** |

### 2-2. ADR 0020 (KIS Strategy Builder 흡수)

| ADR 0020 §위치 | 결정 사항 | PR-33 구현 |
|---|---|---|
| §3-1 80지표 — 명세·로직 port | `indicators.py` 80개 함수를 Kotlin로 1:1 port (KIS 코드 직접 import 0) | **PR-33a strategy-engine** — `KisStrategyBuilderSource.kt` + 80개 helper 함수 (`Indicator{TREND,MOMENTUM,VOLUME,VOLATILITY,OSCILLATOR,OTHER}.kt` 6 파일 분할 권장) |
| §3-4 KIS 코드 라이선스·출처 표기 | 각 helper 클래스 KDoc에 `Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py · License: MIT · function: calc_xxx` 명시 | KDoc 헤더 의무 |
| §3-5 AI hook 명시 제거 | T-IND 코드에서 LLM provider import 0건 (룰 5 정합) | DoD #13 |
| §4-4 검증·정확성 | 80지표 fixture 정확성 검증 — 표준 입력에 대한 expected output 비교, drift 0.001% 미만 | **PR-34 R3 일괄** (5종은 PR-33a 단위 테스트 의무) |
| §4-5 AI 호출 차단 검증 | T-IND 코드 grep으로 LLM provider import 0건 검증 | **PR-32·PR-33·PR-34 모두** (DoD #13) |

### 2-3. KIS-first raw fetch 의무 (헌법 §3)

본 PR-32 spec 작성 시 — KIS Strategy Builder `indicators.py` 직접 raw fetch 의무. 자체 추론 0.

```bash
# raw fetch 명령 (sub-agent PR-33a strategy-engine 위임 시 본인 직접 재확인)
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py
```

- 80개 함수 시그니처 (param 이름·default 값·return shape)는 위 raw 출처 1:1 정합 의무
- `_workspace/strategy/indicator-catalog.md`는 raw fetch 결과 요약본 (이미 v3.1.0 시점에 작성됨 — 80개 카탈로그 + default params 명시)
- sub-agent PR-33a는 80개 함수 구현 시 각 함수 KDoc에 `Source: indicators.py:line_NN` 인용 의무 (자체 추론 0)
- multi-output 함수 (MACD, BB 등)의 return tuple 인덱스 → values map key 매핑은 본 spec §5 매트릭스 LOCK

---

## §3. 모듈 경계 결정 (사용자 결재 필요 항목)

### 3-1. domain — port + 도메인 모델 (Spring 의존 0)

`domain/src/main/kotlin/com/mj/domain/indicator/`:

```
indicator/
├── IndicatorSpec.kt          ← data class(name, params: Map<String, Any>)
├── IndicatorResult.kt         ← data class(spec, series: List<IndicatorPoint>, source: String)
├── IndicatorPoint.kt          ← data class(time: Instant, values: Map<String, BigDecimal>)
├── IndicatorCatalog.kt        ← 80 spec 등록 (id·category·default params) — read-only data
├── IndicatorEngine.kt         ← class(sources: List<IndicatorSource>) — calculate() 라우팅
├── IndicatorException.kt      ← sealed class (UnsupportedSpecException · InsufficientCandlesException · CalculationFailureException)
└── port/
    └── IndicatorSource.kt     ← interface (★ ADR 0021 §2-1)
```

**판단 근거**: master §5-1 domain/indicator/ 트리 정합. ADR 0021 §2-1에서 IndicatorSource port가 domain 위치로 명시됨.

### 3-2. consumer — IndicatorSource 구현체 + REST 어댑터 + 캐시

`consumer/src/main/kotlin/com/mj/consumer/application/indicator/`:

```
application/indicator/
├── source/
│   ├── KisStrategyBuilderSource.kt    ← IndicatorSource 구현 (priority=100, 80지표)
│   ├── kis/
│   │   ├── IndicatorTrend.kt          ← 20개 (ma·ema·wma·...·regression_slope)
│   │   ├── IndicatorMomentum.kt       ← 20개 (rsi·stochastic_k·...·fisher)
│   │   ├── IndicatorVolume.kt         ← 12개 (obv·volume_ma·...·kvo)
│   │   ├── IndicatorVolatility.kt     ← 12개 (bb·bb_width·...·augen)
│   │   ├── IndicatorOscillator.kt     ← 8개 (adx·adxr·...·bop)
│   │   └── IndicatorOther.kt           ← 8개 (std·variance·...·regression_intercept)
│   ├── NativeIndicatorSource.kt        ← IndicatorSource 구현 (priority=50, 5종)
│   └── native/
│       ├── NativeMa.kt                 ← magicJar 자체 SMA
│       ├── NativeEma.kt                ← magicJar 자체 EMA
│       ├── NativeRsi.kt                ← magicJar 자체 RSI
│       ├── NativeMacd.kt               ← magicJar 자체 MACD
│       └── NativeBollingerBands.kt     ← magicJar 자체 BB
├── IndicatorEngineConfig.kt            ← @Configuration @Bean IndicatorEngine(sources injected)
└── CrossCheckEngine.kt                 ← (옵션) MAGICJAR_INDICATOR_CROSSCHECK=true 시 활성

consumer/src/main/kotlin/com/mj/consumer/infrastructure/cache/
└── RedisIndicatorCacheAdapter.kt       ← Redis 캐시 read/write
```

**판단 근거**: master §5-2 표 — Indicator는 consumer가 "실시간 계산(`IndicatorSource` 합성 — 80개 in-process)" 책임. consumer가 지표 계산 본체.

### 3-3. api — REST 컨트롤러 + 도메인 포트 의존만

`api/src/main/kotlin/com/mj/api/controller/`:

```
controller/
└── IndicatorController.kt             ← @RestController GET /api/indicators (frontend·DSL·StrategyBuilder 호출)
```

**중요**: api는 IndicatorEngine 직접 의존 안 함 — REST → consumer 호출 경로는 **api 모듈 내부에 IndicatorEngine bean 등록** (consumer와 동일 sources 등록). master §5-2 표 — Indicator의 api 책임은 "캐시 결과 REST" 명시. 즉 IndicatorEngine 자체는 api·consumer 양쪽에 등록되며 둘 다 같은 sources 사용 (sources는 stateless — multi-instance 등록 안전).

**대안 검토 (post-MVP)**:
- (a) api → consumer Kafka 호출 — overhead 큼, 1차 폐기
- (b) api·consumer 양쪽에 IndicatorEngine bean 등록 — **채택** (sources stateless + Redis 캐시 공유)
- (c) 별도 indicator-service 모듈 분리 — 모듈 추가 부담, 1차 폐기

### 3-4. batch — 변경 0

batch는 IndicatorEngine 호출 없음. T-BT(W3) 진입 시 백테스트 잡에서 IndicatorEngine 호출 가능 (post-T-IND).

### 3-5. frontend — 차트 overlay (R3 PR-34)

`frontend/src/components/chart/IndicatorOverlay.tsx` (PR-34 R3 일괄):
- LWC v4 `addLineSeries` 또는 `addHistogramSeries` 호출
- IndicatorPicker UI에서 사용자가 RSI·MACD·BB 등 토글 시 fetch
- TanStack Query `useIndicatorSeries(code, name, interval, params)` hook

### 3-6. sub-agent 영역 분배 (메인=strategy-engine·보조=backend-core)

**사용자 사용자 결재 받을 결정**: T-IND 메인 책임은 strategy-engine OR backend-core?

**architect 권장 = strategy-engine 메인**:
- 근거 1: tracks-roadmap §2 T-IND row 담당 칸 = `backend-core+ai-engineer` (당시 v2 시점 — strategy-engine 별도 분리 전). v3.1.0 sub-agent 세분화 후 strategy-engine이 indicator·DSL·signal 영역 메인.
- 근거 2: master §5-2 표 — Indicator·DSL·Signal·Screener·Backtest 모두 strategy-engine 영역 (T-DSL·T-SIG·T-BT·T-SC도 strategy-engine 메인).
- 근거 3: backend-core는 도메인 port·인프라(JPA·Redis·Kafka) 책임 — IndicatorSource port 정의 + Native 5종 + RedisIndicatorCacheAdapter는 backend-core 영역.
- 근거 4: T-PAT(57패턴)도 W2 후속 — 일관되게 strategy-engine 메인으로 가는 게 사용자 인지 흐름 자연스러움.

**보조**: backend-core (port·Native 5종·infrastructure)

**market-data 미참여**: T-W2에서 market-data가 KIS WS·REST·candle 영속 본체 만들었으니, 본 PR-32부터는 candle DB read 의존만. market-data 디스패치 0건.

---

## §4. PR 분할 매트릭스 (R1+R2 통합 + R3 일괄)

### 4-1. PR 시리즈 개요

| PR | 담당 | 분량 | 라운드 | 영역 | 우선순위 |
|---|---|---|---|---|---|
| **PR-32** | architect | M | spec only(R0) | docs(`_workspace/specs/` 4 파일 + CHANGELOG 슬롯 사전 발행) | **현재** (본 PR) |
| **PR-33a** | strategy-engine | XL | **R1+R2 통합** | consumer indicator/source/kis/ 80지표 + IndicatorEngineConfig + RedisIndicatorCacheAdapter + IndicatorController | **PR-32 머지 후 병렬 1** |
| **PR-33b** | backend-core | M~L | **R1+R2 통합** | domain indicator/* (port·spec·result·engine) + consumer indicator/source/native/ 5종 + (옵션) CrossCheckEngine | **PR-32 머지 후 병렬 1** |
| **PR-34** | frontend + strategy-engine + backend-core | M | R3 일괄 | frontend IndicatorOverlay + IndicatorPicker + 80지표 정확성 fixture 일괄 + 통합 e2e | PR-33a/b 머지 후 |

**라운드 분할 일탈 사유 (룰 3 정합 사전 보고)**:
- 표준 룰 3은 R1(domain port·시그니처) → R2(어댑터·영속) → R3(UI·테스트). 이론상 PR-33b R1 → PR-33a R2 직렬.
- 본 spec은 **R1+R2 통합** 권장 — 80지표 일괄이라 R1만 따로 PR 분리하면 빈 인터페이스 + R2까지 시작 못 함. PR-33b가 domain port + IndicatorEngine + Native 5종 동시 발행, PR-33a가 KIS source 80지표 동시 발행. 양쪽 인터페이스 동결 시점 = 본 spec PR-32 머지.
- `feedback_orchestrator_active_parallel.md` 정합 — "직렬 권장 답습 금지". 본 PR-33은 영역 LOCK 분리(domain/native vs kis/api/cache) + add-only 충돌 0% 사전 검증(§7 충돌 행렬 참조)이라 **완전 병렬 가능**.

### 4-2. 병렬 가능 매트릭스 (PR-33a ⊕ PR-33b)

| 영역 | PR-33a strategy-engine | PR-33b backend-core | 충돌 위험 |
|---|---|---|---|
| domain/indicator/ | (read-only — interface import) | **새 파일 생성** | 0 (PR-33a는 PR-33b 머지 후 import) |
| domain/indicator/port/IndicatorSource.kt | (read-only) | **신규 add-only** | 0 |
| consumer/.../indicator/source/kis/ | **신규 add-only** | (touch 0) | 0 |
| consumer/.../indicator/source/native/ | (touch 0) | **신규 add-only** | 0 |
| consumer/.../indicator/IndicatorEngineConfig.kt | **신규 add-only — 양쪽 source bean inject** | 본 파일 미수정 | **modify-shared 의심** — §7 충돌 행렬 참조 |
| consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt | **신규 add-only** | (touch 0) | 0 |
| api/.../controller/IndicatorController.kt | **신규 add-only** | (touch 0) | 0 |
| application.yml | (touch 0) | (touch 0 — Redis URL 기존 사용) | 0 |
| CHANGELOG.md | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 (ADR 0050 정합) |

**결론**: §7 충돌 행렬에서 IndicatorEngineConfig modify-shared 우려 → **strategy-engine 단독 LOCK** (구현체는 backend-core가 만들지만 Bean 등록은 strategy-engine 책임 — 본 spec §7-2에서 LOCK).

**권장 디스패치 모드**: **완전 병렬 (시나리오 A)** — orchestrator는 본 spec PR-32 머지 즉시 PR-33a·PR-33b 동시 background 발사.

---

## §5. 80지표 multi-output 매트릭스 (ADR 0021 §2-4 정합 — 본 spec LOCK)

`_workspace/strategy/indicator-catalog.md` 80지표 카탈로그 + ADR 0021 §2-4 결과 컬럼 일관성 결정에 따라, 각 지표의 `IndicatorPoint.values: Map<String, BigDecimal>` key 매핑을 본 spec에서 LOCK한다. sub-agent는 이 매트릭스를 자체 추론 없이 그대로 구현 의무.

### 5-1. Single-output 지표 (60개) — values = `{name: value}`

```
ma → {ma}                ema → {ema}              wma → {wma}             hma → {hma}
dema → {dema}            tema → {tema}            trima → {trima}         lwma → {lwma}
t3 → {t3}                zlema → {zlema}          kama → {kama}           alma → {alma}
frama → {frama}          vidya → {vidya}          ichimoku_tenkan → {tenkan}
ichimoku_kijun → {kijun} sar → {sar}              regression_slope → {slope}
rsi → {rsi}              stochastic_k → {k}       stochrsi → {stochrsi}   cci → {cci}
williams_r → {wr}        roc → {roc}              momentum → {momentum}   apo → {apo}
ppo → {ppo}              cmo → {cmo}              ao → {ao}               trix → {trix}
tsi → {tsi}              rvi → {rvi}              dpo → {dpo}             kst → {kst}
coppock → {coppock}      schaff → {schaff}        fisher → {fisher}
obv → {obv}              volume_ma → {volume_ma}  vwap → {vwap}           mfi → {mfi}
cmf → {cmf}              cho → {cho}              ad → {ad}               adl → {adl}
force → {force}          vwma → {vwma}            eom → {eom}             kvo → {kvo}
bb_width → {width}       bb_percent → {percent}   atr → {atr}             natr → {natr}
keltner_upper → {upper}  keltner_lower → {lower}  donchian_upper → {upper}
donchian_lower → {lower} chop → {chop}            mass_index → {mass}     augen → {augen}
adx → {adx}              adxr → {adxr}            aroon_up → {up}         aroon_down → {down}
vortex_plus → {plus}     vortex_minus → {minus}   ultosc → {uo}           bop → {bop}
std → {std}              variance → {variance}    disparity → {disparity}
midpoint → {midpoint}    midprice → {midprice}    logr → {logr}
regression_intercept → {intercept}
```

### 5-2. Multi-output 지표 (20개)

| id | values keys | 비고 |
|---|---|---|
| `macd` | `{macd, signal, histogram}` | KIS calc_macd 3-tuple |
| `bb` | `{upper, middle, lower}` | KIS Bollinger Band 3-line |
| `stochastic_d` | `{k, d}` | %K + %D 2-tuple |
| `supertrend` | `{value, direction}` | direction 1.0=up / -1.0=down |
| `pivot` | `{p, r1, r2, r3, s1, s2, s3}` | 7-line standard pivot |

**나머지 single-output 75개 + multi-output 5개 = 80개 합계 정합** (`indicator-catalog.md` 합계와 동일).

**stochrsi**·**stochastic_k** 같은 일부 항목은 single-output(매핑 §5-1 참조). KIS `indicators.py` raw 함수 시그니처가 결정 권위 — sub-agent PR-33a는 raw fetch 후 함수당 return type 직접 확인 의무 (자체 추론 0).

### 5-3. paramsHash 계산 정책

Redis 캐시 key의 `{paramsHash}`는 다음 정규화로 결정 (sub-agent LOCK):

```kotlin
fun IndicatorSpec.paramsHash(): String {
    val sorted = params.entries.sortedBy { it.key }
        .joinToString("|") { "${it.key}=${it.value}" }
    return MessageDigest.getInstance("SHA-256")
        .digest(sorted.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)  // 16자리 hex prefix (충돌 무시할 수준 + key 길이 절약)
}
```

- 정규화: 키 알파벳 정렬 + `{key}={value}` join + SHA-256 + 16자리 hex prefix
- BigDecimal 값은 `toPlainString()` 결과 사용 (scientific notation 회피)
- 동일 paramsMap → 동일 hash LOCK (캐시 hit 보장)

---

## §6. 데이터 흐름 + 인터페이스 동결

### 6-1. 호출 시퀀스 (사용자 차트 RSI overlay 토글 → 응답)

```
[frontend] IndicatorPicker "RSI(14)" 토글
   │
   ▼
[frontend] useIndicatorSeries('005930', 'rsi', '1d', {period: 14}, fromDate, toDate)
   │ HTTP GET /api/indicators?code=005930&name=rsi&interval=1d&params=period=14&from=...&to=...
   ▼
[api] IndicatorController.get(...)
   │
   ▼
[api/consumer] IndicatorEngine.calculate(IndicatorSpec("rsi", {period:14}), candles)
   │ 1. RedisIndicatorCacheAdapter.get(cacheKey) — hit 시 즉시 반환
   │ 2. miss 시 sources.filter { supports } → minByOrNull priority
   │    → [native, kis-strategy-builder] supports → native 선택 (priority=50)
   │ 3. NativeIndicatorSource.calculate(spec, candles) — 자체 RSI 계산
   │ 4. RedisIndicatorCacheAdapter.put(cacheKey, result, ttl=86400s)
   ▼
[domain] IndicatorResult(spec, series, source="native") 반환
   │
   ▼
[frontend] LWC chart.addLineSeries({color: 'orange', lineWidth: 1}) + setData(series)
```

### 6-2. domain port 시그니처 LOCK (PR-33b 책임)

```kotlin
// domain/.../indicator/port/IndicatorSource.kt
package kr.spdaewoon.magicjar.domain.indicator.port

import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * Indicator Source plug-in (ADR 0021 §2-1).
 * 1차 구현체: KisStrategyBuilderSource(priority=100), NativeIndicatorSource(priority=50).
 */
interface IndicatorSource {
    val name: String        // "kis-strategy-builder" · "native" · "ta-lib" · "krx"
    val priority: Int       // 낮을수록 우선

    fun supports(spec: IndicatorSpec): Boolean

    /** @throws IndicatorException.InsufficientCandlesException candles.size < spec required period */
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult
}

// domain/.../indicator/IndicatorSpec.kt
data class IndicatorSpec(
    val name: String,
    val params: Map<String, Any>,
)

// domain/.../indicator/IndicatorResult.kt
data class IndicatorResult(
    val spec: IndicatorSpec,
    val series: List<IndicatorPoint>,
    val source: String,
)

// domain/.../indicator/IndicatorPoint.kt
import kotlinx.datetime.Instant
import java.math.BigDecimal
data class IndicatorPoint(
    val time: Instant,
    val values: Map<String, BigDecimal>,
)

// domain/.../indicator/IndicatorEngine.kt
class IndicatorEngine(private val sources: List<IndicatorSource>) {
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult {
        val source = sources
            .filter { it.supports(spec) }
            .minByOrNull { it.priority }
            ?: throw IndicatorException.UnsupportedSpecException(spec.name)
        return source.calculate(spec, candles)
    }

    fun supportedNames(): Set<String> =
        sources.flatMap { src -> /* 등록된 spec name 집합 */ emptySet<String>() }.toSet()
        // 구현 옵션: IndicatorCatalog.allSpecs().filter { sources.any { src -> src.supports(it) } }
}

// domain/.../indicator/IndicatorException.kt
sealed class IndicatorException(message: String) : RuntimeException(message) {
    class UnsupportedSpecException(name: String) : IndicatorException("No source supports $name")
    class InsufficientCandlesException(required: Int, actual: Int) :
        IndicatorException("Insufficient candles: required=$required, actual=$actual")
    class CalculationFailureException(spec: IndicatorSpec, cause: Throwable) :
        IndicatorException("Calculation failed for ${spec.name}: ${cause.message}")
}
```

### 6-3. REST endpoint 시그니처 LOCK (PR-33a 책임)

```kotlin
// api/.../controller/IndicatorController.kt
@RestController
@RequestMapping("/api/indicators")
@Tag(name = "Indicators", description = "기술적 지표 80종 (KIS Strategy Builder + magicJar Native source)")
class IndicatorController(private val indicatorEngine: IndicatorEngine, private val candleQueryService: CandleQueryService) {

    @GetMapping
    @Operation(summary = "지표 시계열 계산", description = "80지표 중 하나를 candle 시계열 위에서 계산하여 반환합니다. 결과는 Redis 5분(분봉)/1일(일봉) 캐시. 미지원 지표는 400 에러.")
    fun getIndicator(
        @RequestParam @Parameter(description = "종목코드(6자리)", example = "005930") code: String,
        @RequestParam @Parameter(description = "지표 ID (예: rsi · macd · bb · adx · ...)", example = "rsi") name: String,
        @RequestParam @Parameter(description = "캔들 interval", example = "1d") interval: String,
        @RequestParam(required = false) @Parameter(description = "지표 파라미터 (key=value 쉼표 구분, 예: period=14)") params: String?,
        @RequestParam(required = false) from: String?,  // ISO Instant
        @RequestParam(required = false) to: String?,    // ISO Instant
        @RequestParam(required = false, defaultValue = "false") adjusted: Boolean,  // T-CA1 정합
    ): IndicatorResultDto = ...
}

// data class IndicatorResultDto — 직렬화 친화 (BigDecimal → String, Instant → ISO String)
data class IndicatorResultDto(
    val spec: SpecDto,
    val series: List<PointDto>,
    val source: String,
) {
    data class SpecDto(val name: String, val params: Map<String, String>)
    data class PointDto(val time: String, val values: Map<String, String>)
}
```

### 6-4. Redis 캐시 키 LOCK

| 키 패턴 | TTL | 작성자 | 형식 |
|---|---|---|---|
| `indicator:cache:{code}:{interval}:{name}:{paramsHash}` | 분봉 300s / 일봉 86400s | PR-33a `RedisIndicatorCacheAdapter` | JSON `IndicatorResultDto` |

**TTL 매트릭스**:
- `1m·5m·15m·30m·1h` → 300s (5분)
- `1d·1wk·1mo·1y` → 86400s (1일)
- `90m` → 300s (분봉 분류) — Yahoo 매트릭스 정합

**evict 시점**: candle 영속 시 자동 invalidate 안 함 (TTL 만료 자연 갱신). 사용자 명시 무효화 필요 시 `DELETE /api/indicators/cache?code=...` (post-MVP).

### 6-5. Kafka 영향

본 PR-32·PR-33은 Kafka 영향 0건. T-IND는 in-process 동기 호출. (post-MVP `indicator.drift.v1` topic은 ADR 0021 §2-6 cross-check 활성 시 — 옵션, 본 PR 비범위)

### 6-6. SoT 변경 시 처리

본 spec(`pr-32-w2-t-ind-design.md` + `pr-32-dispatch-spec.md` + `pr-32-conflict-matrix.md`)이 머지된 후 양쪽 sub-agent 진행 중에 시그니처 변경 필요 시:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-32 작업 중엔 spec 변경 0건 의무.

---

## §7. 충돌 행렬 (modify-shared 사전 점검)

별도 `pr-32-conflict-matrix.md` 참조 (요약):

| 파일 | PR-33a strategy-engine | PR-33b backend-core | 결론 |
|---|---|---|---|
| `domain/.../indicator/*.kt` | (read-only) | **신규 add-only** | 0 |
| `consumer/.../indicator/source/kis/*` | **신규 add-only** | (touch 0) | 0 |
| `consumer/.../indicator/source/native/*` | (touch 0) | **신규 add-only** | 0 |
| `consumer/.../indicator/IndicatorEngineConfig.kt` | **신규 add-only — strategy-engine 단독 LOCK** | (import 가능 BUT 작성 0) | 0 — strategy-engine 단독 LOCK |
| `consumer/.../infrastructure/cache/RedisIndicatorCacheAdapter.kt` | **신규 add-only** | (touch 0) | 0 |
| `api/.../controller/IndicatorController.kt` | **신규 add-only** | (touch 0) | 0 |
| `application.yml` (3 모듈) | (touch 0) | (touch 0) | 0 |
| `CHANGELOG.md` | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 (ADR 0050 정합) |

**결론**: **시나리오 A 적극 병렬 권장** — modify-shared 0건, add-only 100%. orchestrator는 PR-32 머지 즉시 PR-33a·PR-33b 동시 background 발사.

---

## §8. 함정·운영 가드 (sub-agent 의무 인지)

### 8-1. ADR 0051 — Repository default fun 함정

본 PR-32에서는 **`IndicatorCacheRepository` interface 신설하지 않음** — Redis 어댑터는 `RedisIndicatorCacheAdapter` 단일 클래스(StringRedisTemplate 직접 사용). JPA Repository 0건이라 ADR 0051 함정 발동 0건.

단 sub-agent PR-33a는 (만약 indicator metric audit 필요 시 — 본 PR 비범위) Repository interface 추가 시 헌법 §6 §6 정독 의무.

### 8-2. KIS-first raw fetch (헌법 §3)

PR-33a strategy-engine sub-agent는 80지표 구현 전 다음 raw fetch 의무:

```bash
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py > /tmp/kis-indicators.py
wc -l /tmp/kis-indicators.py  # 약 1500~2000 라인 추정
```

각 80개 함수 구현 시:
- 함수명 → Kotlin 함수명 (`calc_ma` → `calculateMa`)
- param name·default 값 → 1:1 정합 (`indicator-catalog.md` default params와 일치)
- multi-output return tuple → §5-2 매트릭스 LOCK
- 자체 추론 0 — 애매한 경우 raw 출처 줄번호 인용 KDoc

### 8-3. BigDecimal 정밀도

ADR 0021 §2-1 `IndicatorPoint.values: Map<String, BigDecimal>` — Float·Double 사용 금지 (정확성 fixture drift 0.001% 정합).

KIS `indicators.py`는 Python float (IEEE 754 double) — Kotlin port 시 `BigDecimal` 사용 + RoundingMode = HALF_EVEN (banker's rounding) + scale=8 (소수점 8자리). 정확성 fixture drift 0.001% 미만은 8자리면 충분.

### 8-4. Insufficient candles 처리

각 source `calculate()` 호출 시 candles.size < required period (예: rsi(period=14)는 candles.size ≥ 14 필요) → `IndicatorException.InsufficientCandlesException(14, actual)` throw. REST는 400 + 에러 메시지 응답.

### 8-5. 캐시 stampede 방지

동일 paramsHash + cache miss 동시 다중 호출 시 stampede 가능성 — 1차 무시 (사용자 1명 + 차트 overlay 토글 빈도 낮음). post-MVP: Redis SETNX 분산락 + waitFor pattern. 본 PR 비범위.

### 8-6. T-CA1 adjusted candle 사용 옵션

REST `?adjusted=true` 옵션 시 `candle.close_adjusted` 사용 (raw 대신). default = false (raw). 백테스트(T-BT)는 항상 adjusted=true. 사용자 차트는 default raw 표시 (사용자 인지 흐름 자연 — 화면 표시 가격과 일치).

### 8-7. KisStrategyBuilderSource priority=100 vs Native=50

ADR 0021 §2-3에서 Native가 priority=50 (KIS=100보다 낮은 숫자 = 우선). 즉 **Native 5종이 활성 상태면 KIS의 동일 지표는 호출 안 됨**. 의도적 설계 — 자체 구현이 신뢰. 단 1차 구현 시 Native 정확성을 KIS와 cross-check 검증 권장 (PR-34 R3 일괄에서 5종 fixture 비교).

### 8-8. 빌드 영향 + dependency

- domain 모듈 — JPA·Spring 의존 0 유지 (kotlinx-datetime, java.math.BigDecimal만)
- consumer — 기존 Redis dep 재사용. 신규 dep 0건
- api — 기존 Spring Web + OpenAPI 재사용. 신규 dep 0건

### 8-9. KIS Rate Limit 영향 0

T-IND는 candle DB read 의존 — KIS REST 호출 0건. KIS Rate Limit 영향 없음.

---

## §9. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)

> **lens**: "자동매매에 필요한가? v1/v2 잔재 아닌가?"

| 항목 | 판정 | 근거 |
|---|---|---|
| **80지표 일괄 vs 30개 우선** | **80 일괄 정합** | tracks-roadmap §7 결정 #2 사용자 명시 = "80개 일괄". ADR 0020 §3-2 §1차 구현 = 80개. v1 시점은 RSI 단일이었으나 v2 비전 정합으로 폐기. |
| **Source 추상화 의무** | **정합** | ADR 0021 §1 사용자 명시 = "KIS 외에서도 받을 수 있게". 단순 KIS port만 하면 v1 회귀. |
| **NativeIndicatorSource 5종 1차** | **정합** | ADR 0021 §2-3 1차 구현 = Native 5종 (SMA·EMA·RSI·MACD·BB). KIS 검증 + 자체 우선 사용 의도. |
| **Redis 캐시** | **정합** | master §6-3 indicator:cache 표 정합. quant trading 본체에 차트 overlay 5분 캐시는 사용자 차트 응답성에 직결. |
| **REST 단일 endpoint** | **정합** | T-DSL·T-SB·차트·screener 모두 동일 endpoint 사용. v1 시점 indicator endpoint 분산 회귀 차단. |
| **AI 호출 0** | **정합** | 룰 5 + ADR 0023 정합. T-IND는 W2 — LLM provider import 0건. |
| **80지표 정확성 R3 일괄** | **정합** | 룰 4 정합. R3 PR-34에서 80개 fixture 일괄. PR-33은 5종만 단위 테스트 (정확성 검증 동력 보장). |

**결론**: T-IND spec은 자동매매 본체 quant trading의 신경계 — v1/v2 잔재 0건. 80지표는 룰 본체(W2-W3) 진정한 first-class citizen. ADR 0020·0021 결정 정합 100%.

**post-MVP 후속 (1차 비범위)**:
- TaLibSource (JNI 또는 HTTP) — ADR 0021 §2-3 표 priority=200, post-W2
- KrxSource (KRX 공식) — 동 표 priority=300, post-MVP
- 사용자 정의 지표 (Native source 확장) — post-MVP
- cross-check 정밀화 (drift Kafka publish + dashboard) — post-MVP

---

## §10. 사용자 결재 받을 결정 사항 (PR-32 본문에 명시)

본 spec PR 머지 전 사용자가 결재할 항목:

| # | 항목 | architect 권장 | 영향 |
|---|---|---|---|
| 1 | **메인 sub-agent 결정** | strategy-engine 메인 + backend-core 보조 (§3-6 정합) | PR-33 디스패치 분배 |
| 2 | **NativeIndicatorSource 1차 5종 OK?** | SMA·EMA·RSI·MACD·BB 5종 (ADR 0021 §2-3 정합 + 사용자 차트 overlay 빈도 최상위) | PR-33b 분량 |
| 3 | **R1+R2 통합 권장 OK?** | 통합 (80지표 일괄이라 분리 비효율 + add-only 충돌 0%) | PR 개수 |
| 4 | **80지표 정확성 검증 시점** | R3 PR-34 일괄 (5종은 PR-33 단위 테스트 의무) | 룰 4 정합 |
| 5 | **MAGICJAR_INDICATOR_CROSSCHECK default off** | 채택 (ADR 0021 §2-6) | PR-33b 옵션 분량 |
| 6 | **adjusted candle default** | default=false (raw) — UI 표시 가격과 일치 | REST endpoint 디폴트 |
| 7 | **신규 ADR 0건 OK?** | OK — ADR 0021 §2 implementation 명세로 충분. 신규 ADR 발행 시 fitness gap 위반 risk (헌법 §3 정독 후 판단) | ADR 인덱스 |

**사용자 결재 후**: orchestrator가 본 spec PR-32 머지 → PR-33a·PR-33b 동시 background 발사.

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-32 T-IND 설계 가이드 v1 발행. ADR 0021/0020 implementation 명세 풀어냄 — 신규 ADR 0건. domain/indicator/* port 7개 + consumer/.../indicator/source/{kis,native}/ 분할 + RedisIndicatorCacheAdapter + IndicatorController REST endpoint LOCK. 80지표 multi-output 매트릭스 (single 75 + multi 5 = 80) §5에 LOCK. R1+R2 통합 권장(80일괄 비효율 회피) + R3 PR-34 일괄. 충돌 행렬 시나리오 A 적극 병렬 권장 (modify-shared 0건). fitness gap 검증 — v1/v2 잔재 0건. (architect, opus 4.7 1M) |
