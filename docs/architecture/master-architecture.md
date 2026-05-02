# magicJar 마스터 아키텍처 v2

> **Status**: DRAFT v2 — 사용자 검토 대기
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)
> **선행 폐기**: 1차안 v1 (765줄, 2026-05-03) — "AI quant trading platform · AI 직접 매매" 가정 비전 정정으로 풀 폐기.
> **범위**: magicJar 전체 시스템의 단일 진실원. 룰 기반 quant 본체 + AI 옵션 보조 + MCP 서버 미래.
> **선행 ADR**: 0001~0030 (재분류 — `_workspace/arch/adr/REORGANIZATION.md` 참조)
> **신규 ADR**: 0031~0047 (master §10에 핵심 결정 한 줄. 정식 발행은 W1 진입 직전 + 트랙 진입 시 부분).

---

## §1. 시스템 비전 (사용자 정정 2026-05-02 반영)

### 1-1. 정체성

magicJar는 **룰 기반 quant trading 시스템**이다. **AI 자동매매 플랫폼이 아니다.**

| 행위자 | 책임 |
|---|---|
| **사용자(`sp-daewoon`)** | 시스템 설계자·전략 작성자·검증자·운영자. 종목 universe·전략 DSL·위험 한도·매매 한도 정의. |
| **시스템(magicJar)** | 종목 마스터 + 지표 + DSL + 시그널 → 자동 주문. **AI·외부 API·인터넷 끊겨도 동작**. |
| **AI(Claude·Gemini·OpenAI)** | **옵션 분석 보조**. 사용자 버튼 클릭 시 호출. 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. **매매 트리거 절대 X. 결정권 0**. |
| **AI 클라이언트(Claude·Cursor 등)** | **미래** — magicJar의 MCP 서버 호출. 우리가 server, AI가 client. |

### 1-2. 사용자 인용 (2026-05-02 원문)

> "AI가 매매를 하는건 허용하는게 아니다. 신호에 의한이고 AI는 옵션이다 명심해야한다 AI를 접목시킬 수 있는거지 이 시스템에 대한 MCP를 새로 만들어서 활용할 수 있게 하는거지 AI가 메인이 되면 안됨!!"

> "기본 오프라인으로 마스터 데이터만으로도 종목이 추출 가능한 시스템이 기본 목적이고 AI가 해당 추출 종목 및 차트의 지표 여러 분석을 옵션으로 코멘트 밑 기업분석을 목표로함 AI가 그냥 이거 산다 만다의 결정권을 갖는 시스템이 아님"

### 1-3. 핵심 원칙 5건

1. **시스템 본체 = 룰 기반 quant**. 종목 마스터 + 지표 + DSL + 시그널 → 자동 주문. **AI·외부 API·인터넷 끊겨도 동작**.
2. **AI = 옵션 분석 보조**. 사용자 버튼 클릭 시 호출. 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. 매매 트리거 X.
3. **MCP 서버 제공이 미래 목표**. magicJar가 MCP **서버**를 노출 — Claude/Cursor 등 AI 클라이언트가 magicJar를 호출. 우리가 AI 사용자가 아니다.
4. **지표는 source 추상화 plug-in**. KIS Strategy Builder 80지표는 1차 import. 이후 TA-Lib·자체 구현·KRX 등도 같은 인터페이스로 plug-in.
5. **개발 진행 방식 — 코드 먼저 완성 → 테스트 마지막 일괄**. QA 게이트 배제. 단일 책임. API 빌딩 한 스텝씩 순차. 테스트하면서 방향 흝뜨림 절대 X.

### 1-4. 본질 (이전 1차안 v1과 명확히 다른 점)

| 영역 | 1차안 v1 (폐기) | **v2 (정식)** |
|---|---|---|
| 본체 | "AI quant trading platform" | **"룰 기반 quant trading 시스템"** |
| AI 위상 | 시스템 흐름의 의사결정 노드 | **사용자 트리거 시 옵션 코멘트** |
| 매매 트리거 | AI가 BUY/SELL 결정 | **DSL 룰 평가 결과만** |
| 오프라인 동작 | 명시 없음 | **모든 본체 컴포넌트의 1순위 검증 기준** |
| MCP | 우리가 client (KIS MCP 호출) | **우리가 server (Claude/Cursor가 호출)** |
| 지표 | KIS 80지표 흡수 | **source 추상화 + KIS는 1차 plug-in** |
| Strategy UI | 1차 YAML editor + 2차 GUI | **1차 GUI 블록 빌더 골격 + yaml export/import 동시** |
| 차트 시각화 | LWC + TradingView Advanced(후속) | **LWC 확장 + 자체 도형/주석/패턴** (TradingView Advanced 폐기) |

### 1-5. MCP 서버 (W4 안에 포함)

magicJar는 미래에 **MCP 서버를 외부에 노출**해 Claude/Cursor 등 AI 클라이언트가 magicJar의 도메인을 호출 가능하게 한다. **W4 (Wave 4 — AI 옵션 + MCP 서버)** 범위.

