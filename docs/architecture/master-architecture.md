# magicJar 마스터 아키텍처 v2

> **Status**: DRAFT v2 — 사용자 검토 대기
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)
> **선행 폐기**: 1차안 v1 (765줄, 2026-05-03) — "AI quant trading platform · AI 직접 매매" 가정 비전 정정으로 풀 폐기.
> **범위**: magicJar 전체 시스템의 단일 진실원. 룰 기반 quant 본체 + AI 옵션 보조 + MCP 서버 미래.
> **선행 ADR**: 0001~0040 (전체 인덱스는 `_workspace/arch/adr/README.md`)
> **신규 ADR**: 0041~ (master §10에 핵심 결정 한 줄. 정식 발행은 W1 진입 직전 + 트랙 진입 시 부분). 0048 슬롯은 §12 후속 예약 — 0049·0050은 PR-8 메타 ADR(GitHub Actions release 한정 / CHANGELOG 슬롯 채번룰)로 채택.

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

## §2. 핵심 컴포넌트 맵 (4 영역 19 컴포넌트)

각 컴포넌트는 **오프라인 동작 가능 여부** 컬럼 명시 — 본체는 모두 가능, AI는 옵션.

### A. 데이터 수집·정합 영역 (4 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| A.1 | **Instrument Master (종목 마스터)** | KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주 universe. KIS·KRX·ISIN 매핑. 상장일·상장폐지·액면분할·합병 추적. | 가능 (정적 시드 + KIS sync는 옵션) | T-IM1 |
| A.2 | **Market Data Pipeline (시세 수집·캐시·세션)** | **Watchlist 기반 Ingestion Worker (1~5초 polling)** — KIS WS tick·체결·호가 + KIS REST 일/주/월/년봉 + Yahoo 1m/2m/5m/15m/30m/90m/1h DB 영속 + 당일 진행 중 캔들 Redis 휘발 캐시. **장 상태 FSM + fallback** (live → cache → last-day-close). 합성·LRU 폐기. | 가능 (캐시·last-day-close · MockTickGenerator) | T-W1·T-W2 |
| A.3 | **Corporate Action & Adjusted Price** | 액면분할·증자·배당·합병으로 인한 가격 점프 보정. raw + adjusted 양 컬럼. retroactive 재계산. | 가능 (일별 batch + 정적 시드) | T-CA1 |
| A.4 | **News & Disclosure (뉴스·공시)** | DART 공시 + 네이버/한경 뉴스. 외부 source 끊겨도 **본체 흐름 차단 0**. | 부분 (news 미수집 시 본체 영향 0) | post-MVP 보강 |

### B. 분석·전략 영역 (5 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| B.5 | **Indicator Library (기술적 지표)** | 80지표 (MA·EMA·RSI·MACD·볼린저·VWAP·ADX·...). **`IndicatorSource` plug-in 인터페이스 의무.** KIS는 1차 import. TA-Lib·자체·KRX 후속. | 가능 (전부 in-process 계산) | T-IND |
| B.6 | **Candlestick Pattern (캔들 패턴)** | 57개 패턴 인식 엔진. Doji·Hammer·Engulfing 등. 차트 마커. | 가능 (in-process 룰) | T-PAT |
| B.7 | **Strategy DSL** | `.kis.yaml` 호환 superset. AST 파서·검증·실행. magicJar 확장(다중 TF·시간 필터·종목 필터·패턴 조건). | 가능 (in-process 평가) | T-DSL |
| B.8 | **Strategy Builder UI (GUI 블록 빌더)** | 사용자 명시 — **GUI 블록 빌더 1차** (지표 선택·연산자·논리 조합·파라미터·저장) + yaml export/import 동시. 2차 풀 GUI. | 가능 (frontend in-browser) | T-SB |
| B.9 | **Backtest Engine** | DSL → 과거 시계열 simulation → 매매 기록 + 성과 지표. 룩어헤드 차단·생존자 편향 보정·세금/수수료/슬리피지 모델. | 가능 (영속 candle만 있으면) | T-BT |

### C. 의사결정·실행 영역 (5 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| C.10 | **Screener (종목 발굴)** | universe × DSL 조건식 → 후보 + 랭킹. 5분 캐시. 일별 batch + 분봉 실시간 두 모드. | 가능 (영속 candle + 룰만) | T-SC |
| C.11 | **Signal Engine (시그널)** | 실시간 watch list × 활성 전략 → BUY/SELL/HOLD + 강도 + reason. **DSL 평가만, AI 호출 0**. | 가능 (in-process 룰) | T-SIG |
| C.12 | **Auto Order Execution (주문 실행)** | 시그널 → 위험 가드 → KisOrderSender → KIS API → 체결 → audit. mock/dev/prd profile 분기. | 가능 (mock profile은 즉시 FILLED) | T-O (트랙1 완료 + 트랙2 통합) |
| C.13 | **Risk & Position Guard (위험 관리)** | Guard chain 6단 (KillSwitch·Session·DailyLimit·PositionLimit·DrawdownStop·PriceSanity). 사이징(Kelly·Fixed Fractional). | 가능 (in-process 정책) | T-RISK |
| C.14 | **Performance Loop (성과 통계)** | 일별/주별 P&L·MDD·Sharpe·승률. **오프라인 통계만** (AI 복기는 W4 옵션). | 가능 (영속 데이터만) | T-PERF |

