# spec — 단타 발굴(Discovery) 레이어

- 작성: 2026-06-09, architect (2026-06-09 최고 관리자 결정 2건 정정 — ① 구현 순서 R2 우선 ② 프리셋 권위 V75 Flyway 시드)
- 상태: **제안 (사용자 결재됨 — 발굴 레이어 구축 결재 + 최고 관리자 결정 2건 LOCK. §7-2 미결 → V75 시드로 확정)**
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
| 저장 | `strategy` 테이블 | `strategy_research_candidate` 테이블 (V74 스키마 + **V75 시드** — 최고 관리자 결정 2 LOCK) |
| entryDsl | 사용자 DSL | `strategy_research_candidate.entry_dsl` (V75 시드 row) |
| 발굴 진입 | 사용자가 전략 선택 후 screen | 시스템 빌트인을 즉시 평가 |

→ 발굴 프리셋은 **기존 V74 `strategy_research_candidate` 테이블에 V75 마이그레이션으로 단타 setup 프리셋 row를 시드**한다 (신규 테이블 0 · DB 권위). 사용자 DSL 전략과 별도 테이블에 공존 — 충돌 0.

> **최고 관리자 결정 2 (2026-06-09 LOCK)**: 직전 spec의 옵션 A(코드 권위 `BuiltinResearchCandidates` add · Flyway 0)를 **폐기**한다. 단타 발굴 프리셋 권위는 **V75 Flyway 시드** = `strategy_research_candidate` 테이블에 row INSERT (V70 preset·V74 18종 후보 시드 선례 정합 — `ON CONFLICT (candidate_key) DO NOTHING`, builtin도 삭제 시 부활 X). 코드는 시드된 row를 `ResearchCandidateProvider` 포트로 read만 한다 (시드 권위 ≠ 코드 권위). §7-2 종결.

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