후보 tool 5종 (1차):

| MCP tool | 입력 | 출력 | 권한 |
|---|---|---|---|
| `get_candles` | code · interval · from · to | OHLCV array | read |
| `run_screener` | universe · DSL yaml | code list + 강도 랭킹 | read |
| `get_journal` | from · to · code? | 매매일지 entries + summary | read |
| `explain_indicator` | name · params · context? | 사용자가 지정한 지표·차트의 자연어 설명 (LLM 호출은 magicJar 내부 LLM provider 또는 client AI 자체) | read |
| `backtest_strategy` | DSL yaml · period · universe | 성과 카드(Sharpe·MDD·승률 등) + trades | read |

인증: API key 또는 OAuth (post-MVP에서 결정). 1차는 localhost 한정.

---

## §2. 핵심 컴포넌트 맵 (4 영역 18 컴포넌트)

각 컴포넌트는 **오프라인 동작 가능 여부** 컬럼 명시 — 본체는 모두 가능, AI는 옵션.

### A. 데이터 수집·정합 영역 (4 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| A.1 | **Instrument Master (종목 마스터)** | KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주 universe. KIS·KRX·ISIN 매핑. 상장일·상장폐지·액면분할·합병 추적. | 가능 (정적 시드 + KIS sync는 옵션) | T3 |
| A.2 | **Market Data Pipeline (시세 수집·캐시·세션)** | KIS WS tick·체결·호가 + KIS REST 일봉·분봉 backfill. 분봉 합성. **장 상태 FSM + fallback** (live → cache → last-day-close). | 가능 (캐시·last-day-close · MockTickGenerator) | T2 (트랙2) |
| A.3 | **Corporate Action & Adjusted Price** | 액면분할·증자·배당·합병으로 인한 가격 점프 보정. raw + adjusted 양 컬럼. retroactive 재계산. | 가능 (일별 batch + 정적 시드) | T4 |
| A.4 | **News & Disclosure (뉴스·공시)** | DART 공시 + 네이버/한경 뉴스. 외부 source 끊겨도 **본체 흐름 차단 0**. | 부분 (news 미수집 시 본체 영향 0) | post-MVP 보강 |

### B. 분석·전략 영역 (5 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| B.5 | **Indicator Library (기술적 지표)** | 80지표 (MA·EMA·RSI·MACD·볼린저·VWAP·ADX·...). **`IndicatorSource` plug-in 인터페이스 의무.** KIS는 1차 import. TA-Lib·자체·KRX 후속. | 가능 (전부 in-process 계산) | T5 |
| B.6 | **Candlestick Pattern (캔들 패턴)** | 57개 패턴 인식 엔진. Doji·Hammer·Engulfing 등. 차트 마커. | 가능 (in-process 룰) | T6 |
| B.7 | **Strategy DSL** | `.kis.yaml` 호환 superset. AST 파서·검증·실행. magicJar 확장(다중 TF·시간 필터·종목 필터·패턴 조건). | 가능 (in-process 평가) | T7 |
| B.8 | **Strategy Builder UI (GUI 블록 빌더)** | 사용자 명시 — **GUI 블록 빌더 1차** (지표 선택·연산자·논리 조합·파라미터·저장) + yaml export/import 동시. 2차 풀 GUI. | 가능 (frontend in-browser) | T8 |
| B.9 | **Backtest Engine** | DSL → 과거 시계열 simulation → 매매 기록 + 성과 지표. 룩어헤드 차단·생존자 편향 보정·세금/수수료/슬리피지 모델. | 가능 (영속 candle만 있으면) | T9 |

### C. 의사결정·실행 영역 (5 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| C.10 | **Screener (종목 발굴)** | universe × DSL 조건식 → 후보 + 랭킹. 5분 캐시. 일별 batch + 분봉 실시간 두 모드. | 가능 (영속 candle + 룰만) | T10 |
| C.11 | **Signal Engine (시그널)** | 실시간 watch list × 활성 전략 → BUY/SELL/HOLD + 강도 + reason. **DSL 평가만, AI 호출 0**. | 가능 (in-process 룰) | T11 |
| C.12 | **Auto Order Execution (주문 실행)** | 시그널 → 위험 가드 → KisOrderSender → KIS API → 체결 → audit. mock/dev/prd profile 분기. | 가능 (mock profile은 즉시 FILLED) | T13 (트랙1 완료 + 트랙2 통합) |
| C.13 | **Risk & Position Guard (위험 관리)** | Guard chain 6단 (KillSwitch·Session·DailyLimit·PositionLimit·DrawdownStop·PriceSanity). 사이징(Kelly·Fixed Fractional). | 가능 (in-process 정책) | T12 |
| C.14 | **Performance Loop (성과 통계)** | 일별/주별 P&L·MDD·Sharpe·승률. **오프라인 통계만** (AI 복기는 W4 옵션). | 가능 (영속 데이터만) | T14 |