### D. 학습·운영·UI 영역 (5 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| D.15 | **Watchlist (관심종목)** | UI 1순위 진입점. Strategy Builder 발굴 → 관심등록 → 상세 보기 흐름. 모든 외부 시세 수집의 단위 (Watchlist 풀). | 가능 (DB only) | T-W1 (최우선) |
| D.16 | **Trading Journal (매매일지)** | 모든 주문·체결·시그널·차트 스냅샷 영속. 사용자 메모. | 가능 (영속 only) | T-J |
| D.17 | **Chart UI (LWC + 자체 도형/주석/패턴)** | 다중 timeframe·지표 overlay·패턴 마커·자체 도형/주석/패턴 시각화. **TradingView Advanced 의존 0**. | 가능 (frontend in-browser) | T-LWC |
| D.18 | **AI Option Console (W4)** | 사용자 버튼 클릭 시 LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. **매매 트리거 0**. dev에선 5분 prompt cache. | 부분 (LLM endpoint 끊겨도 본체 흐름 영향 0) | W4 |
| D.19 | **MCP Server (W4 미래)** | magicJar가 외부 AI 클라이언트(Claude·Cursor 등)에 도메인 노출. tool 5종. | 가능 (read 전용 — DB·캐시 직접 접근) | W4 |

총 19 컴포넌트.

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
[0] Position Bootstrap (consumer ApplicationRunner — ADR 0042)
       활성 user 순회 → KIS REST 잔고(TTTC8434R) → DB position upsert →
       Redis position:current:{userId} 워밍 (TTL 24h) → KIS WS 구독 시작
       ↓
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
[8] Trading Journal 자동 기록 (frontend 차트 스냅샷 base64 → api 영속)
       ↓
[9] 일별 PerformanceJob — P&L·MDD·승률 통계
```

**위 0~9단계 모두 AI 호출 0건. 외부 LLM 끊겨도 정상 동작**.

**부수 흐름 (사이클 외 안전망)**:
- **WS 재연결 시 Recovery (ADR 0042)** — `KisWsReconnectedEvent` listener → `inquire-daily-ccld` (TTTC0081R) 호출 → missed exec fetch → DB·Redis 적용
- **Balance Reconcile (ADR 0041)** — 시장 OPEN 5분 cron / CLOSED 1시간 cron → KIS REST 잔고 vs DB diff → drift 시 KillSwitch auto-ON + Kafka publish
- **KillSwitch ON 행동 (ADR 0043)** — ON 시 시그널 emit 정지 + pending cancel(`order-rvsecncl` TTTC0803U) + STOMP push + 신규 주문 422 + 체결통보는 정상 수신
- **Notification 통지 (ADR 0044)** — consumer NotificationDispatcher가 7 Kafka topic 구독 → Slack webhook POST + outbox fallback. 사용자 부재(취침·자리비움) 중 사고 즉시 모바일 push
- **Graceful Shutdown (ADR 0045)** — SIGTERM 수신 시 api(HTTP draining 30s)·consumer(Kafka manual commit + KIS WS close)·batch(JobOperator.stop) 순차 종료. 미체결 주문은 PENDING 보존 → 재기동 시 ADR 0042+0041로 진위 판정. order.intent.v1 idempotency 키 `{user}:{strategy}:{ymd}:{tickSeq}` + Redis SETNX 24h
- **Onboarding Setup (ADR 0046)** — 첫 부팅 시 활성 user 0명 idle. localhost:3000 진입 → 셋업 마법사 7-step → KIS REST `/oauth2/tokenP` 검증 → AES-256-GCM 암호화 → user_account + user_kis_credential 영속 → UserOnboardedEvent → WatchlistIngestionWorker polling 시작
- **DSL Sandbox (ADR 0047)** — Strategy 등록 시 static 검증(AST depth ≤ 32·노드 ≤ 10000·indicator ≤ 50·키워드 화이트리스트). SignalGenerator 평가 시 strategy-eval-pool(fixed 4) 경유 → realtime timeout 100ms / backtest 1000ms · CPU budget 50ms. Timeout 1회 시 strategy auto-disable + `risk.violation.v1`(type=STRATEGY_TIMEOUT) → ADR 0044 통지

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
- dev profile은 prompt cache 5분 + `MAGICJAR_AI_ENABLED=false` 기본 (비용 통제)

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
| **NAVER 금융 / 한경** | 뉴스 본문 | **옵션** | api 외부 어댑터 | 동일 | 동일 | 끊겨도 본체 영향 0 |
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
├── instrument/         ← Instrument · InstrumentMarket · CorpAction (T-IM1·T-CA1)
│   └── port/InstrumentRepository · CorpActionRepository · InstrumentSearchIndex
├── market/             ← Tick · Quote · Candle · session FSM (트랙2)
│   ├── session/MarketSession sealed class · MarketSessionResolver port
│   ├── cache/port/QuoteCache
│   ├── event/TickEvent · CandleEvent · ScreenshotCapturedEvent
│   └── port/KisRestPort · KisWsPort
├── indicator/          ← T-IND
│   ├── IndicatorSpec · IndicatorResult · IndicatorCatalog (80개 등록)
│   └── port/IndicatorEngine · IndicatorSource (★ source plug-in 인터페이스)
├── pattern/            ← T-PAT
│   ├── PatternSpec · PatternMatch · PatternCatalog (57개)
│   └── port/PatternRecognizer
├── strategy/           ← T-DSL·T-SB
│   ├── StrategyDefinition · StrategyDsl AST · DslCondition · StrategyParams
│   ├── port/StrategyRepository · DslParser · StrategyEvaluator
│   └── preset/PresetStrategies (10개 KIS 호환)
├── screener/           ← T-SC
│   ├── ScreenerQuery · ScreenerResult · Universe
│   └── port/ScreenerEngine
├── signal/             ← T-SIG
│   ├── Signal (BUY/SELL/HOLD + strength + reason) · SignalContext
│   └── port/SignalGenerator
├── ai/                 ← W4 옵션 — 본체 흐름과 분리
│   ├── AiCommentRequest · AiCommentResponse (코멘트만, 의사결정 X)
│   └── port/AiCommentProvider (Anthropic·Gemini·OpenAI adapter)
├── order/              ← 트랙1 완료
├── risk/               ← T-RISK
│   ├── PositionLimitPolicy · DrawdownPolicy · RiskBudget
│   └── port/RiskCalculator
├── position/           ← T-RISK
│   ├── Position · PnL
│   └── port/PositionRepository
├── perf/               ← T-PERF
│   ├── PerformanceMetrics (MDD·Sharpe·Sortino·WinRate)
│   └── port/PerformanceCalculator
├── journal/            ← 트랙E-P1 + T-J
├── news/               ← post-MVP 보강
├── mcp/                ← W4 — magicJar MCP server tool 정의
│   ├── McpToolSpec
│   └── port/McpToolHandler
├── user/               ← 트랙1
└── secret/             ← 트랙1 (AES-256-GCM master key)
```

