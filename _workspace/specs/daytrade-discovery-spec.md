# spec — 단타 발굴(Discovery) 레이어

- 작성: 2026-06-09, architect
- 상태: **제안 (사용자 결재됨 — 발굴 레이어 구축 결재. 단 본 spec §7 "사용자 결정 필요" 2건은 R 진입 전 확인 필요)**
- 출발점: `_workspace/strategy/2026-06-08-daytrade-screener-design.md` (결재된 strategy-engine 제안서)
- 동반 ADR: **ADR 0117** (발굴 레이어 신설 — 본 spec과 1:1)
- 데이터 계약 LOCK 의무: 본 §3·§4 계약은 R1~R3 sub-agent가 그대로 따른다. 임의 변경 금지.

---

## §1. 방향성 가드 분석 (산출물 1 — `.claude/skills/magicjar-direction-guard`)

```text
기존 방향: 스크리너 = "사용자 DSL 전략(Strategy.definition.dsl.buyCondition)을 universe에 적용하는 필터".
  ScreenerEngineImpl.screen(criteria) — strategyId 필수, BUY 시그널만 ScreenerMatch 수집 (ScreenerEngineImpl.kt:90,178).
  universe = instrument 영속 + candle 영속 reuse, KIS REST 호출 0 LOCK (ScreenerEngineImpl.kt:50-51 KDoc).
  실시간 수요는 owner 분리 — DETAIL/STRATEGY/HOLDING/PENDING_BUY (RealtimeSubscriptionRouter.kt:249-259).
  STRATEGY owner는 priority 40, TTL 30분, watchlist 자동 삽입 0 (RealtimeSubscriptionRouter.kt:45,258).
  체결강도는 시스템 어디에도 없음 (grep trade_intensity/cttr → 0건). KIS WS H0STCNT0 index 18(CTTR)을 파서가 버림 (KisWsMessageParser.parseH0Stcnt0 — IDX 0·1·2·3·4·5·12·33만 추출).
  빌트인 후보 인프라는 이미 존재 — BuiltinResearchCandidates 18종 + V74 strategy_research_candidate 시드 + ResearchCandidateProvider 포트 (ResearchCandidateDefinition.kt:46-289).
  단, /api/strategy-research/ai-discovery 는 Claude(LLM) 기반 발굴 (StrategyResearchAiDiscoveryService.kt:238 "Claude에게 신규 발굴 프리셋").

변경 의도: "전략을 미리 작성하지 않아도 시스템이 오늘 단타 후보 TOP N을 수치로 제시"하는 Discovery 레이어 추가.
  (1) 체결강도(CTTR) KIS raw 흡수 (계산 신설 0). (2) 시스템 제공 빌트인 발굴 프리셋(룰 기반). (3) 발굴 결과 → STRATEGY owner WS 수요 연결. (4) 단타 백테스트 alpha 측정 + 과최적화 탐지.

방향성 영향: 유지 (보수적 확장 — 전환 아님). 근거 §1-1.
확인할 리스크: 데이터(tick-only CTTR은 candle 백테스트 재현 불가 §1-4) · 자본흐름(발굴=후보 출발점, 매매 결정권 0 §1-2) · API(LLM ai-discovery와 path/개념 충돌 §1-3) · UX(발굴≠watchlist 자동삽입 §1-2) · 운영(STRATEGY WS slot·KIS rate limit §1-5).
```

### 1-1. 핵심 질문 — 충돌인가 확장인가? → **확장 (충돌 아님)**

기존 스크리너(`ScreenerEngineImpl.screen`)는 `criteria.strategyId`를 **필수**로 받아 사용자가 미리 저장한 Strategy의 `buyCondition`을 universe에 평가한다 (`ScreenerEngineImpl.kt:90`). 즉 "사용자가 전략을 먼저 작성해야" 동작한다.

발굴(Discovery) 레이어는 "사용자가 전략을 작성하지 않아도 시스템 빌트인 룰로 오늘 후보를 제시"한다. 이는 기존 필터를 **대체하지 않고**, 기존 엔진을 **시스템 제공 빌트인 전략을 strategyId로 주입하는 방식으로 재사용**하면 충돌이 없다.