### D. 학습·운영·UI 영역 (4 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| D.15 | **Trading Journal (매매일지)** | 모든 주문·체결·시그널·차트 스냅샷 영속. 사용자 메모. | 가능 (영속 + pyworker screenshot) | T14 |
| D.16 | **Chart UI (LWC + 자체 도형/주석/패턴)** | 다중 timeframe·지표 overlay·패턴 마커·자체 도형/주석/패턴 시각화. **TradingView Advanced 의존 0**. | 가능 (frontend in-browser) | T15 |
| D.17 | **AI Option Console (W4)** | 사용자 버튼 클릭 시 LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. **매매 트리거 0**. dev에선 5분 prompt cache. | 부분 (LLM endpoint 끊겨도 본체 흐름 영향 0) | W4 |
| D.18 | **MCP Server (W4 미래)** | magicJar가 외부 AI 클라이언트(Claude·Cursor 등)에 도메인 노출. tool 5종. | 가능 (read 전용 — DB·캐시 직접 접근) | W4 |

총 18 컴포넌트.

### 부수 영역 (post-MVP)

| 영역 | 시점 |
|---|---|
| Notification (Slack·이메일·푸시) | W5 후 |
| Multi-tenant 인증 (OAuth2/JWT) | 실서비스 진입 |
| Observability (Prometheus·Grafana) | post-MVP |
| DR/Backup | post-MVP |
| TimescaleDB | 일봉 ≥1억 행 도달 시 |

---

## §3. 데이터 플로우 (오프라인 1순위 + AI 옵션 분기)

### 3-1. 본체 자동 매매 흐름 (오프라인 동작 — AI 호출 0)

```
[1] InstrumentSyncJob (cron 17:00) → instrument 테이블 갱신
       ↓
[2] DailyCandleBackfillJob (cron 18:00) → candle 테이블 영속
       + CorpActionSyncJob (cron 18:30) → corp_action 적용 + adjusted 재계산
       ↓
[3] DailyScreenerJob (cron 다음 영업일 09:00) — universe × DSL → 후보 종목
       ↓
[4] SignalEngine (실시간) — KIS WS tick → 분봉 합성 → DSL 평가 → BUY/SELL/HOLD
       ↓
[5] Risk Guard chain (6단) — KillSwitch · MarketSession · DailyLimit · PositionLimit · DrawdownStop · PriceSanity
       ↓ 통과
[6] KisOrderSender → KIS API → orders.placed (REJECTED 시 audit row INSERT)
       ↓
[7] KIS WS H0STCNI0 체결통보 → order_events.executed → position 갱신
       ↓
[8] Trading Journal 자동 기록 + 차트 스냅샷 (pyworker)
       ↓
[9] 일별 PerformanceJob — P&L·MDD·승률 통계
```

**위 9단계 모두 AI 호출 0건. 외부 LLM 끊겨도 정상 동작**.

### 3-2. 장외 시세 fallback 흐름 (오프라인 정합)

```
MarketSessionResolver = CLOSED → TickFallbackEmitter (10s) → QuoteCache.get →
   TickEvent(stale=true, asOf=마지막 영업일 close) → Kafka market.tick.v1 →
   STOMP → frontend stale 배지("휴장 중", "장 마감 14:30 기준")
```

KIS WS 끊겨도, KIS REST 끊겨도, 인터넷 끊겨도 — **마지막 영업일 close 데이터로 차트·지표·screener 모두 동작**. mock profile은 MockTickGenerator.

### 3-3. AI 옵션 보조 흐름 (사용자 트리거 한정)

```
사용자 클릭 [AI 분석 요청 버튼]
   ↓
api `/api/ai/explain?type=symbol&code=005930`
   ↓
consumer AnthropicAdapter (or Gemini) — prompt 조립 (지표 + 패턴 + 뉴스 컨텍스트)
   ↓
LLM 호출 (5분 prompt cache hit 시 skip) — 응답
   ↓
api STOMP `/topic/ai.explain.{requestId}` → frontend AI Card 표시
```

**호출 조건**:
- 사용자 명시 클릭만 (자동 호출 0)
- LLM 응답 실패·타임아웃 시 frontend "AI 응답 없음" 표시 + **본체 흐름 영향 0**
- dev profile은 prompt cache 5분 + `MAGICJAR_AI_OPTION_ENABLED=false` 기본 (비용 통제)

### 3-4. MCP 서버 흐름 (W4 미래)

```
Claude/Cursor (외부 AI 클라이언트) → MCP protocol over stdio/HTTP →
   magicJar MCP server (api 모듈에 endpoint or 별도 sidecar) →
   tool 호출 (get_candles · run_screener · ...) → DB·캐시·DSL 평가 →
   결과 응답
```

magicJar는 **server**. AI 클라이언트가 magicJar의 사용자.

### 3-5. 데이터 정합성 원칙