### 5-1A. pattern-engine 라이브러리 모듈 (Spring Context — Boot main 0)

ADR 0054 정합 — 2026-05-10 신설. domain port `PatternRecognizer`·`PatternDetector` 구현 빈을 보유하는 라이브러리 jar. api·consumer 두 모듈이 동일 빈을 공유하기 위한 분리.

```
pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/
├── JvmPatternRecognizer            ← @Component("patternRecognizer") · 57 detector registry
├── cache/RedisPatternCacheAdapter  ← Redis 캐시 (분봉 300s / 일봉 86400s TTL · api·consumer unify)
├── util/CandleMath                 ← 캔들 수학 헬퍼 (KIS candlestick.py 정합)
├── helper/                         ← ConsecutiveDirectionCalculator · SimpleTripleHelper
└── detector/                       ← 57 detector
    ├── single/                     ← 14 detector (Doji·Hammer·Marubozu·... )
    ├── triple/                     ← 27 detector (MorningStar·EveningStar·... )
    └── pair/                       ← 16 detector (Engulfing·Harami·... )
```

의존: `pattern-engine` → `domain`만. `api`·`consumer` → `pattern-engine`. ADR 0004 §의존 방향 정합 보존(api·consumer 직접 의존 0).

빌드: `kotlin("jvm") + kotlin("plugin.spring") + spring-context + spring-data-redis`. **Spring Boot main·bootJar 0** (라이브러리 jar). 컨테이너 0.

### 5-2. 응용 레이어 분배 (api·batch·consumer·frontend)

(아래 표는 응용 모듈 4개 분배 — pattern-engine 라이브러리는 §5-1A 별도)

| 영역 | api | batch | consumer | frontend |
|---|---|---|---|---|
| Instrument | REST 조회·검색 | 일별 sync | (없음) | 검색 자동완성 |
| Market Data | candles REST·STOMP · Yahoo backfill 어댑터 | 일봉 backfill 잡 | KIS WS·tick·session·fallback | 실시간 시세·차트 |
| Corp Action | DART scrape · 조회 REST | DART/KIS sync + adjust | (없음) | 차트 표시 |
| Calendar | 조회 REST | 분기 sync (post-MVP) | session resolver | 세션 배지 |
| Indicator | 캐시 결과 REST | (없음) | 실시간 계산(`IndicatorSource` 합성 — 80개 in-process) | 차트 overlay |
| Pattern | 결과 조회 REST | (없음) | 실시간 인식 | 차트 마커 |
| DSL | 파싱·검증 REST | (없음) | 평가 (시그널·screener) | 빌더 GUI |
| Strategy | CRUD REST | (없음) | 활성 전략 로드 | 빌더 UI |
| Backtest | trigger·결과 REST | 백테스트 job | (없음) | 결과 시각화 |
| Screener | trigger·결과 REST | 일별 batch | 분봉 실시간 | 조건식·결과 |
| Signal | 조회 REST·STOMP | (없음) | 실시간 시그널 룰 매칭 | Dashboard |
| Order | CRUD REST·STOMP | (선택 cleanup) | KIS adapter·guard·sender | 주문·체결 표 |
| Risk | 조회 REST·KillSwitch toggle | DrawdownStop check job | guard chain | KillSwitch UI |
| Position | 조회 REST | 잔고 sync job | KIS WS 체결 → position 갱신 | 보유 종목 |
| Journal | CRUD REST · 차트 스냅샷 영속 | (없음) | 자동 기록 listener | 매매일지 + 차트 스냅샷 |
| Performance | 조회 REST | 일/주 통계 job | (없음) | 성과 페이지 |
| News | 네이버·DART scrape 어댑터 + Kafka publish | (없음) | AI 분석 listener | 뉴스 패널 |
| **AI Option** | trigger REST · STOMP | (없음) | LLM 호출 (5분 cache) | AI Card (사용자 클릭) |
| **MCP Server (W4)** | sidecar endpoint | (없음) | (없음) | (없음) |

---

## §6. 모듈 매트릭스 (영역 × 앱)

### 6-1. 컨테이너 7 + sidecar 1 = 8 (W4 시점)