검증 시점 main = `f0df035b` (v3.12.1). **Flyway 번호 재audit (2026-06-09, 실 디렉토리 ls)**: `api/src/main/resources/db/migration/` 최신 = **V74** (`V74__strategy_research_candidate.sql`). 옆 세션 market-data 브랜치 `agent/market-data/today-candle-seed`(PR #833)는 `git diff --stat main..agent/market-data/today-candle-seed -- .../db/migration/` 결과 **migration 추가 0건**(최신 V74 동일) — V번호 점유 없음. → **다음 가용 = V75 확정** (충돌 0).

| 자원 | 신규 점유 | main 충돌 검증 | 결론 |
|---|---|---|---|
| **Flyway** | **V75 — 단타 빌트인 setup 프리셋 seed (최고 관리자 결정 2 — 권위)** | 최신 = V74 (`V74__strategy_research_candidate.sql`, ls 확인). 옆 세션 PR #833 migration 0건. 다음 가용 = **V75** | 충돌 0 LOCK. `strategy_research_candidate`(V74 스키마)에 INSERT 시드 — §3-R2 계약 |
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

**빌트인 발굴 프리셋 권위 = V75 Flyway 시드 (최고 관리자 결정 2 — LOCK)**:
- 권위는 **`V75__daytrade_discovery_setup_seed.sql` 마이그레이션**이 `strategy_research_candidate` 테이블(V74 스키마)에 INSERT하는 row다. 코드(`ResearchCandidateProvider`)는 시드된 row를 **read만** 한다 — 코드에 단타 프리셋을 하드코딩하지 않는다(옵션 A 폐기).
- **V75 시드 row 계약 (V74 컬럼과 정합 LOCK — `V74__strategy_research_candidate.sql:10-25` 스키마 1:1)**:

| 컬럼 (V74) | V75 단타 시드 값 계약 |
|---|---|
| `candidate_key` | `DAYTRADE_*` prefix 단타 setup key (V74 18종 key와 UNIQUE 충돌 0 — 예 `DAYTRADE_GAP_RVOL_TURNOVER`) |
| `title` | 단타 setup 한국어 제목 (예 "장전 갭·RVOL·거래대금 setup") |
| `market_fit` | 단타 적합 시장 설명 (사용자 가독 문구) |
| `entry_rules` | JSONB 배열 — 사람이 읽는 조건 목록 (V74 `entry_rules` 패턴 1:1) |
| `entry_dsl` | TEXT — strategy-engine 평가 권위 DSL. **전부 기존 화이트리스트 식별자만** (장전=일봉 표현 가능 필터). 예: `(close > 2000) and (atr(14)/close > 0.015) and (atr(14)/close < 0.06) and (close * volume > 10000000000) and (volume > 1.5 * volume_ma(20))` |
| `target_profit_pct` / `stop_loss_pct` / `max_hold_bars` | 단타 exit 파라미터 (V74 1.5/0.8/12 패턴 참고 — 단타값 strategy-engine 정정 가능) |
| `sort_order` | V74 18종(10~180) 뒤 — **200부터** 부여 (단타 setup 구간 분리, 충돌 0) |
| `is_active` | `TRUE` |
| `is_builtin` | `TRUE` (시스템 소유 — userId 비귀속) |

- 시드 형식 LOCK: `ON CONFLICT (candidate_key) DO NOTHING` (V74:186 선례 — 재배포 부활 X · builtin 삭제 허용). `created_at`/`updated_at`은 DEFAULT NOW() 위임(INSERT 컬럼 목록에서 생략 — V74:40 패턴).
- **`trade_intensity`는 장전 setup 프리셋(일봉 `entry_dsl`)에 넣지 않는다** (tick-only — 일봉 평가 시 false). 장중 확인 경로에서만. → V75 시드 entry_dsl은 candle 표현 가능 식별자만 사용 의무.

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

## §4. 라운드 계획 + 영역 매핑 (산출물 4 — 최고 관리자 결정 1: R2 우선 재배치)

**최고 관리자 결정 1 (2026-06-09 LOCK)**: 구현 순서를 **R2(장전 일봉 발굴) 먼저**로 재배치한다. 사유: 옆 세션이 **전 종목 일봉을 전수 backfill 중**(분봉은 전수 불가, 일봉만 완비). 따라서 일봉 기반 장전 발굴이 데이터 완비되는 견고한 1차 경로 → 우선순위 최상. 체결강도(이전 R1)·장중 분봉/tick은 **데이터 불완전 전제의 보조(best-effort·graceful)** → 후순위 파도로 재배치. 추가 이점: R2는 candle을 **read만** 하므로 옆 세션 market-data(일봉 ingestion)와 파일 충돌이 R1(WS 파서 — `KisWsMessageParser.kt`)보다 적다.

**새 순서**: `R2(장전 일봉 발굴 — 최우선) → R1(체결강도 tick 흡수 — best-effort 보조) → R3(백테스트 수익측정 + 과최적화 탐지)`.

| 순번 | 라운드 | 담당 sub-agent | 범위 | 의존 | 병렬 |
|---|---|---|---|---|---|
| **1 (최우선)** | **R2** | strategy-engine(엔진+V75 시드) ∥ frontend(발굴 화면) ∥ qa(Visual) | **V75 시드**(`strategy_research_candidate` 단타 setup row) + `/api/screener/daytrade-discover`(룰 기반·일봉 `interval=1d`) + 엔진(시드 read → ScreenerEngine 재사용 평가) + 발굴 화면(후보 TOP N + STRATEGY owner 등록·watchlist write 0) + Visual 회귀 | **없음** — 일봉 candle 영속만 read, R1(tick) 무관 | R2 내부: V75 시드+엔진(strategy) → 화면(frontend) → Visual(qa) 순차. 단 V75 시드 ∥ 화면 mock 골격은 병렬 착수 가능 |
| **2 (보조)** | **R1-A** | market-data | KisWsMessageParser index 14·18·19·20 흡수 + TickEvent 2필드 add-only + parseH0Nxcnt0 동일 | **없음**(독립) — best-effort tick, R2와 무관 | R2와 병렬 착수 가능하나 **우선순위는 R2 뒤**. 데이터 불완전 전제 → graceful 흡수만 |
| **2 (보조)** | **R1-B** | strategy-engine | DSL `trade_intensity` 식별자 화이트리스트 add + 평가기 graceful(tick 부재 시 false·throw 금지) | R1-A 선머지 권장(TickEvent import) — add-only라 rebase 흡수 | R1-A와 병렬. R2 일봉 entry_dsl은 `trade_intensity` 미사용이므로 R2와 독립 |
| **3** | **R3-검증** | strategy-engine | 과최적화 탐지: `BacktestRequest.outOfSampleSplit` add + `OverfitDiagnostics`·`SensitivityRequest/Result` 신규 + 엔진 2-run 래핑 | **없음**(candle 백테스트 — R1·R2 산식 무관) | R1·R2와 병렬 가능하나 순번상 후순위 배치 |
| **3** | **R3-측정** | strategy-engine | 단타 후보(일봉/5m) 백테스트 run + IS/OOS 분할 + alpha 판정 리포트(`_workspace/strategy/sample-backtest/`) | **R2 후**(V75 시드 프리셋이 universe·strategy 제공) + R3-검증 활용 | R2·R3-검증 후 |

**의존 그래프 (R2 우선)**: `R2 (V75 시드 ∥ 엔진 ∥ 화면 ∥ Visual)` → `{R1-A ∥ R1-B (best-effort 보조)}` → `{R3-검증 ∥ R3-측정}`.

- **R2가 R1 의존 0 검증**: R2 장전 발굴 entry_dsl은 일봉 표현 가능 식별자(close·atr·volume·volume_ma 등 — §3-R2 시드 계약)만 사용. `trade_intensity`(tick-only)는 V75 시드에 미포함(§3-R2 LOCK) → R2는 R1(tick 흡수·DSL 식별자) 산출물을 **요구하지 않는다**. 따라서 R2 단독 선행이 데이터·코드 양면에서 성립. ✅
- **옆 세션 market-data 충돌 회피 검증**: R2는 candle(일봉)을 **read만**(ScreenerEngine candle 영속 reuse·KIS 호출 0 — §1-4·§1-5) → 옆 세션 일봉 ingestion(write 경로)과 파일·자원 충돌 0. 반면 R1-A는 `KisWsMessageParser.kt`(consumer/ws — 옆 세션 WS 구독 시드 PR #833와 인접 가능) touch → 충돌 risk 상대적 ↑ → R1을 R2 뒤로 미루는 것이 충돌 최소화에도 부합. ✅
- **R1 best-effort 격하**: 분봉/tick 데이터 미완비 전제 → R1 흡수는 graceful(결손 시 필드 null·조건 false·throw 0)로 LOCK(§3-R1). 데이터 완비 전제로 동작 강제 금지.

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

### 7-2. (종결) 빌트인 단타 프리셋 권위 — **V75 Flyway 시드로 확정 (최고 관리자 결정 2)**
- ~~옵션 A (코드 권위 `BuiltinResearchCandidates` add · Flyway 0)~~ — **폐기**.
- **확정 = 옵션 B**: `V75__daytrade_discovery_setup_seed.sql` 마이그레이션이 `strategy_research_candidate`(V74 스키마)에 단타 setup row INSERT (§3-R2 시드 row 계약 LOCK). DB 권위 — 코드는 시드 row를 `ResearchCandidateProvider`로 read만. 사유: V70 preset·V74 후보 18종이 이미 시드 권위 선례 → DB 시드 일관성 통일. 미결 사항 0 — R2 진입 전 추가 확인 불요.

> 그 외 모든 결정(CTTR index·식별자·라운드 분할·과최적화 계약)은 KIS raw + 기존 코드 인용 기반 architect 자율 LOCK — 사용자 결정 불요.