| 원칙 | 적용 |
|---|---|
| **Look-ahead 차단** | 백테스트는 t 시점에서 t 시점까지의 데이터만 보장. corp_action은 ex-date 기준 |
| **생존자 편향 보정** | universe는 시점별 — 2025-01-01 backtest는 2025-01-01 시점 상장 종목만 |
| **수정 주가** | corp_action 발생 시 candle.close_raw·close_adjusted 양 컬럼. 차트·백테스트 default는 adjusted |
| **세금·수수료·슬리피지** | 거래세 0.18% · 수수료 0.015% · VAT · 슬리피지 0.05% default. DSL에서 파라미터화 |
| **시간대** | 영속 시각 TIMESTAMPTZ UTC. 표현·DSL 평가는 KST. `zoneKst()` 헬퍼 단일 진실원 |
| **이벤트 멱등** | Kafka consumer 모두 idempotency key dedup |
| **결정 audit** | 모든 order·signal·screener result에 immutable audit row. **AI 호출도 audit (코멘트 자체)** — 매매에 영향 0이지만 비용·드리프트 추적 |

---

## §4. 외부 연동 매트릭스 (모두 옵션 + fallback 명시)

| 외부 | 용도 | 본체 의존도 | dev profile | prd MOCK_KIS | prd REAL_KIS | fallback |
|---|---|---|---|---|---|---|
| **KIS REST** | 일봉·분봉·잔고·종목 마스터·주문 | 매매 시 필수 (시세는 옵션) | 모의 server | 모의 server | 실 server | 캐시·last-day-close. mock profile은 stub |
| **KIS WS** | tick·호가·체결통보 | 매매 모니터링 시 필수 | 모의 ws | 모의 ws | 실 ws | TickFallbackEmitter (10s last-cached) |
| **DART (전자공시)** | 공시 본문·재무제표 | **옵션** | 호출 (key 발급 시) | 동일 | 동일 | 끊겨도 본체 영향 0 |
| **NAVER 금융 / 한경** | 뉴스 본문 | **옵션** | pyworker | 동일 | 동일 | 끊겨도 본체 영향 0 |
| **Anthropic Claude** | AI 옵션 분석 (W4) | **옵션** | 호출 (key 발급 + toggle) | 호출 | 호출 | 끊겨도 본체 영향 0 |
| **Google Gemini** | AI 옵션 분석 (W4 free tier) | **옵션** | 호출 (key 발급 + toggle) | 호출 | 호출 | 끊겨도 본체 영향 0 |
| **OpenAI** | AI 옵션 분석 (W4) | **옵션** | 호출 (선택) | 호출 | 호출 | 끊겨도 본체 영향 0 |
| **KRX 캘린더** | 영업일·임시휴장 | 트랙2 사용 | 정적 시드 (1차) | 정적 시드 | 자동 동기 (post-MVP) | 정적 시드 default |

**모든 외부 의존이 끊겨도 다음 본체 흐름은 동작**:
- 영속 candle로 차트·지표·screener·backtest
- mock profile MockTickGenerator로 시세 흐름
- 정적 캘린더 시드로 세션 인지
- DSL 평가 + Guard chain은 in-process

---

## §5. 도메인 경계 (헥사고날)

### 5-1. domain 모듈 (순수 — Spring·JPA·Kafka 의존 0)

```
domain/src/main/kotlin/com/mj/domain/
├── common/             ← RejectReason · zoneKst · Result sealed class
├── instrument/         ← Instrument · InstrumentMarket · CorpAction (T3·T4)
│   └── port/InstrumentRepository · CorpActionRepository · InstrumentSearchIndex
├── market/             ← Tick · Quote · Candle · session FSM (트랙2)
│   ├── session/MarketSession sealed class · MarketSessionResolver port
│   ├── cache/port/QuoteCache
│   ├── event/TickEvent · CandleEvent · ScreenshotCapturedEvent
│   └── port/KisRestPort · KisWsPort
├── indicator/          ← T5
│   ├── IndicatorSpec · IndicatorResult · IndicatorCatalog (80개 등록)
│   └── port/IndicatorEngine · IndicatorSource (★ source plug-in 인터페이스)
├── pattern/            ← T6
│   ├── PatternSpec · PatternMatch · PatternCatalog (57개)
│   └── port/PatternRecognizer
├── strategy/           ← T7·T8
│   ├── StrategyDefinition · StrategyDsl AST · DslCondition · StrategyParams
│   ├── port/StrategyRepository · DslParser · StrategyEvaluator
│   └── preset/PresetStrategies (10개 KIS 호환)
├── screener/           ← T10
│   ├── ScreenerQuery · ScreenerResult · Universe
│   └── port/ScreenerEngine
├── signal/             ← T11
│   ├── Signal (BUY/SELL/HOLD + strength + reason) · SignalContext
│   └── port/SignalGenerator
├── ai/                 ← W4 옵션 — 본체 흐름과 분리
│   ├── AiCommentRequest · AiCommentResponse (코멘트만, 의사결정 X)
│   └── port/AiCommentProvider (Anthropic·Gemini·OpenAI adapter)
├── order/              ← 트랙1 완료
├── risk/               ← T12
│   ├── PositionLimitPolicy · DrawdownPolicy · RiskBudget
│   └── port/RiskCalculator
├── position/           ← T12
│   ├── Position · PnL
│   └── port/PositionRepository
├── perf/               ← T14
│   ├── PerformanceMetrics (MDD·Sharpe·Sortino·WinRate)
│   └── port/PerformanceCalculator
├── journal/            ← 트랙E-P1 + T14
├── news/               ← post-MVP 보강
├── mcp/                ← W4 — magicJar MCP server tool 정의
│   ├── McpToolSpec
│   └── port/McpToolHandler
├── user/               ← 트랙1
└── secret/             ← 트랙1 (AES-256-GCM master key)
```