(JVM 모듈은 5개 — `domain`·`pattern-engine`은 라이브러리 jar로 컨테이너 0. ADR 0054 정합)

| 컨테이너 | 언어 | 책임 |
|---|---|---|
| `api` | Kotlin/JVM | REST·STOMP·Swagger·웹 진입점 + 외부 어댑터 (KIS·Yahoo·DART·Naver) |
| `batch` | Kotlin/JVM | 스케줄 잡·일/주 작업 |
| `consumer` | Kotlin/JVM | Kafka consumer·KIS WS·실시간 처리 |
| `frontend` | TS/React | UI·LWC 차트 |
| `postgres` | (인프라) | 영속 |
| `redis` | (인프라) | 캐시 |
| `kafka` | (인프라) | 이벤트 백본 |
| `mcp` (W4) | Kotlin | MCP 서버 sidecar (1차는 api 내부 endpoint 옵션) |

### 6-2. 영속 저장 (PostgreSQL + Flyway 단일 인스턴스)

| 테이블 | 영역 | 신설/현행 | Flyway |
|---|---|---|---|
| `instrument` · `instrument_alias` | T-IM1 | 신설 | V12 |
| `corp_action` | T-CA1 | 신설 | V13 |
| `candle` (raw + adjusted) | T-MS1·T-CA1 | 현행 + adjusted 컬럼 | V14 |
| `market_calendar` | T-MS1 | 신설 | V10 |
| `news` · `news_sentiment` | post-MVP | 현행 | (현행 V?) |
| `strategy` · `strategy_dsl_yaml` | T-DSL | 신설 (현행 placeholder 본격화) | V15 |
| `screener_query` · `screener_result` | T-SC | 신설 | V16 |
| `signal` | T-SIG | 신설 | V17 |
| `ai_comment_log` (코멘트 audit, 매매 영향 0) | W4 | 신설 | V20 |
| `prompt_template` | W4 | 신설 | V20 |
| `orders` · `order_events` (현행) + `order_reject_audit` (트랙2) | 트랙1·T-MS1 | 현행 + V11 | V11 |
| `position` · `pnl_daily` | T-RISK·T-PERF | 신설 | V18 |
| `risk_event` | T-RISK | 신설 | V18 |
| `trading_journal` · `chart_snapshot` | T-J | 현행 | (현행 V?) |
| `users` · `user_trading_config` · `kill_switch_state` · `daily_order_counter` | 트랙1 | 현행 V9 | V9 |
| `mcp_audit` (W4 호출 로그) | W4 | 신설 | V21 |

### 6-3. Redis Key 매트릭스

| key 패턴 | 용도 | TTL | 트랙 |
|---|---|---|---|
| `quote:last:{code}` | last tick | 7일 | T-MS1 |
| `quote:lastbusinessday:{code}:{yyyyMMdd}` | D+N 보존 | 14일 | T-MS1 |
| `session:cache:current` | session resolver 캐시 | 60초 | T-MS1 |
| `instrument:search:{prefix}` | 자동완성 | 1일 | T-IM1 |
| `instrument:list:{market}` | universe | 1일 | T-IM1 |
| `indicator:cache:{code}:{interval}:{ind}:{params}` | 지표 결과 | 5분(분봉)/1일(일봉) | T-IND |
| `screener:result:{queryHash}` | 스크리너 | 5분 | T-SC |
| `signal:active:{code}` | 활성 시그널 | 5분 | T-SIG |
| `position:current:{userId}` | 현재 포지션 | 30초 | T-RISK |
| `kis:token:{userId}` | KIS access token | 23h | 트랙1 |
| `ai:prompt:cache:{templateHash}` | LLM 응답 (W4 옵션) | 5분 | W4 |

### 6-4. Kafka 토픽 매트릭스

| 토픽 | producer | consumer | retention | 트랙 |
|---|---|---|---|---|
| `market.tick.v1` | consumer (WS·Mock·Fallback) | api (STOMP) | 1h | 현행 + T-MS1 |
| `market.candle.v1` | batch · consumer | consumer (지표 갱신) | 1d | T-IND |
| `market.session.v1` | consumer | api (STOMP) | 1d | T-MS1 |
| `watchlist.changed.v1` | api | consumer (WatchlistIngestionWorker) | 7d | T-W1·T-W2 (ADR 0028) |
| `instrument.updated.v1` | batch | consumer (캐시 invalidate) | 7d | T-IM1 |
| `corp_action.applied.v1` | batch | consumer (candle 재조정) | 30d | T-CA1 |
| `news.crawled.v1` | api | consumer | 7d | post-MVP |
| `screener.result.v1` | consumer/batch | consumer (시그널), api (STOMP) | 1d | T-SC |
| `signal.generated.v1` | consumer | consumer (order intent), api (STOMP) | 7d | T-SIG |
| `order.intent.v1` | consumer (signal), api (수동) | consumer (KisOrderSender) | 7d | 트랙1 |
| `order.executed.v1` | consumer | api (STOMP), consumer (journal) | 30d | 트랙1 |
| `order.rejected.v1` | consumer | api (STOMP) | 30d | T-MS1 |
| `journal.recorded.v1` | consumer | api (STOMP fanout) | 30d | T-J |
| `risk.event.v1` | consumer | api (STOMP), consumer (KillSwitch trigger) | 30d | T-RISK |
| `position.reconciled.v1` (ADR 0041) | batch | (audit) | 7d | T-RECON·T-O |
| `position.drift.v1` (ADR 0041) | batch | consumer(KillSwitchAutoToggler·NotificationDispatcher), api(STOMP) | 30d | T-RECON·T-O |
| `risk.killswitch.v1` (ADR 0043) | api·batch·consumer | api(STOMP fanout)·consumer(NotificationDispatcher) | 30d | T-RISK·T-O |
| `risk.violation.v1` (ADR 0044) | consumer (Guard chain) | consumer(NotificationDispatcher), api(STOMP) | 30d | T-RISK·T-NOTIFY |
| `kis.token.expired.v1` (ADR 0044) | api·consumer (KisOAuthAdapter) | consumer(NotificationDispatcher) | 30d | T-W2·T-NOTIFY |
| `system.startup.v1` (ADR 0045) | api·batch·consumer (ApplicationReadyEvent) | consumer(NotificationDispatcher) | 7d | 횡단·T-NOTIFY |
| `system.shutdown.v1` (ADR 0045) | api·batch·consumer (SmartLifecycle.stop) | consumer(NotificationDispatcher) | 7d | 횡단·T-NOTIFY |
| `notification.sent.v1` (ADR 0044) | consumer (NotificationDispatcher) | (audit only) | 30d | T-NOTIFY |
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
| **AI Option Console** | inactive (default) | inactive (default) — `MAGICJAR_AI_ENABLED=true` 시 활성 | inactive (default) | inactive (default) |
| **AiCommentProvider** (Anthropic·Gemini·OpenAI) | n/a | toggle ON 시 호출 + 5분 cache | toggle ON | toggle ON |
| **MCP Server** (W4) | inactive | toggle ON 시 활성 (localhost) | toggle ON | toggle ON |