결정적으로, 시스템에는 이미 **이 패턴의 인프라가 존재**한다 — `BuiltinResearchCandidates` 18종 빌트인 후보 + `entryDsl` + `isBuiltin` 플래그 + V74 `strategy_research_candidate` 테이블 + `ResearchCandidateProvider` 포트 (`ResearchCandidateDefinition.kt:17-47`). 발굴 레이어는 **신규 도메인/테이블을 신설하지 않고 이 빌트인 후보 인프라를 단타 발굴 프리셋으로 확장**한다. → 보수적 보강. 방향 유지.

### 1-2. owner 분리 가드 + 자본 흐름 — 매매 결정권 0 유지

- **watchlist 자동 삽입 금지**: 발굴된 후보는 `RealtimeDemandOwner.STRATEGY`로만 WS 수요 등록 (`RealtimeSubscriptionRouter.request(owner=STRATEGY, ...)`). 발굴 화면 진입/결과 표시가 `watchlistRepository`에 write하지 않는다 — CLAUDE.md "전략 후보를 watchlist에 자동 삽입하지 않는다" 명문 정합. R2 frontend·strategy-engine은 발굴 결과를 watchlist API로 라우팅 금지(코드 리뷰 게이트).
- **자본 흐름**: 발굴은 후보 **출발점**이지 매매 결정이 아니다. 발굴 결과 DTO는 주문 트리거 필드를 갖지 않으며(§3-R2), 자동매매 결정권 0. 사용자가 후보를 보고 검증·저장·스크리닝을 실행하는 진입점. CLAUDE.md FVG/OrderBlock 가드 "전략 후보 프리셋은 자동매매 결정을 대신하지 않는다" 1:1 정합.
- **STRATEGY owner TTL 정합**: 발굴 후보 WS 수요는 `STRATEGY` owner default TTL 30분 (`RealtimeSubscriptionRouter.kt:45 strategyTtlMs`). 발굴 재실행 시 TTL refresh, 미접속 시 자연 evict (`evictExpiredDemands` 60초 cron). watchlist·HOLDING과 priority 분리(40 vs 0~10)되어 보유/상세 구독을 밀어내지 않음 (`findEvictionCandidateLocked` priority 비교 LOCK).

### 1-3. 빌트인 발굴 프리셋 vs 사용자 DSL 전략 — 구분·공존

발굴 setup 프리셋은 **시스템 제공 빌트인**(`isBuiltin=true`)이고, 사용자 DSL 전략(`Strategy` 테이블)과 다른 종류다. 공존 설계:

| 축 | 사용자 DSL 전략 (`Strategy`) | 빌트인 발굴 프리셋 |
|---|---|---|
| 소유 | userId 격리 (`strategy.userId`) | 시스템 (`isBuiltin=true`, userId 비귀속) |
| 작성 | 사용자가 StrategyBuilder로 작성 | architect/strategy-engine이 코드 권위로 정의 |
| 저장 | `strategy` 테이블 | `strategy_research_candidate` 테이블 (V74 시드 — **기존 재사용**) |
| entryDsl | 사용자 DSL | `ResearchCandidateDefinition.entryDsl` (§3-R2 단타 프리셋 add) |
| 발굴 진입 | 사용자가 전략 선택 후 screen | 시스템 빌트인을 즉시 평가 |

→ 발굴 프리셋은 **기존 `BuiltinResearchCandidates` + V74 테이블에 단타 setup 프리셋을 add-only**한다 (신규 테이블 0). 사용자 DSL 전략과 별도 테이블에 공존 — 충돌 0.

**개념·path 충돌 회피 (중요)**: `/api/strategy-research/ai-discovery`(`StrategyResearchController.kt:126`)는 **Claude(LLM) 기반 발굴**(`StrategyResearchAiDiscoveryService.kt:238`)이다. 본 발굴 레이어는 **룰 기반(KIS raw + DSL, LLM 호출 0)**이다. 개념이 다르므로 path를 분리한다 — `discover`/`ai-discovery` 단어 재사용 금지. 본 레이어는 `/api/screener/daytrade-discover`(룰 기반 단타 발굴, AI 0)로 명명한다 (§3-R2).