### 5-2. 응용 레이어 분배 (api·batch·consumer·pyworker·frontend)

| 영역 | api | batch | consumer | pyworker | frontend |
|---|---|---|---|---|---|
| Instrument | REST 조회·검색 | 일별 sync | (없음) | (선택 옵션) | 검색 자동완성 |
| Market Data | candles REST·STOMP | 일봉 backfill | KIS WS·tick·session·fallback | (없음) | 실시간 시세·차트 |
| Corp Action | 조회 REST | DART/KIS sync + adjust | (없음) | DART scrape | 차트 표시 |
| Calendar | 조회 REST | 분기 sync (post-MVP) | session resolver | (없음) | 세션 배지 |
| Indicator | 캐시 결과 REST | (없음) | 실시간 계산(`IndicatorSource` 합성 — 80개 in-process) | (옵션) | 차트 overlay |
| Pattern | 결과 조회 REST | (없음) | 실시간 인식 | (없음) | 차트 마커 |
| DSL | 파싱·검증 REST | (없음) | 평가 (시그널·screener) | (없음) | 빌더 GUI |
| Strategy | CRUD REST | (없음) | 활성 전략 로드 | (없음) | 빌더 UI |
| Backtest | trigger·결과 REST | 백테스트 job | (없음) | (선택 정밀 시뮬) | 결과 시각화 |
| Screener | trigger·결과 REST | 일별 batch | 분봉 실시간 | (선택) | 조건식·결과 |
| Signal | 조회 REST·STOMP | (없음) | 실시간 시그널 룰 매칭 | (없음) | Dashboard |
| Order | CRUD REST·STOMP | (선택 cleanup) | KIS adapter·guard·sender | (없음) | 주문·체결 표 |
| Risk | 조회 REST·KillSwitch toggle | DrawdownStop check job | guard chain | (없음) | KillSwitch UI |
| Position | 조회 REST | 잔고 sync job | KIS WS 체결 → position 갱신 | (없음) | 보유 종목 |
| Journal | CRUD REST | (없음) | 자동 기록 listener | screenshot 캡처 | 매매일지 |
| Performance | 조회 REST | 일/주 통계 job | (없음) | (선택 그래프) | 성과 페이지 |
| **AI Option** | trigger REST · STOMP | (없음) | LLM 호출 (5분 cache) | (없음) | AI Card (사용자 클릭) |
| **MCP Server (W4)** | sidecar endpoint | (없음) | (없음) | (없음) | (없음) |

---

## §6. 모듈 매트릭스 (영역 × 앱)

### 6-1. 컨테이너 9 + sidecar 1 = 10 (W4 시점)

| 컨테이너 | 언어 | 책임 |
|---|---|---|
| `api` | Kotlin/JVM | REST·STOMP·Swagger·웹 진입점 |
| `batch` | Kotlin/JVM | 스케줄 잡·일/주 작업 |
| `consumer` | Kotlin/JVM | Kafka consumer·KIS WS·실시간 처리 |
| `pyworker` | Python | 스크래핑·캡처 |
| `frontend` | TS/React | UI·LWC 차트 |
| `postgres` | (인프라) | 영속 |
| `redis` | (인프라) | 캐시 |
| `kafka` | (인프라) | 이벤트 백본 |
| `minio` | (인프라) | 객체 저장 |
| `mcp` (W4) | Kotlin or Python | MCP 서버 sidecar (1차는 api 내부 endpoint 옵션) |

### 6-2. 영속 저장 (PostgreSQL + Flyway 단일 인스턴스)