### 7-2. AI 호출 토글 (비용·속도 제어)

- `MAGICJAR_AI_ENABLED=false` (default 모든 profile) — AI 옵션 사용 안 함, LLM 호출 0
- `MAGICJAR_AI_ENABLED=true` + `MAGICJAR_AI_PROVIDER=anthropic|gemini|openai` — 사용자 트리거 시 호출
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
| **W1** — 데이터 기반 (오프라인 1순위) | 종목 마스터·시세 fallback·세션·corp action | T-MS1 (트랙2) · T-IM1 · T-CA1 |
| **W2** — 분석 도구 | 80지표·57패턴·DSL·Strategy Builder UI | T-IND · T-PAT · T-DSL · T-SB |
| **W3** — 자동 매매 본체 (룰만, AI 호출 0) | 백테스트·screener·signal·risk·order 통합·perf | T-BT · T-SC · T-SIG · T-RISK (T-O 트랙1 완료 + 통합) · T-PERF |
| **W4** — AI 옵션 + MCP 서버 | 사용자 트리거 AI 코멘트·기업 분석·MCP server expose | T-AI · T-MCP |
| **W5** — 사용자 학습 + 일원화 | 데이터 흐름·아키 의도 검증·교육·LWC 확장 시각화 | T-LWC (LWC 확장) · T-LEARN (사용자 학습) |

총 ~14 트랙. 본 마스터의 W1~W3 완료 시점이 "룰 기반 본체 풀 가동". W4·W5는 옵션 + 마무리.

---

## §9. 비범위·후속 (post-MVP)

| 항목 | 사유 | 시점 |
|---|---|---|
| **TradingView Advanced 통합** | 라이선스 실현성 낮음 (결제만으로 안 됨, 무료 신청 거절 가능). LWC 확장 + 자체 도형/주석/패턴으로 종결 (T-LWC). | 폐기 — 재검토 안 함 |
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

## §10. 핵심 ADR 인덱스 (0001~0056 — 발행 완료)

상세 본문은 `_workspace/arch/adr/<NNNN>-*.md`. 분류 카테고리는 `_workspace/arch/adr/README.md` §1~§10-4 정합. 본 §10은 한 줄 요약 인덱스 — 신규 ADR 발행 시 본 표 + README 동시 갱신 (ADR 0030 SoT 운영 규약).