### 1-4. 데이터 흐름 전/후 + tick-only 백테스트 한계

- **장전 setup**(일봉): 기존 ScreenerEngine 경로 100% — candle 영속 reuse, KIS 호출 0. 변경 0.
- **장중 확인**(tick): KIS WS H0STCNT0 tick의 CTTR(index 18)·ACML_TR_PBMN(index 14)을 흡수. 이는 candle에 영속되지 않는 tick-only 값.
- **백테스트 한계 (정직 LOCK)**: CTTR은 과거 candle에 없다 → **체결강도 조건은 백테스트로 재현 불가**. 따라서 수익 검증은 2층 — A층(candle 표현 가능 필터: 갭·RVOL·거래대금만 백테스트) + B층(체결강도 incremental은 MOCK 페이퍼 A/B). 제안서 §3.3 정합. R3는 A층만 다룬다.

### 1-5. 운영 부하 비교

- KIS rate limit: 장전 setup은 candle 영속 reuse라 KIS 호출 0 (기존과 동일). 장중 tick은 이미 구독 중인 WS stream에서 CTTR 추출(추가 호출 0 — 파서가 버리던 필드를 읽을 뿐). → KIS 호출 증가 0.
- WS slot: 발굴 후보를 STRATEGY owner로 등록 시 `PRICE_TICK_MAX` 슬롯 경합. priority 40으로 watchlist/HOLDING 뒤이므로 보유 구독 안 밀어냄. 발굴 후보 N은 limit으로 제한(§3-R2 default 상한).

---

## §2. 자원 audit (산출물 3 — main 점유 검증, ADR 0102 정합)

검증 시점 main = `f0df035b` (v3.12.1).

| 자원 | 신규 점유 | main 충돌 검증 | 결론 |
|---|---|---|---|
| **Flyway** | (선택) V75 — 단타 빌트인 setup 프리셋 seed | 최신 = V74 (`strategy_research_candidate` 시드). 다음 가용 = **V75** | 충돌 0. 단 §7-2 — 프리셋을 코드 권위(`BuiltinResearchCandidates` add)로만 둘지 V75 시드까지 할지 결정 필요 |
| **REST path** | `POST /api/screener/daytrade-discover` (R2) · `BacktestRequest`/`BacktestPerformance` 확장 (R3, 신규 path 0) | `/api/screener/{evaluate,runs,runs/{id}}` 점유 (`ScreenerController.kt`). `discover`는 `/api/strategy-research/ai-discovery`가 LLM 용도로 점유 → **`daytrade-discover` 명명으로 회피** | 충돌 0 (명명 분리) |
| **Redis key** | `screener:discover:cache:<presetKey>:<filterHash>` (R2, 선택 캐시) | 기존 `screener:cache:*`·`screener:lock:*` (`RedisScreenerCacheAdapterTest.kt:74,135`) | prefix `screener:discover:` 분리 — 충돌 0 |
| **Kafka topic** | 0 (발굴은 inline 동기 — 기존 ScreenerEngine A-1 패턴) | — | 신규 0 |
| **STOMP destination** | 0 (발굴 결과는 REST 응답. WS 수요 등록은 기존 RealtimeSubscriptionRouter 경유) | — | 신규 0 |
| **ENV** | (선택) `MAGICJAR_DAYTRADE_DISCOVERY_ENABLED`(default true) · `MAGICJAR_DAYTRADE_TRADE_INTENSITY_FALLBACK`(default true — CTTR 결손 시 19·20 비) | — | add-only 충돌 0 |
| **DSL 식별자** | `trade_intensity` (R1, add-only) | `ALLOWED_IDENTIFIERS` set (`KisYamlDslParser.kt:549-608`) — `trade_intensity` 미존재 | add-only 하위호환 충돌 0 |
| **TickEvent 필드** | `tradeIntensity: BigDecimal?` · `turnoverAccum: BigDecimal?` (R1, add-only nullable) | `TickEvent.kt:20-33` — 두 필드 미존재 | add-only 충돌 0 |