| 테이블 | 영역 | 신설/현행 | Flyway |
|---|---|---|---|
| `instrument` · `instrument_alias` | T3 | 신설 | V12 |
| `corp_action` | T4 | 신설 | V13 |
| `candle` (raw + adjusted) | T2·T4 | 현행 + adjusted 컬럼 | V14 |
| `market_calendar` | T2 | 신설 | V10 |
| `news` · `news_sentiment` | post-MVP | 현행 | (현행 V?) |
| `strategy` · `strategy_dsl_yaml` | T7 | 신설 (현행 placeholder 본격화) | V15 |
| `screener_query` · `screener_result` | T10 | 신설 | V16 |
| `signal` | T11 | 신설 | V17 |
| `ai_comment_log` (코멘트 audit, 매매 영향 0) | W4 | 신설 | V20 |
| `prompt_template` | W4 | 신설 | V20 |
| `orders` · `order_events` (현행) + `order_reject_audit` (트랙2) | T1·T2 | 현행 + V11 | V11 |
| `position` · `pnl_daily` | T12·T14 | 신설 | V18 |
| `risk_event` | T12 | 신설 | V18 |
| `trading_journal` · `chart_snapshot` | T14 | 현행 | (현행 V?) |
| `users` · `user_trading_config` · `kill_switch_state` · `daily_order_counter` | T1 | 현행 V9 | V9 |
| `mcp_audit` (W4 호출 로그) | W4 | 신설 | V21 |

### 6-3. Redis Key 매트릭스

| key 패턴 | 용도 | TTL | 트랙 |
|---|---|---|---|
| `quote:last:{code}` | last tick | 7일 | T2 |
| `quote:lastbusinessday:{code}:{yyyyMMdd}` | D+N 보존 | 14일 | T2 |
| `session:cache:current` | session resolver 캐시 | 60초 | T2 |
| `instrument:search:{prefix}` | 자동완성 | 1일 | T3 |
| `instrument:list:{market}` | universe | 1일 | T3 |
| `indicator:cache:{code}:{interval}:{ind}:{params}` | 지표 결과 | 5분(분봉)/1일(일봉) | T5 |
| `screener:result:{queryHash}` | 스크리너 | 5분 | T10 |
| `signal:active:{code}` | 활성 시그널 | 5분 | T11 |
| `position:current:{userId}` | 현재 포지션 | 30초 | T12 |
| `kis:token:{userId}` | KIS access token | 23h | T1 |
| `ai:prompt:cache:{templateHash}` | LLM 응답 (W4 옵션) | 5분 | W4 |

### 6-4. Kafka 토픽 매트릭스

| 토픽 | producer | consumer | retention | 트랙 |
|---|---|---|---|---|
| `market.tick.v1` | consumer (WS·Mock·Fallback) | api (STOMP) | 1h | 현행 + T2 |
| `market.candle.v1` | batch · consumer | consumer (지표 갱신) | 1d | T5 |
| `market.session.v1` | consumer | api (STOMP) | 1d | T2 |
| `instrument.updated.v1` | batch | consumer (캐시 invalidate) | 7d | T3 |
| `corp_action.applied.v1` | batch | consumer (candle 재조정) | 30d | T4 |
| `news.crawled.v1` | pyworker | consumer | 7d | post-MVP |
| `screener.result.v1` | consumer/batch | consumer (시그널), api (STOMP) | 1d | T10 |
| `signal.generated.v1` | consumer | consumer (order intent), api (STOMP) | 7d | T11 |
| `order.intent.v1` | consumer (signal), api (수동) | consumer (KisOrderSender) | 7d | T1 |
| `order.executed.v1` | consumer | api (STOMP), consumer (journal) | 30d | T1 |
| `order.rejected.v1` | consumer | api (STOMP) | 30d | T2 |
| `journal.recorded.v1` | consumer | pyworker (snapshot) | 30d | T14 |
| `screenshot.captured.v1` | pyworker | consumer (journal 갱신) | 7d | 현행 |
| `risk.event.v1` | consumer | api (STOMP), consumer (KillSwitch trigger) | 30d | T12 |
| `ai.comment.requested.v1` (W4) | api | consumer (LLM 호출) | 1d | W4 |
| `ai.comment.responded.v1` (W4) | consumer | api (STOMP) | 7d | W4 |

**`ai.decision.v1` 폐기** — 1차안의 "AI 의사결정 토픽". v2엔 매매 결정 토픽 자체 없음 (시그널 → 주문은 룰 평가).

---

## §7. Profile 분기 매트릭스 (mock/dev/prd × 컴포넌트 × source × AI on/off)

### 7-1. 컴포넌트 × profile