| ADR | 제목 | 카테고리 | Wave/시점 |
|---|---|---|---|
| 0001 | 시스템 비전 — 룰 본체 + AI 옵션 + MCP 서버 미래 | 비전 | 베이스 |
| 0002 | 언어·프레임워크 (Kotlin 2.0 / Spring Boot 3.3 / JDK 21 / Gradle KTS) | 기술 스택 | 베이스 |
| 0003 | 증권사 API로 한국투자증권 KIS OpenAPI 선택 | 기술 스택 | 베이스 |
| 0004 | MSA 모듈 재구성 (4 JVM + frontend) | 기술 스택 | 베이스 |
| 0005 | 하이브리드 런타임 — api / batch / consumer 3 JVM | 기술 스택 | 베이스 |
| 0006 | PostgreSQL 단일 + 스키마 분리 | 기술 스택 | 베이스 |
| 0007 | Kafka 이벤트 백본 | 기술 스택 | 베이스 |
| 0008 | Docker 표준 — 모듈별 Dockerfile + compose 통합 기동 | 기술 스택 | 베이스 |
| 0009 | 프론트엔드 SPA (React + TS + Vite + LWC v4) | 프론트엔드 | 베이스 |
| 0010 | Multi-tenant 구조 | 도메인 본체 | W1 |
| 0011 | 주문 실행기 3-mode + 안전 게이트 | 도메인 본체 | W3 |
| 0012 | 매매일지 아키텍처 | 도메인 본체 | W3 |
| 0013 | Market Session FSM (KRX 영업일·장중·장외·휴장) | 시장 데이터 | W1 |
| 0014 | 시세 데이터 Fallback 3-tier (live → cache → last-day-close) | 시장 데이터 | W1 |
| 0015 | REJECTED Audit Trail | 시장 데이터 | W1 |
| 0016 | KIS REST/WS HTTP 정밀화 | 시장 데이터 | W1 |
| 0017 | Instrument Master 1급 시민 | 시장 데이터 | W1 |
| 0018 | Corp Action raw + adjusted | 시장 데이터 | W1 |
| 0019 | 캔들 저장 정책 (Watchlist 기반 + Yahoo 분봉 DB 영속) | 시장 데이터 | W1 |
| 0020 | KIS Strategy Builder 흡수 = 명세 port | 전략·신호·리스크 | W2 |
| 0021 | Indicator Source 추상화 | 전략·신호·리스크 | W2 |
| 0022 | Strategy DSL = .kis.yaml 호환 superset | 전략·신호·리스크 | W2 |
| 0023 | Signal Engine = 룰 매칭 only (AI 호출 0) | 전략·신호·리스크 | W3 |
| 0024 | Risk Guard chain 6단 | 전략·신호·리스크 | W3 |
| 0025 | LWC 확장 + 자체 도형/주석/패턴 | 시각화·AI·MCP | W5 |
| 0026 | AI 옵션 보조 (사용자 트리거 한정 + 분석 코멘트) | 시각화·AI·MCP | W4 |
| 0027 | MCP 서버 미래 노출 | 시각화·AI·MCP | W4 |
| 0028 | Watchlist 기반 Ingestion Worker (1~5초 polling) | Watchlist·차트 데이터 | W1 |
| 0029 | 당일 진행 중 캔들 Redis 캐시 | Watchlist·차트 데이터 | W1 |
| 0030 | 마스터 아키텍처 = 단일 진실원 (SoT) | 메타·운영·하네스 | 베이스 |
| 0031 | 베이스라인 태그 + release/v* immutable 보존 브랜치 | 메타·운영·하네스 | 베이스 |
| 0032 | Husky v9 git hooks (pre-commit author whitelist + pre-push) | 메타·운영·하네스 | 베이스 |
| 0033 | 단일 worktree + 순차 작업 정책 | 메타·운영·하네스 | 베이스 |
| 0034 | 머지 흐름 단순화 (`agent/* → main` rebase) | 메타·운영·하네스 | 베이스 |
| 0035 | 에이전트별 모델 차등 + 에스컬레이션 | 메타·운영·하네스 | 베이스 |
| 0036 | Orchestrator Meta Rules (git-author + task-file-system + architect-as-orchestrator) | 메타·운영·하네스 | 베이스 |
| 0037 | KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷) | 외부 API·UI 흐름 | W1 |
| 0038 | KIS OAuth 24h 토큰 + Redis 분산락 공통 모듈 | 외부 API·UI 흐름 | W1 |
| 0039 | 종목 마스터 갱신 Cron (KIS 갱신 시각 +5분) | 외부 API·UI 흐름 | W1 |
| 0040 | 관심종목 우선 UI 흐름 (Strategy Builder → 발굴 → Watchlist → 상세) | 외부 API·UI 흐름 | W1 |
| 0041 | Broker Reconciliation Job (KIS 잔고 ↔ 내부 position 정합) | 자동매매 fitness | W1·W3 |
| 0042 | Position Bootstrap & Recovery (앱 시작·WS 재연결 시 잔고 복구) | 자동매매 fitness | W1 |
| 0043 | KillSwitch 행동 정의 (ON 시 신호·주문·통지·재시작 정책) | 자동매매 fitness | W3 |
| 0044 | Notification System (Slack webhook 1차 + Kafka 트리거 + outbox fallback) | 운영·UX fitness | W3 (T-NOTIFY) |
| 0045 | Graceful Shutdown & In-flight 처리 (SIGTERM · Kafka manual commit · idempotency) | 운영·UX fitness | W1 (횡단) |
| 0046 | Onboarding & Credential Registration (테이블 3종 분리 · 마스터 키 단일 예외) | 운영·UX fitness | W1 ★ (T-ONBOARD) |
| 0047 | DSL Evaluation Sandbox (정적 검증 + 런타임 timeout/CPU budget + thread pool 격리 + auto-disable) | DSL 안전성 | W2 (T-DSL) · W3 (T-SIG·T-BT) |
| 0048 | KIS WebSocket 41건 구독 한도 + 우선순위 정책 (slot 1+30+10·priority eviction·polling fallback) | 시장 데이터 | W1 (T-W2 진입 시 발행) |
| 0049 | GitHub Actions = release 트리거 한정 (일상 검증은 pre-push hook 단독) | 메타·CI/CD | 횡단 |
| 0050 | CHANGELOG 슬롯 채번룰 (architect 사전 발행 + sub-agent in-place edit) | 메타·하네스 | 횡단 |
| 0051 | Spring Data Repository default fun 함정 (회피 패턴) | 메타·하네스 | 횡단 (backend-core·market-data 핵심 risk) |
| 0052 | Baseline Documentation Cycle (skill + slash + 스크립트 4종) | 메타·하네스 | 횡단 (release 사이클·문서화) |
| 0053 | Living Policy Docs (현행 권위 단일 위치 + INDEX·policies/ 분리) | 메타·하네스 | 횡단 (문서 정합·인지 비용·토큰 효율) |
| 0054 | pattern-engine 라이브러리 모듈 분리 (domain 순수성 보존 + api·consumer 공유) | 기술 스택 | 베이스 (PR-41 채택) |
| 0055 | Strategy Builder UI 컴포넌트 스택 (B+C-lite 하이브리드 — 트리 폼 빌더 + textarea DSL 토글, 의존 추가 0) | 프론트엔드 | W2 (T-SB) |
| 0056 | Backtest Engine Architecture (룩어헤드 slice-by-time 가드 + 세·수수료·슬리피지 모델 open extension + ADR 0047 sandbox 통합 + abort quota 50 + 1차 stateless) | 전략·신호·리스크 | W3 (T-BT) |