---

## §3. 데이터 계약 LOCK (산출물 2 — 가장 중요, R1~R3 그대로 따름)

### R1 — 체결강도 흡수 계약 (market-data + strategy-engine)

**KIS-first raw 검증 완료** (헌법 §3 — `open-trading-api/.../domestic_stock_functions_ws.py` `ccnl_krx()` columns 배열, 2026-06-09 raw fetch):

| index | 필드 | 의미 | R1 흡수 |
|---|---|---|---|
| 14 | `ACML_TR_PBMN` | 누적거래대금(원) | ✅ → `TickEvent.turnoverAccum` |
| 18 | `CTTR` | 체결강도 (KIS 계산 완료값, 매수/매도 체결량×100) | ✅ → `TickEvent.tradeIntensity` (1순위) |
| 19 | `SELN_CNTG_SMTN` | 총매도체결수량 | ✅ fallback 분모 (CTTR 결손 시) |
| 20 | `SHNU_CNTG_SMTN` | 총매수체결수량 | ✅ fallback 분자 |

**계약 — TickEvent 확장 (`domain/.../market/tick/TickEvent.kt`, add-only nullable)**:
```kotlin
/** 체결강도 (KIS H0STCNT0 [18] CTTR — 매수체결량/매도체결량×100). polling source 미제공 → null */
val tradeIntensity: BigDecimal? = null,
/** 장중 누적거래대금 원 (KIS H0STCNT0 [14] ACML_TR_PBMN). polling source 미제공 → null */
val turnoverAccum: BigDecimal? = null,
```
- 타입 = `BigDecimal?` (기존 `priceChange`/`changeRate` 패턴 1:1 — `TickEvent.kt:27-32`). nullable 이유: polling source(`WatchlistPollingWorker`)·NXT 일부 미제공.

**계약 — KisWsMessageParser (`consumer/.../ws/KisWsMessageParser.kt`)**:
- IDX 상수 add: `IDX_ACML_TR_PBMN = 14` · `IDX_CTTR = 18` · `IDX_SELN_CNTG_SMTN = 19` · `IDX_SHNU_CNTG_SMTN = 20` (KisWsMessageParser.kt:75-82 패턴).
- `parseH0Stcnt0`(line 163)·`parseH0Nxcnt0`(NXT, 동일 인덱스 구조) 둘 다 흡수.
- **필드 게이트 상향**: 현 `fields.size < 34` 게이트(line 169)는 index 18·19·20 추출 시 부족 가능 → **`< 21` 보장 + 결손 시 해당 필드만 null**(전체 reject 금지 — 하위호환). 즉 `fields.getOrNull(IDX_CTTR)?.trim()?.ifBlank{null}?.toBigDecimalOrNull()` 패턴(line 188-196과 동일 graceful).
- `tradeIntensity` 결정: `CTTR(18)` 우선. CTTR blank/0 이고 fallback ENV ON이면 `SHNU_CNTG_SMTN(20) / SELN_CNTG_SMTN(19) × 100` (분모 0 시 상한 200.0). KDoc에 raw URL 인용 의무(헌법 §3).
- KIS-first raw URL: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py`

**계약 — DSL 식별자 (`strategy-engine/.../KisYamlDslParser.kt`)**:
- `ALLOWED_IDENTIFIERS` set에 `add("trade_intensity")` (line 549-608 buildSet, "캔들 직접 접근" 인접 — tick 컨텍스트 특수 케이스). 식별자명 LOCK = **`trade_intensity`** (사용자 직관 "체결강도"의 영문, `cttr`은 KIS 약어라 사용자 가독성 낮음 → `trade_intensity` 채택). README/카탈로그에 alias로 `cttr` 병기 가능하나 1차 식별자는 `trade_intensity` 단일.
- **평가 컨텍스트 주입**: `trade_intensity`는 candle slice에 없는 tick-only 값 → `StrategyEvaluatorPort.evaluate`의 평가 컨텍스트에 tick snapshot 동반 필요. **R1 1차 LOCK**: DSL 확장은 식별자 화이트리스트 등록 + 평가기에서 tick snapshot 부재 시 graceful(식별자 미해결 → 조건 false 처리, throw 금지). tick snapshot 실제 주입 배선은 R2 장중 발굴 경로에서. (R1은 "식별자가 화이트리스트 통과 + 백테스트(candle-only) 경로에서 안전하게 false")
- **자체 추론 0 의무**: index·필드명은 위 raw fetch 결과만 사용. CTTR 정의(매수/매도 체결량 비×100)는 KIS HTS "체결강도"와 동일 — 임의 산식 금지.

### R2 — 단타 발굴 스크리너 계약 (strategy-engine + frontend + qa)

**REST endpoint 시그니처 (신규)**:
```
POST /api/screener/daytrade-discover
  (룰 기반 단타 발굴 — LLM 호출 0. ai-discovery(LLM)와 명확히 구분)