| 컴포넌트 | mock | dev | prd MOCK_KIS | prd REAL_KIS |
|---|---|---|---|---|
| MarketSessionResolver | NoOp(OPEN) | active | active | active(strict) |
| MarketSessionGuard | bypass | active(toggle) | active | active(strict) |
| KisRestPort | StubKisRestAdapter | RealKisRestAdapter(모의) | RealKisRestAdapter(모의) | RealKisRestAdapter(실전) |
| KisWsPort | n/a (Mock) | RealKisWs(모의) | RealKisWs(모의) | RealKisWs(실전) |
| MockTickGenerator | active | inactive (default) — toggle ON 시 활성 | inactive | inactive |
| TickFallbackEmitter | inactive | active(CLOSED) | active(CLOSED) | active(CLOSED) |
| QuoteCache | InMemory | Redis | Redis | Redis |
| InstrumentSync | mock seed | KIS sync | KIS sync | KIS sync |
| CorpActionSync | mock seed | KIS+DART sync | KIS+DART sync | KIS+DART sync |
| **IndicatorEngine** (`IndicatorSource` plug-in) | active(JVM Native) | active(JVM Native + KisStrategyBuilderSource 옵션) | active | active |
| PatternRecognizer | active | active | active | active |
| Screener | active(mock data) | active | active | active |
| SignalGenerator | active(룰 매칭) | active | active | active |
| OrderSender | Mock(즉시 FILLED) | KisOrderSender (CLOSED+toggle 시 Mock) | KisOrderSender(모의) | KisOrderSender(실전, 이중 게이트) |
| KillSwitch | bypass(default OFF) | DB row | DB row | DB row (default ON, 명시 OFF) |
| RiskCalculator | active | active | active | active(strict) |
| TradingJournal | active | active | active | active |
| **AI Option Console** | inactive (default) | inactive (default) — `MAGICJAR_AI_OPTION_ENABLED=true` 시 활성 | inactive (default) | inactive (default) |
| **AiCommentProvider** (Anthropic·Gemini·OpenAI) | n/a | toggle ON 시 호출 + 5분 cache | toggle ON | toggle ON |
| **MCP Server** (W4) | inactive | toggle ON 시 활성 (localhost) | toggle ON | toggle ON |

### 7-2. AI 호출 토글 (비용·속도 제어)

- `MAGICJAR_AI_OPTION_ENABLED=false` (default 모든 profile) — AI 옵션 사용 안 함, LLM 호출 0
- `MAGICJAR_AI_OPTION_ENABLED=true` + `MAGICJAR_AI_PROVIDER=anthropic|gemini|openai` — 사용자 트리거 시 호출
- 5분 prompt cache (`ai:prompt:cache:{hash}`) — 동일 입력 재사용
- LLM 응답 실패·타임아웃 시 frontend "AI 응답 없음" 표시 + **본체 흐름 영향 0**

### 7-3. source 추상화 (`IndicatorSource` plug-in — v2 핵심)

```kotlin
interface IndicatorSource {
    val name: String  // "kis-strategy-builder" · "ta-lib" · "native" · "krx"
    fun supports(spec: IndicatorSpec): Boolean
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult
}

// 구현체 — Profile별 또는 spec별 라우팅
class KisStrategyBuilderSource(...) : IndicatorSource  // KIS 80지표 1차 import
class NativeIndicatorSource(...) : IndicatorSource     // 자체 Kotlin 구현
class TaLibSource(...) : IndicatorSource              // TA-Lib JNI/HTTP (선택)
class KrxSource(...) : IndicatorSource                // KRX 공식 발행 (post-MVP)
```

`IndicatorEngine`은 등록된 source 중 spec 지원 여부 + 우선순위로 선택.

---

## §8. 트랙 로드맵 요약 (5 Wave — 상세는 `tracks-roadmap.md`)

| Wave | 범위 | 담당 트랙 |
|---|---|---|
| **W1** — 데이터 기반 (오프라인 1순위) | 종목 마스터·시세 fallback·세션·corp action | T2 (트랙2) · T3 · T4 |
| **W2** — 분석 도구 | 80지표·57패턴·DSL·Strategy Builder UI | T5 · T6 · T7 · T8 |
| **W3** — 자동 매매 본체 (룰만, AI 호출 0) | 백테스트·screener·signal·risk·order 통합·perf | T9 · T10 · T11 · T12 (T13 트랙1 완료 + 통합) · T14 |
| **W4** — AI 옵션 + MCP 서버 | 사용자 트리거 AI 코멘트·기업 분석·MCP server expose | T16 · T17 |
| **W5** — 사용자 학습 + 일원화 | 데이터 흐름·아키 의도 검증·교육·LWC 확장 시각화 | T15 (LWC 확장) · T18 (사용자 학습) |

총 ~14 트랙. 본 마스터의 W1~W3 완료 시점이 "룰 기반 본체 풀 가동". W4·W5는 옵션 + 마무리.

---

## §9. 비범위·후속 (post-MVP)

| 항목 | 사유 | 시점 |
|---|---|---|
| **TradingView Advanced 통합** | 라이선스 실현성 낮음 (결제만으로 안 됨, 무료 신청 거절 가능). LWC 확장 + 자체 도형/주석/패턴으로 종결 (T15). | 폐기 — 재검토 안 함 |
| 인증 (OAuth2/JWT) | 1인 사용자 단계 | 실서비스 진입 |
| 알림 (Slack·이메일·푸시) | UI 알림 우선 | W5 후 |
| TimescaleDB | 일봉 ≥1억 행 미달 | post-MVP |
| Schema Registry (Avro) | 프로듀서 ≥3팀 미달 | post-MVP |
| Vault 시크릿 | 1인 + .env로 충분 | 멀티유저 진입 |
| k8s 배포 | docker compose 9 컨테이너로 충분 | 운영 부하 시 |
| 외국인·기관 수급 정밀 | 1차 뉴스·공시로 컨텍스트 | post-MVP |
| 자연어 → DSL 번역 | AI 옵션 (W4 후속) | post-W4 |
| 실시간 분봉 backfill | 일봉·5분봉 우선 | post-MVP |