### 후속 예정 (Proposed — `_workspace/arch/adr/README.md` §12 정합)

| 후보 번호 | 제목 (예정) | 진입 트랙 |
|---|---|---|
| 0057 | 57 캔들 패턴 표준 (본문 ADR로 격상 검토 — T-PAT 머지 정합) | T-PAT |
| 0058 | Screener 표준 | T-SC |
| 0059 | Performance 표준 | T-PERF |
| 0060 | Backtest 결과 영속 (backtest_run · backtest_trade 테이블 + GET endpoint) | T-BT 후속 (backlog §27-2) |

---

## §11. 비범위 (TradingView Advanced·외국인 수급·실시간 분봉 backfill 등)

위 §9에 일괄 정리.

추가:
- **AI 의사결정 흐름** (1차안 ADR 0042) — **명시 폐기**. v2 베이스라인엔 AI Decision Layer 자체 없음.
- **AI 비용 모니터링 자체 페이지** — W4 후속 (1차는 ai_comment_log 테이블 단순 audit으로 충분)

---

## §12. 사용자 결정 누적 카드

v1·v2 검토 시점에 누적된 결정 항목과 v3.0.0 베이스라인 컷(2026-05-05) 결과를 함께 기록한다. 트랙 진입 시 신규 결정 필요 항목이 생기면 본 절에 누적 + 사용자 답변 시 결과 표시.

### 12-1. v1·v2 잔여 결정 (모두 결정됨/자연 해소)

| # | 결정 항목 | 상태 | 결과·해소 사유 |
|---|---|---|---|
| 1 | 새 v2 ADR 발행 시점 (W1 진입 직전 일괄 vs 트랙별 부분) | 결정됨 | ADR 0001~0040 베이스 일괄 발행. 트랙 진입 시 ADR 0041~ sequential 추가. |
| 2 | Deprecated/Superseded 13건 처리 (삭제 / `_archive/` / 본문 헤더만) | 자연 해소 | v3.0.0 베이스라인 컷에서 PoC 잔재 일괄 정리. ADR 디렉토리 1~40만 보존. |
| 3 | release/v1 처리 (v1 삭제 / v0만 보존) | 자연 해소 | release/v* 브랜치 immutable 보존 정책 (ADR 0031). v3.0.0 베이스라인 컷 완료. |
| 4 | MCP server 1차 위치 (sidecar 컨테이너 vs api 모듈 내 endpoint) | 보류 (W4) | T-MCP 진입 시 결정. tracks-roadmap §7 결정 항목 #6 정합. |
| 5 | AI provider 1차 (Anthropic / Gemini free / OpenAI) | 보류 (W4) | T-AI 진입 시 결정. .env.example default `MAGICJAR_AI_PROVIDER=anthropic`. |
| 6 | Indicator source 1차 plug-in (KIS만 / NativeSource 동시) | 보류 (W2) | T-IND 진입 시 결정. ADR 0021 source 추상화 인터페이스 확정. |

### 12-2. v3 베이스라인 컷 후 신규 결정 (트랙 진입 시 채움)

| # | 결정 항목 | 상태 | 영향 트랙 |
|---|---|---|---|
| (트랙 진입 시 누적) | | | |

---