```
- request DTO `DaytradeDiscoverRequest`:
  - `userId: Long` (multi-tenant 격리 — 빌트인 프리셋 평가도 userId 검증; 단 프리셋 자체는 시스템 소유)
  - `presetKey: String?` (null이면 default 단타 setup 프리셋. 빌트인 프리셋 key — §아래 정의 위치)
  - `filters: ScreenerFilterDto?` (기존 `ScreenerFilter` 재사용 — KOSPI+KOSDAQ active STOCK)
  - `interval: String = "1d"` (장전 setup = 일봉. ScreenerEvaluateRequest.kt:31 패턴 정합)
  - `limit: Int = 30` (발굴 TOP N — default 30, 1~100. 기존 screener 500보다 낮음: 발굴은 압축된 후보 풀)
- response DTO `DaytradeDiscoverResultDto`:
  - 기존 `ScreenerResultDto` 형태 재사용 + 발굴 메타(`presetKey`·`presetTitle`·`isBuiltin=true`).
  - `matches`: 각 후보 = instrumentCode·instrumentName·rank·score·breakdown·reason (기존 `ScreenerMatch` 1:1).
  - **주문 트리거 필드 0** (발굴=후보 출발점, 매매 결정권 0 LOCK — §1-2).

**빌트인 발굴 프리셋 정의 위치 (LOCK)**:
- `domain/.../strategyresearch/ResearchCandidateDefinition.kt` 의 `BuiltinResearchCandidates.DEFINITIONS`에 **단타 setup 프리셋 add-only** (기존 18종 + 단타 N종). 또는 별도 `BuiltinDaytradeSetups` provider 신설(같은 `ResearchCandidateProvider` 포트 구현) — strategy-engine 재량. **신규 테이블 0** (V74 `strategy_research_candidate` 재사용 또는 코드 권위).
- 단타 setup DSL 예시 (제안서 §2.1, 전부 기존 화이트리스트 내):
  `(close > 2000) and (atr(14)/close > 0.015) and (atr(14)/close < 0.06) and (close * volume > 10000000000) and (volume > 1.5 * volume_ma(20))`
- **`trade_intensity`는 장전 setup 프리셋(일봉)에 넣지 않는다** (tick-only — 일봉 평가 시 false). 장중 확인 경로에서만.

**발굴 결과 → STRATEGY owner WS 수요 연결 계약**:
- 발굴 응답을 받은 frontend가 사용자 액션(장중 감시 시작)을 트리거하면, 후보 code들을 `RealtimeDemandOwner.STRATEGY` 수요로 등록.
- 연결 경로: 기존 STRATEGY owner 등록 API/STOMP 경유 (market-data와 R2 협의 — 신규 owner enum 0, 기존 `RealtimeSubscriptionRouter.request(STRATEGY, ownerId="daytrade-discover:<presetKey>", code, ttl)`).
- **watchlist write 금지 LOCK** (§1-2). ownerId는 발굴 세션 식별자 — 단순 합집합 방지.

### R3 — 백테스트 + 과최적화 탐지 계약 (strategy-engine)

**과최적화 탐지 — IS/OOS 분할 + 파라미터 민감도 (BacktestRequest/BacktestPerformance 확장, add-only)**:

`BacktestRequest` 확장 (`domain/.../backtest/BacktestRequest.kt`, add-only nullable — 기본 null이면 현 단일 run 동작 무변경):
```kotlin
/** IS/OOS 분할 — null이면 단일 run(현행). 지정 시 from~splitDate=IS, splitDate~to=OOS 2 run + degradation 산출 */
val outOfSampleSplit: LocalDate? = null,
```
- 검증 기준 LOCK (제안서 §3.3 + ADR 0056 정합): OOS 기대값(totalReturn) > 0, 거래수 ≥ 100, MDD ≤ 8%. IS 대비 OOS degradation 비율 보고.

`BacktestPerformance` 확장 — 신규 wrapper DTO `OverfitDiagnostics` (BacktestPerformance 자체는 불변, 별도 결과 객체):
```kotlin
data class OverfitDiagnostics(
    val inSample: BacktestPerformance,     // IS 구간 성과
    val outOfSample: BacktestPerformance,  // OOS 구간 성과
    val returnDegradation: BigDecimal,     // (IS.totalReturn - OOS.totalReturn) / IS.totalReturn — 클수록 과최적화
    val sharpeDegradation: BigDecimal?,    // IS/OOS Sharpe 차 (n<30 시 null)
    val passed: Boolean,                   // OOS 기대값>0 && 거래≥100 && MDD≤8% LOCK
)
```

**파라미터 민감도 분석 1종 (입출력 시그니처 LOCK)**:
```kotlin
// 입력: 기준 BacktestRequest + 변동 파라미터 1개(예: volume_ma period) + 변동 그리드
data class SensitivityRequest(
    val base: BacktestRequest,
    val paramName: String,           // 민감도 대상 식별자 (예: "volume_ma_period")
    val grid: List<BigDecimal>,      // 변동값 그리드 (예: [10, 15, 20, 25, 30])
)
// 출력: 그리드 각 점의 성과 → 고원(plateau) 여부 판정
data class SensitivityResult(
    val points: List<SensitivityPoint>,        // (paramValue, performance)
    val isPlateau: Boolean,                     // 인접 점 성과 분산 작으면 robust(고원), 크면 과최적화 의심
    val bestParamValue: BigDecimal,
)
data class SensitivityPoint(val paramValue: BigDecimal, val performance: BacktestPerformance)
```
- R3는 **엔진 코드 add-only**(IS/OOS 2-run 호출 래핑 + 그리드 루프) — 백테스트 코어 산식(ADR 0056) 무변경.
- **체결강도 백테스트 재현 불가** 명시 — R3는 candle 표현 가능 필터(갭·RVOL·거래대금·ATR)만. 체결강도 incremental은 B층(MOCK A/B, post-R3 backlog).

---

## §4. R1~R3 라운드 계획 + 영역 매핑 (산출물 4)

오케스트레이터 가설 검증 결과 — **대부분 타당. 1건 정정**(R1 DSL 식별자와 R2 tick 주입 배선의 의존 명확화).

| 라운드 | 담당 sub-agent | 범위 | 의존 | 병렬 |
|---|---|---|---|---|
| **R1-A** | market-data | KisWsMessageParser index 14·18·19·20 흡수 + TickEvent 2필드 add-only + parseH0Nxcnt0 동일 | 없음 (계약 LOCK 후 즉시) | R1-B·R3-검증과 병렬 |
| **R1-B** | strategy-engine | DSL `trade_intensity` 식별자 화이트리스트 add + 평가기 graceful(tick 부재 시 false, throw 금지) | 없음 (TickEvent 필드는 R1-A 머지 후 import, 식별자 등록은 독립) | R1-A·R3-검증과 병렬 (단 TickEvent import는 R1-A 선머지 권장 — add-only라 충돌 0) |
| **R3-검증** | strategy-engine | 과최적화 탐지: `BacktestRequest.outOfSampleSplit` add + `OverfitDiagnostics`·`SensitivityRequest/Result` 신규 + 엔진 2-run 래핑 | 없음 (R1과 독립 — candle 백테스트 경로) | R1-A·R1-B와 병렬 ✅ |
| **R2** | strategy-engine(엔진+프리셋) + frontend(발굴 화면) + qa(Visual) | `/api/screener/daytrade-discover` + 빌트인 단타 setup 프리셋(BuiltinResearchCandidates add) + 발굴 화면 + STRATEGY owner 연결 + Visual 회귀 | **R1 흡수 후** (trade_intensity 식별자 + TickEvent 필드 필요) | R2 내부는 엔진→화면→Visual 순차 권장 |
| **R3-측정** | strategy-engine | 단타 후보 A(5m) 백테스트 run + IS/OOS 분할 + alpha 판정 리포트(`_workspace/strategy/sample-backtest/`) | **R2 후** (발굴 프리셋이 universe·strategy 제공) + R3-검증(과최적화 탐지) 활용 | R2·R3-검증 후 |

**의존 그래프**: `{R1-A ∥ R1-B ∥ R3-검증}` → `R2` → `R3-측정`.

- **정정 포인트**: 오케 가설은 "R1: market-data + strategy-engine 병렬"인데, R1-B(DSL 식별자)는 R1-A(TickEvent 필드) import 의존이 있으나 **add-only라 실질 병렬 가능** — R1-A를 먼저 머지하면 R1-B는 rebase로 흡수. 식별자 화이트리스트 등록 자체는 TickEvent 무관(독립). → 병렬 OK, R1-A 선머지 권장.
- R3-검증(과최적화)은 R1 전혀 무관(candle-only 백테스트) → 진짜 병렬.

---

## §5. ADR 필요 판단 (산출물 6) → **신규 ADR 0117 발행**

발굴 레이어는 (1) 새 사용자 진입점(전략 없이 후보 제시) (2) KIS raw 신규 흡수(CTTR — 외부 데이터 규격 결정 → 헌법 §3 ADR 발행 의무) (3) 빌트인 발굴 프리셋 vs 사용자 DSL 구분 정책 (4) tick-only 백테스트 한계 LOCK — **결정 근거 누적 가치 충분**. ADR 0071(스크리너 spec) amend로는 부족 — 발굴은 strategyId 필수 전제를 깨는 별도 진입 모델이므로 신규 ADR이 정직.

→ **ADR 0117 신설** (본 spec과 1:1). ADR 0071은 "사용자 DSL 스크리너", ADR 0117은 "시스템 빌트인 룰 발굴 + 체결강도 흡수"로 책임 분리. ADR 0048(WS owner)·ADR 0056(백테스트)·ADR 0071 참조.

---

## §6. CHANGELOG 슬롯 (산출물 4 일부 — ADR 0050 정합)

라운드별 슬롯 row + anchor를 CHANGELOG.md 상단에 사전 prepend (PR diff 참조). 실제 구현 PR이 anchor를 채운다.

---

## §7. 사용자 결정 필요 (충돌 발견 시 임의 진행 금지 — 분리)

### 7-1. (낮음) DSL 식별자명 — `trade_intensity` 단일 vs `cttr` 병기
1차 LOCK은 `trade_intensity`(사용자 직관). `cttr` alias 병기 여부는 strategy-engine 재량으로 두되, 사용자가 KIS HTS 용어 `cttr` 선호 시 R1에서 alias add. **기본 진행 OK** (architect 자율 LOCK = `trade_intensity`).

### 7-2. (중간) 빌트인 단타 프리셋 — 코드 권위 vs V75 시드
- 옵션 A (권장): `BuiltinResearchCandidates.DEFINITIONS`에 코드 권위로 add (V74 시드 패턴과 동일하게 추후 시드 정합). Flyway 0.
- 옵션 B: V75 `strategy_research_candidate` 시드 INSERT.
- **기본 진행**: 옵션 A(코드 권위 add-only, Flyway 0)로 R2 진입. 사용자가 DB 시드 일관성 강제 원하면 V75 추가. **R2 진입 전 확인 1건** — orchestrator가 사용자 의향 1회 확인 권장(블로킹 아님, 옵션 A로 진행 가능).

> 그 외 모든 결정(CTTR index·식별자·라운드 분할·과최적화 계약)은 KIS raw + 기존 코드 인용 기반 architect 자율 LOCK — 사용자 결정 불요.