---

## §10. 핵심 ADR 세트 (v2 — 0031~0047)

상세는 `_workspace/arch/adr/REORGANIZATION.md` §3 + 각 트랙 진입 시 발행. 핵심만 한 줄:

| 새 번호 | 제목 | 핵심 결정 |
|---|---|---|
| 0031 | 시스템 비전 v2 (룰 본체 + AI 옵션 + MCP 서버 미래) | AI 결정권 0. 오프라인 1순위. |
| 0032 | Market Session FSM | KRX 영업일·장중·장외·휴장 FSM. MarketSessionResolver port. |
| 0033 | 시세 Fallback 3-tier (live → cache → last-day-close) | stale=true 명시 + UI 배지. |
| 0034 | REJECTED Audit Trail | Guard reject·KisApiException 모두 orders INSERT. |
| 0035 | KIS REST/WS 정밀화 (EGW00202 hotfix + hashkey) | RealKisRestAdapter raw HTTP 표준. |
| 0036 | KIS Strategy Builder 흡수 = 명세 port (AI hook 제거) | DSL·80지표·57패턴·10프리셋만 port. .kis.yaml 호환. |
| 0037 | Instrument Master 1급 시민 | KIS·KRX 일일 sync + Redis 검색 인덱스. 상장폐지 보존. |
| 0038 | Corp Action raw + adjusted | 룩어헤드 차단. retroactive 재계산. |
| 0039 | Indicator Source 추상화 | `IndicatorSource` plug-in. KIS·TA-Lib·Native·KRX. |
| 0040 | Strategy DSL = `.kis.yaml` 호환 superset | magicJar 확장(다중 TF·시간 필터·종목 그룹·패턴 조건). |
| 0041 | Signal Engine = 룰 매칭 only (AI 호출 0) | DSL 평가만. AI 컨설트는 W4 옵션 분리. |
| 0042 | Risk Guard chain 6단 | 1차 매우 느슨 default. PositionLimit 종목당 10%. DrawdownStop 일일 -3%. |
| 0043 | Multi-tenant 구조 (트랙1 완료 흡수) | user_id 컬럼. 인증은 post-MVP. |
| 0044 | LWC 확장 + 자체 도형/주석/패턴 (TradingView Advanced 폐기) | LWC v4 공식 API 한정 + 자체 overlay layer. |
| 0045 | MCP 서버 미래 (W4) | tool 5종. 1차 localhost. 우리가 server. |
| 0046 | AI 옵션 보조 (사용자 트리거 한정) | 종목 코멘트·기업 분석·차트 해석·지표 의미. 매매 트리거 0. |
| 0047 | 새 v2 마스터 = 단일 진실원 | 트랙 spec/plan은 §X 위치 명시 의무. 충돌 시 master 선 개정. |

---

## §11. 비범위 (TradingView Advanced·외국인 수급·실시간 분봉 backfill 등)

위 §9에 일괄 정리.

추가:
- **AI 의사결정 흐름** (1차안 ADR 0042) — **명시 폐기**. v2 베이스라인엔 AI Decision Layer 자체 없음.
- **AI 비용 모니터링 자체 페이지** — W4 후속 (1차는 ai_comment_log 테이블 단순 audit으로 충분)

---

## §12. 사용자 결정 필요 사항 (v2 검토 시)

본 마스터 검토 시 사용자가 확정:

1. **새 v2 ADR 0031~0047 발행 시점** — W1 진입 직전 일괄 / 트랙별 진입 시 부분?
2. **Deprecated/Superseded 13건 처리** — 즉시 삭제 / `_archive/` 이동 / 본문 헤더만?
3. **release/v1 처리** — 사용자 명시 "release말곤 싹지울 예정" 정합 시 v1 삭제? v0만 보존?
4. **MCP server 1차 위치** — W4의 별도 sidecar 컨테이너 vs api 모듈 내 endpoint?
5. **AI provider 1차** — Anthropic / Gemini(free) / OpenAI 중 default?
6. **Indicator source 1차 plug-in** — KIS Strategy Builder 1차만 / NativeSource 자체 구현 동시?

---

## §13. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 (v1) | 1차안 (architect, opus 4.7 1M) — "AI quant trading platform" 가정. 사용자 비전 정정으로 폐기. |
| 2026-05-03 (v2) | **풀 재작성** — 룰 기반 본체 + AI 옵션 + MCP 서버 미래. 18 컴포넌트 4 영역. 새 ADR 0031~0047 17건. (architect, opus 4.7 1M) |