## §13. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 (v1) | 1차안 (architect, opus 4.7 1M) — "AI quant trading platform" 가정. 사용자 비전 정정으로 폐기. |
| 2026-05-03 (v2) | **풀 재작성** — 룰 기반 본체 + AI 옵션 + MCP 서버 미래. 18 컴포넌트 4 영역. 새 ADR 0031~0047 17건. (architect, opus 4.7 1M) |
| 2026-05-07 (PR-2) | SoT 동기화 — §10 ADR 인덱스 0001~0040 풀 재작성, §2 헤더 19 컴포넌트 정정, §6 Kafka `watchlist.changed.v1` topic 추가, §12 사용자 결정 누적 카드 정리, AI 변수명 `MAGICJAR_AI_OPTION_ENABLED` → `MAGICJAR_AI_ENABLED` 통일. (architect) |
| 2026-05-07 (PR-3a) | 자동매매 fitness 갭 Critical 3건 ADR 0041·0042·0043 신규 발행 정합 — §10 인덱스 3행 추가, §3-1 [0] Position Bootstrap 단계 prepend + 부수 흐름 3건 명시, §6 Kafka topic 3건 추가(`position.reconciled.v1`·`position.drift.v1`·`risk.killswitch.v1`), §10 후속 예정 0044~0047 시프트. (architect) |
| 2026-05-07 (PR-3b) | 운영·UX fitness 갭 3건 ADR 0044·0045·0046 신규 발행 정합 — §10 인덱스 3행 추가(0044 Notification·0045 Graceful Shutdown·0046 Onboarding), §10 헤더 0001~0046 갱신, §6 Kafka topic 5건 추가(`risk.violation.v1`·`kis.token.expired.v1`·`system.startup.v1`·`system.shutdown.v1`·`notification.sent.v1`) + position.drift.v1·risk.killswitch.v1 consumer에 NotificationDispatcher 합류, §3-1 부수 흐름 3건 추가(Notification·Graceful Shutdown·Onboarding Setup), §10 후속 예정 0047~0050 시프트. (architect) |
| 2026-05-07 (PR-3c) | DSL 안전성 갭 ADR 0047(DSL Evaluation Sandbox) 신규 발행 정합 — §10 인덱스 1행 추가, §10 헤더 0001~0047 갱신, §3-1 부수 흐름 1건 추가(DSL Sandbox — static 검증·runtime timeout·strategy-eval-pool 격리·auto-disable·`risk.violation.v1` type 확장), §10 후속 예정 0048~0051 시프트. Kafka 신규 topic 0건(기존 `risk.violation.v1` payload type 확장만). (architect) |
| 2026-05-07 (PR-4) | v1/v2 잔여 grep 8회 검증 — 실질 잔재 0건(모두 의도된 historical context — 0014 T1/T2/T3 tier 표기·0004/0005/0006/0031/0032 Phase 역사적 마커·0020/0021/0027/0030 1차안 폐기 마커). §10 후속 예정에 ADR 0048 후보 prepend(KIS WS 41건 구독 한도 + 우선순위 정책 — PR-3b architect 발굴 갭) + 기존 0048~0051을 0049~0052로 시프트. v3.0.1 컷 준비 — CHANGELOG entry 추가, ADR 47건(0001~0047) + 파일명/헤더 일치 + README §12 정합 모두 GREEN. (architect) |
| 2026-05-07 (PR-8) | 메타 정리 4건 — ADR 0049(GitHub Actions = release 트리거 한정 — 일상 검증 pre-push 단독)·ADR 0050(CHANGELOG 슬롯 채번룰 — architect 사전 발행 + sub-agent in-place edit) 신규 발행. ADR 0040 endpoint 단수→복수 정정(`/api/watchlist` → `/api/watchlists` — RESTful 관례 + PR-7 T-W1 spec lock 정합) + 정합 차원 ADR 0019·0028·candle-data-policy-v2 동시 갱신. §10 인덱스 0049·0050 추가, 후속 예정 기존 0049~0052 → 0051~0054 시프트(0048 KIS WS 41건은 그대로). agent SKILL 보강(architect 슬롯 발행 의무 + sub-agent fallback 룰 + magicjar-orchestrator 운영 절차). `.github/workflows/ci.yml` 삭제 + `release.yml` 재구성. `.husky/pre-push` 보강(frontend 변경 시 pnpm typecheck/build 조건부 실행). (architect) |
| 2026-05-07 (PR-9) | T-W2 진입 직전 설계 산출물 — `_workspace/specs/pr-9-w2-design.md` (slot 분배 1+30+10·priority eviction·30s polling 전환·Yahoo 7 interval×1회·MarketSession 1차 stub LOCK), `pr-9-dispatch-spec.md` (PR-9a backend-core·PR-9b market-data 분배 + 인터페이스 동결 + KIS WS PIPE 메시지 KIS-first 의무), `pr-9-conflict-matrix.md` (병렬 권장 — modify-shared 3건 모두 사전 정책 해소). ADR 0048 본문 발행(`0048-kis-ws-41-subscription-limit.md`) — README §12 후속 예약 → §5 시장 데이터 카테고리 이동, master §10 인덱스 갱신(후속 예정에서 제거). ADR 0050 첫 적용 — CHANGELOG slot row 3건(architect 1·backend-core TBD·market-data TBD) 사전 발행. (architect) |
| 2026-05-11 (PR-47) | W3 T-BT 진입 직전 설계 산출물 — `_workspace/specs/pr-47-w3-t-bt-design.md` (마스터 §B.9 정독 인용 + 도메인 모델 5건 LOCK BacktestRequest·Result·Position·Trade·Performance + REST endpoint POST /api/backtest/run LOCK + 룩어헤드 slice-by-time 가드 + 세·수수료·슬리피지 모델 인터페이스 3종 + ADR 0047 sandbox 통합 + abort quota 50 + 성과 7지표 식 + KIS-first raw 출처 인덱스), `pr-47-w3-t-bt-plan.md` (시나리오 A 단일 sub-agent backend-core 채택 + LOC ~1500 + wallclock ~5h + frontend backlog §27 합류), `pr-47-dispatch-spec.md` (PR-48 backend-core 단독 LOCK + 인터페이스 동결 + KIS-first raw 출처 KDoc 인용 의무 + fitness gap 7건 검증 의무), `pr-47-conflict-matrix.md` (modify-shared 0건 — add-only 100% + 빌드 그래프 변경 0 — 단독 머지 안전). ADR 0056 신규 발행 — Backtest Engine Architecture (단일 인터페이스 + open extension 모델 3종, 룩어헤드 slice-by-time, 세·수수료 raw 출처 의무, ADR 0047 strategy-eval-pool 공유, abort quota 50, 1차 stateless). §10 인덱스 ADR 0056 row 추가 + §12 후속 예정 시프트(기존 0056 캔들 패턴 → 0057, 0057 Backtest → 0056으로 격상 통합). ADR README §6 전략·신호·리스크 5건 → 6건. CHANGELOG slot sub-agent 1건(backend-core PR-48 TBD) 사전 발행. v1/v2 잔재 검증 — find Backtest 0건 GREEN. (architect) |
