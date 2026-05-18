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
| A.2 | **Market Data Pipeline (시세 수집·캐시·세션)** | **Watchlist 기반 Ingestion Worker (1~5초 polling)** — KIS WS tick·체결·호가 + KIS REST 1d/1wk/1mo/1y 전체 기간 + KIS REST 1m 당일 한정 Redis only + 당일 진행 중 캔들 Redis 휘발 캐시 (ADR 0057 — Yahoo Finance 폐기, 2026-05-11). **prod credential 단일 채널** (정보 수집 모의/실전 분기 0 — 사용자 명시 정책 정정 2026-05-11). **장 상태 FSM + fallback** (live → cache → last-day-close). 5m·15m·30m·90m·1h은 **1m 합산 가공 방향 확정** (backlog §31 트랙). | 가능 (캐시·last-day-close · MockTickGenerator) | T-W1·T-W2 |
| A.3 | **Corporate Action & Adjusted Price** | 액면분할·증자·배당·합병으로 인한 가격 점프 보정. raw + adjusted 양 컬럼. retroactive 재계산. | 가능 (일별 batch + 정적 시드) | T-CA1 |
| A.4 | **News & Disclosure (뉴스·공시) — ADR 0075 Accepted 2026-05-15** | DART 공시 (cron 1분 강화 + `is_correction` 분류 + Flyway V32 `disclosure` 영속 + Kafka `disclosure.new.v1` retention 7d) + Naver Open API search/news.json (Flyway V33 `news_article` 영속 + `<b>` strip + Kafka `news.collected.v1` retention 7d + Watchlist 우선 polling 분당 17건 hard limit). 종목 상세 sidebar NewsPanel + DisclosurePanel polling 60s (STOMP 0 LOCK · 의제 6-1). REST 2 endpoint `GET /api/symbols/{code}/news` + `/disclosures` Swagger 4단. KIS endpoint 호출 0 (ADR 0057 시세 단일 정합 + 뉴스 보조 §B-3 OK). AI 호출 0 (감성 분석 T-AI 흡수 backlog §62). 외부 source 끊겨도 **본체 흐름 차단 0**. | 부분 (Naver/DART 미수집 시 본체 영향 0 + frontend "데이터 없음" 한국어) | **T-NEWS (ADR 0075 Accepted, v3.6.7 patch 사이클 진입)** |

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
| C.10 | **Screener (종목 발굴)** | universe(instrument 영속) × DSL 조건식 → 후보 + 랭킹(4 기준 multi-criteria fixed weights). **Redis cache 30m + 옵션 영속 V28(env toggle)**. **1차 inline 동기 평가 모드** (universe ~3000 inline ~5s) + 일별 batch cron은 post-MVP backlog §55. **KIS endpoint 호출 0 LOCK** (영속 candle + instrument reuse). Strategy Builder "전략으로 종목 찾기" 버튼 + ScreenerResultModal 합류. ADR 0071 정밀 명세 정합. | 가능 (영속 candle + 룰만) | T-SC (ADR 0071 Accepted 2026-05-14) |
| C.11 | **Signal Engine (시그널)** | 실시간 watch list × 활성 전략 → BUY/SELL/HOLD + 강도 + reason. **DSL 평가만, AI 호출 0**. | 가능 (in-process 룰) | T-SIG |
| C.12 | **Auto Order Execution (주문 실행)** | 시그널 → 위험 가드(ADR 0067 ALLOWED) → KisOrderHttpAdapter(TTTC0012U·TTTC0011U) → KIS API → KisExecutionWsListener(H0STCNI0 체결통보) → audit. MAGICJAR_MODE × MAGICJAR_ALLOW_REAL × user_account.real_trading_consent_at 3중 게이트 (실계좌). 분할 체결 1:N + 사용자 수동 취소(TTTC0013U). | 가능 (MOCK 환경 — KIS VPS endpoint + H0STCNI9) | T-O (ADR 0068 plan 발행 2026-05-12, 사용자 결정 대기) |
| C.13 | **Risk & Position Guard (위험 관리)** | Guard chain 6단 (KillSwitch·Session·DailyLimit·PositionLimit·DrawdownStop·PriceSanity). 사이징(Kelly·Fixed Fractional). | 가능 (in-process 정책) | T-RISK |
| C.14 | **Performance Loop (성과 통계)** | 일별/주별 P&L·MDD·승률·평균 손익비. **5 지표 fixed LOCK** (총 P&L · 일별 P&L · MDD · 승률 · 평균 손익비). **batch DailyPerformanceJob 매일 00:30 KST cron** + inline aggregate fallback + Redis cache TTL 1h. **Sharpe·Sortino·CAGR backlog §61** (post-MVP). **AI 호출 0 LOCK** + W4 회고 코멘트 backlog §62. ADR 0072 정밀 명세 정합. | 가능 (영속 데이터만) | T-PERF (ADR 0072 Accepted 2026-05-14) |

### D. 학습·운영·UI 영역 (5 컴포넌트)

| # | 영역 | 책임 | **오프라인 동작** | 트랙 |
|---|---|---|---|---|
| D.15 | **Watchlist (관심종목)** | UI 1순위 진입점. Strategy Builder 발굴 → 관심등록 → 상세 보기 흐름. 모든 외부 시세 수집의 단위 (Watchlist 풀). | 가능 (DB only) | T-W1 (최우선) |
| D.16 | **Trading Journal (매매일지)** | order·signal·risk·reconcile·position 5 Kafka 이벤트 통합 시계열 영속(trading_journal 테이블) + 사용자 메모(journal_note 1:N) + 멱등성 UQ(source_table+source_id+event_type). 차트 스냅샷 자동 영속은 backlog §60 (post-MVP). **AI 호출 0 LOCK** + W4 회고 코멘트 backlog §62. ADR 0072 정밀 명세 정합. | 가능 (영속 only) | T-J (ADR 0072 Accepted 2026-05-14) |
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
[3] **Screener (T-SC ADR 0071)** — 1차 inline 동기 평가 (사용자 트리거 "전략으로 종목 찾기" → POST /api/screener/evaluate → universe(instrument 영속) × DSL → 후보 + 4 기준 multi-criteria 랭킹 + Redis cache 30m). DailyScreenerJob cron 다음 영업일 09:00은 post-MVP backlog §55 (비동기 모드 합류).
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
- **Onboarding Setup (ADR 0046 + 0069)** — 첫 부팅 시 활성 user 0명 idle. localhost:3000 진입 → 셋업 마법사 7-step → KIS REST `/oauth2/tokenP` 검증 → AES-256-GCM 암호화 → user_account + user_kis_credential 영속 → UserOnboardedEvent → WatchlistIngestionWorker polling 시작. 운영 진입 후 키 변경·DART/Slack 추가·실거래 동의 토글은 **SettingsModal** (AppHeader ⚙️ 진입점, ADR 0069 §1 책임 분리 + §3 1차 scope 3 section + §4 신규 API 키 추가 5 step 표준 절차)
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

### 5-1B. indicator-impl 라이브러리 모듈 (Spring Context — Boot main 0)

ADR 0083 정합 — 2026-05-18 신설. domain port `IndicatorSource` 구현 빈을 보유하는 라이브러리 jar. api·consumer 두 모듈이 동일 빈을 공유하기 위한 분리 (pattern-engine 패턴 1:1 정합).

```
indicator-impl/src/main/kotlin/kr/spdaewoon/magicjar/indicator/impl/
├── NativeIndicatorSource              ← @Component (priority=50, 5종: ma·ema·rsi·macd·bb)
├── KisStrategyBuilderSource           ← @Component (priority=100, 80지표)
├── native/                            ← Native* helper 5 파일 + IndicatorMath
└── kis/                               ← Indicator{Trend·Momentum·Volume·Volatility·Oscillator·Other} + KisIndicatorMath
```

의존: `indicator-impl` → `domain`만. `api`·`consumer` → `indicator-impl`. ADR 0004 §의존 방향 정합 보존(api·consumer 직접 의존 0). `LiveCandleMerger`·`LiveCandleMergingSource`·consumer 측 `IndicatorEngineConfig`(LiveCandleMergingSource wrap)는 consumer 모듈 잔류(자본 보호 hotfix 2026-05-15 Redis key `candle:today:{code}:1d` 정합 보존).

빌드: `kotlin("jvm") + kotlin("plugin.spring") + spring-context`. **Spring Boot main·bootJar 0** (라이브러리 jar). 컨테이너 0.

### 5-2. 응용 레이어 분배 (api·batch·consumer·frontend)

(아래 표는 응용 모듈 4개 분배 — pattern-engine·indicator-impl 라이브러리는 §5-1A·§5-1B 별도)

| 영역 | api | batch | consumer | frontend |
|---|---|---|---|---|
| Instrument | REST 조회·검색 | 일별 sync | (없음) | 검색 자동완성 |
| Market Data | candles REST·STOMP · KIS REST backfill 어댑터 (ADR 0057) | 일봉 backfill 잡 (KIS) | KIS WS·tick·session·fallback | 실시간 시세·차트 |
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

(JVM 모듈은 6개 — `domain`·`pattern-engine`·`indicator-impl`은 라이브러리 jar로 컨테이너 0. ADR 0054·0083 정합)

| 컨테이너 | 언어 | 책임 |
|---|---|---|
| `api` | Kotlin/JVM | REST·STOMP·Swagger·웹 진입점 + 외부 어댑터 (KIS·DART) — ADR 0057 (2026-05-11): Yahoo Finance 폐기 + 네이버 캔들 fetch 0건 |
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
| `screener.result.v1` (post-MVP — ADR 0071 v3.6.3는 동기 inline 채택, 비동기 모드는 backlog §52) | consumer/batch | consumer (시그널), api (STOMP) | 1d | T-SC (post-MVP) |
| `signal.generated.v1` | consumer | consumer (order intent), api (STOMP) | 7d | T-SIG |
| `order.intent.v1` | consumer (signal), api (수동) | consumer (KisOrderSender) | 7d | 트랙1 |
| `order.executed.v1` | consumer | api (STOMP), consumer (journal) | 30d | 트랙1 |
| `order.rejected.v1` | consumer | api (STOMP) | 30d | T-MS1 |
| `journal.recorded.v1` | consumer | api (STOMP fanout) | 30d | T-J |
| `risk.event.v1` | consumer | api (STOMP), consumer (KillSwitch trigger) | 30d | T-RISK |
| `reconcile.run.v1` (ADR 0041·0070) | consumer (BalanceReconcileJob `@Scheduled`) | api(StompReconcileNotifier audit) · consumer(NotificationDispatcher 옵션 P2) | 7d | T-RECON |
| `reconcile.drift.v1` (ADR 0041·0070 — 기존 `position.drift.v1` 명명 갱신) | consumer (BalanceReconcileJob) | consumer(KillSwitchAutoToggler·NotificationDispatcher), api(StompReconcileNotifier STOMP fanout) | 30d | T-RECON·T-O |
| `killswitch.engaged.v1` (ADR 0043·0070) | consumer (KillSwitchAutoToggler) · api·batch (manual toggle 경로) | consumer(NotificationDispatcher) · api(STOMP fanout) | 30d | T-RECON·T-RISK·T-O |
| `risk.killswitch.v1` (ADR 0043 — 기존, killswitch.engaged.v1과 정합 검토 의제) | api·batch·consumer | api(STOMP fanout)·consumer(NotificationDispatcher) | 30d | T-RISK·T-O |
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

## §10. 핵심 ADR 인덱스 (0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074·0075·0079·0080 Accepted + 0064·0076·0077·0078·0081·0082 Proposed + 0073 Superseded by 0075 + 0035 Superseded by 0080)

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
| 0019 | 캔들 저장 정책 (Watchlist 기반 + Yahoo 분봉 DB 영속) — **Accepted (Yahoo 의존 부분만 보류, 2026-05-11 노트 — 폐기 아님)** | 시장 데이터 | W1 |
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
| 0057 | **KIS OpenAPI 단일 데이터 출처 (Yahoo Finance·네이버 등 보조 출처 폐기) — 5m~1h은 1m 합산 가공 방향 확정 (backlog §31 트랙)** | 시장 데이터 | W1 (2026-05-11 사용자 결정) |
| 0062 | **5m·15m·30m·90m·1h 합산 가공 — Accepted (2026-05-11 사용자 채택 A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화) — NXT 결합 §9 추가 (ADR 0063 정합, 합산 720/일 베이스 → ~71K rows)** | 시장 데이터 | W1 (2026-05-11 Accepted) |
| 0063 | **NXT/통합거래 매트릭스 cover — Accepted (2026-05-11 사용자 채택 A UN 통합 + 1 08:00~20:00 + threshold 700)** | 시장 데이터 | W1 (2026-05-11 Accepted) |
| 0064 | **Redis boundary fanout + STOMP 실시간 합산 갱신 plan — Proposed (2026-05-12 사용자 결정 대기 — 의제 A·B·C / architect 권장 A-1 5종 별도 Redis + B-1 KIS WS in-process trigger + C-1 5종 별도 토픽)** | 시장 데이터 | v3.3.0 미러스톤 (backlog §34) |
| 0066 | **T-SIG 실시간 시그널 엔진 (룰 매칭 + Kafka boundary listen + 영속 + STOMP fanout) — Accepted (2026-05-12 사용자 채택 A-1·B-1·C-1·D-1·E-1·F-1)** | 전략·신호·리스크 | W3 v3.4.0 마감 (backlog §40 DONE) |
| 0067 | **T-RISK 리스크 관리 (Signal → Risk Gateway consumer → Order intent 의무 게이트 + risk_config·risk_event·position·signal.risk_status 4 도메인 영속 + STOMP 알림 + auto-PAUSE 반복 BLOCKED 10회) — Accepted (2026-05-12 사용자 채택 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1)** | 전략·신호·리스크 | W3 v3.5.0 마감 (backlog §41 DONE) |
| 0068 | **T-O 주문 실행 plan (Risk ALLOWED → KIS REST 매수·매도 + WS H0STCNI0 체결 추적 + 사용자 수동 정정/취소 + 3 테이블 영속 + STOMP 4 event + 멱등성 Redis SETNX + 실계좌 3중 게이트) — Proposed (2026-05-12 사용자 결정 대기 — 의제 A·B·C·D·E·F·G·H 8건 / architect 권장 A-1 env × 2 + 사용자 동의 토글 + B-1 단일 port + adapter 내부 mode 분기 + C-1 시장가/지정가 + D-1 KIS WS H0STCNI0 실시간 + E-1 사용자 수동 only + F-1 3 테이블 분리 + G-1 단일 토픽 4 event discriminator + H-1 Redis SETNX 60s + DB UQ — KIS-first raw 4건 TTTC0012U·TTTC0011U·TTTC0013U·H0STCNI0)** | 전략·신호·리스크 | W3 v3.6.0 후보 (backlog §43) |
| 0069 | **SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 6종 추출 + 미래 신규 API 키 추가 5 step 표준 절차 + 1차 PR scope LOCK (RealTradingConsentSection + KisCredentialSection + SystemConfigSection 3 section) + backlog 의제 10건 — Accepted (2026-05-13 사용자 명시 정책 — dogfooding 결함 발견 응답)** | 운영·UX fitness | W3 T-O 보강 (자본 보호 검증 unblock) |
| 0070 | **T-RECON + T-NOTIFY 묶음 spec/plan — Accepted (2026-05-13 architect 자율 결정 · 사용자 위임 "W3 진행해서 마무리까지하자 W4 진입까지 자율")** — ADR 0041(T-RECON baseline) + ADR 0044(T-NOTIFY baseline) reuse + 정밀 명세 §1~§15 LOCK + Phase 분리 LOCK(T-RECON Phase 1 strategy-engine → Phase 2 backend-core → T-NOTIFY Phase 1 backend-core 직렬). KIS-first raw 4건(TTTC8434R·VTTC8434R + CTRP6548R + H0STCNI0 cross-ref). Flyway V25(reconcile_run) + V26(domain enum) + V27(notification_outbox + system_config.notification_slack_webhook). Kafka 신규 2(reconcile.drift.v1·reconcile.run.v1) + killswitch.engaged.v1 + listen 7. REST 3 endpoint. frontend 0 LOCK. 자본 보호 흐름 LOCK(drift → KillSwitch ON → Slack P0 → STOMP). spec 본문: `_workspace/specs/pr-392-recon-notify-spec.md`. | 자동매매 fitness + 운영·UX fitness | W3 v3.6.2 patch (T-RECON·T-NOTIFY 묶음) |
| 0071 | **T-SC Screener spec/plan — Accepted (2026-05-14 architect 자율 결정 · 사용자 위임 동일)** — A-1~H-1 8 의제 LOCK (A-1 동기 inline + Redis cache 30m · B-1 instrument 영속 + 필터 매트릭스 · C-1 strategy-eval-pool reuse + universe screening timeout 500ms · D-1 cache-only + D-2 옵션 영속 env toggle · E-1 multi-criteria 4기준 fixed weights signal 0.40+volume 0.30+atr 0.20+cap 0.10 · F-1 REST 3 endpoint Swagger 4단 · G-1 candle 결손 graceful skip + skippedReasons 매트릭스 · H-1 Strategy Builder 합류 + ScreenerResultModal). Phase 분리 LOCK (Phase 1 strategy-engine domain+consumer → Phase 2 backend-core api+Redis+옵션 Flyway V28 → Phase 3 frontend 직렬). universe=instrument 영속 reuse + candle=영속 reuse → **KIS endpoint 호출 0 LOCK** + AI 호출 0 grep 검증 + mock 합성 0. Flyway V28(screening_run + screening_match, env toggle MAGICJAR_SCREENER_PERSIST=true). Kafka 신규 0. STOMP 신규 0. 사용자 비전 정합 = Strategy Builder "전략으로 종목 찾기" 흐름 단절 회복 + Watchlist 일괄 등록 합류. spec 본문: `_workspace/specs/pr-393-t-sc-spec.md`. | 전략·신호·리스크 | W3 v3.6.3 patch (T-SC 다섯 번째 트랙) |
| 0072 | **T-J + T-PERF 묶음 spec/plan — Accepted (2026-05-14 architect 자율 결정 · 사용자 위임 동일 · W3 마지막 사이클)** — A-1~H-1 8 의제 LOCK (A-1 trading_journal 신규 + 5 Kafka listen 자동 INSERT · B-1 batch + inline aggregate 듀얼 · C-1 5 지표 fixed(총 P&L · 일별 P&L · MDD · 승률 · 평균 손익비) · D-1 performance_summary + pnl_daily 영속 신규 · E-1 REST 5 endpoint Swagger 4단 한국어 · F-1 2 페이지 + AppHeader 메뉴 2 추가 · G-1 5 토픽 listen(order.filled+signal.generated+risk.violation+reconcile.drift+position.updated) · H-1 AI 호출 0 LOCK 일관성). Phase 분리 LOCK (Phase 1 backend-core T-J: domain+Flyway V29(trading_journal+journal_note IF NOT EXISTS+멱등성 UQ source_table+source_id+event_type)+JournalEventConsumer 5 Kafka listen+JournalSummaryComposer 한국어+REST 3 endpoint · Phase 2 backend-core T-PERF: domain+Flyway V30(performance_summary+pnl_daily IF NOT EXISTS)+batch DailyPerformanceJob 매일 00:30 KST cron+inline aggregate fallback+REST 2 endpoint+Redis cache TTL 1h+PerformanceCalculator pure math MDD 알고리즘 NUMERIC(8,4) · Phase 3 frontend: `/journal`+`/performance` 2 페이지+AppHeader 메뉴 2 추가+LWC v4 equity curve+daily P&L bar chart). 모든 데이터 영속 reuse(order·signal·risk·reconcile·position 5 baseline) → **KIS endpoint 호출 0 LOCK** + AI 호출 0 grep 검증 6 디렉토리 + mock 합성 0("매매일지가 비어있습니다" 한국어 graceful). Kafka 신규 publish 0. STOMP 0(polling). Sharpe·Sortino·CAGR 비범위(backlog §61). 차트 스냅샷 자동 영속 비범위(backlog §60). AI 회고 코멘트 W4 backlog §62. 사용자 비전 정합 = ★★★ 운영 회고 의무(자동매매 본체 검증 핵심) + W4 AI 합류 prereq(사용자 메모 → AI prompt context). 검증 시나리오 19건 dogfooding evidence 의무. spec 본문: `_workspace/specs/pr-402-t-j-perf-spec.md`. | 전략·신호·리스크 + 학습·운영·UI | W3 v3.6.4 patch (T-J + T-PERF 묶음, W3 마지막 사이클) |
| 0073 | **T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — Superseded by ADR 0075 (2026-05-15 사용자 결정 8 의제 채택 + 정밀 spec 위임 · 2026-05-14 architect 자율 발행 · 사용자 의제 응답 "뉴스 스크래핑, 공시 정보 실시간 속보 등 주요 뉴스는 시작해?")** — analysis only · 코드 변경 0 LOCK. 옵션 A·B·C·D 매트릭스 (A T-NEWS 별도 트랙 신설 ★★★ architect 권장 · B T-AI 흡수 · C DART 폴링 강화만 · D KIS-first 극단 확장 비권장). 사용자 결정 의제 8건(트랙 옵션·DART 빈도 1분/5분/30초·Naver 키 시점·KillSwitch auto-ON·감성 분석 분리/흡수·UI 위치·Kafka 토픽 매트릭스·진입 시점). architect 권장 A-1+2-1+3-1+4-2+5-1+6-1+7-1+8-1(옵션 A + DART 1분 + Naver Phase 2 진입 시 .env 등록 + Slack 알림만 + T-AI 흡수 + sidebar + 토픽 2분리 + W3 마감 직후 v3.7.0). baseline 발견 매트릭스 6건 (ADR 0010 §5 news·news_sentiment 테이블 명시 + ADR 0026 §38 prompt 컨텍스트 뉴스 명시 + ADR 0069 §F NaverApiSection 후보 + master §A.4 News & Disclosure 영역 + master §B-3 NAVER 옵션 + master §C.13 News 19 컴포넌트). v1/v2 잔재 0 검증. KIS-first §B-3 정합 OK (시세만 KIS LOCK + 뉴스는 보조 OK). AI 호출 0 LOCK(룰 기반 키워드 매칭만 + T-AI 흡수 비범위). Phase 분리 후보(채택 시) Phase 1 DART 실시간(market-data+backend-core) → Phase 2 Naver(backend-core) → Phase 3 frontend panel → Phase 4 RSS 옵션. 자체 추론 risk 1건(Naver Open API 한도 25K/일 raw fetch 미수행) — 채택 시 추가 ADR(0075+) 풀 spec raw fetch 의무 LOCK. backlog §52 등재 (사용자 결정 후 별도 사이클 ADR 신규 발행 + sub-agent 디스패치). | 시장 데이터·외부 API | W3.5 또는 W4 prereq (v3.6.5 analysis only) |
| 0074 | **SettingsModal 풀세트 (KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리 사전 정합) — Accepted (2026-05-14 재발행 · architect 자율 결정 · 사용자 자율 위임 "§44~§45부터 하자")** — 직전 architect dispatch 시 T-NEWS(PR #414 머지)와 본 사이클(PR #415 close) 둘 다 ADR 0073 발행 → ADR 번호 충돌로 PR #415 close → 본 ADR은 0074 재발행 + 결정 내용 변동 0(번호만 갱신). 12 § LOCK — §1 fitness(자본 보호 prereq + 운영 path + 미래 확장 표준) · §2 KIS 키 변경 풀세트(PUT/GET/DELETE + KisAuthValidator 통과 UPDATE atomicity + Redis access_token + kis:approval DEL + kis:key-changed-lock SET 5초 + Kafka system.kis-key-changed.v1 publish + WS graceful reconnect, MOCK만 DELETE 허용 REAL DELETE 금지) · §3 KillSwitch 수동 토글(SettingsModal Section 4 신설 + GET·PUT /api/system-config/kill-switch + ConfirmDangerToggle reuse + 사유 입력 minLength=5 maxLength=500 + 60초 cooldown + killswitch.engaged.v1 schema 확장 engaged:boolean) · §4 OpenAI/Anthropic 키 카테고리(env 유지 + Section 5 UI 0건 1차 + T-AI 사이클 실구현 분리) · §5 Flyway 0건 LOCK(baseline reuse) · §6 REST 신규 5건 + SystemConfigController 신규 · §7 Redis invalidate 매트릭스 LOCK · §8 frontend UI 매트릭스(Section 2 submit 활성화 + Section 4 신설 + ConfirmDangerToggle 실구현 + 5 hook) · §9 Phase 분리 LOCK(Phase 1 backend-core + Phase 3 consumer add-only 병렬 가능 + Phase 2 frontend 직렬) · §10 검증 시나리오 3건 · §11 ADR 0069 §7 backlog 흡수(§A·§D·§G + §E 사전 정합 + §F·§H·§I·§J 잔류) · §12 Rollback 시나리오. AI 호출 0 grep 검증 의무. mock 합성 0(KillSwitch 초기 row 없을 때 "최초 토글 없음" + KIS 키 hasKey=false 시 "등록된 키 없음"). spec 본문: `_workspace/specs/pr-416-settings-kis-change-killswitch-ai-spec.md` (PR 번호 416 LOCK). CHANGELOG slot 4 anchor(architect + backend-core-phase1 + consumer-phase3 + frontend-phase2). | 운영·UX Fitness 갭 | W3.5 v3.6.5 patch (재발행) |
| 0075 | **T-NEWS 트랙 진입 spec/plan — Accepted (2026-05-15 사용자 결정 8 의제 채택)** — Flyway V32 disclosure + V33 news_article 멱등성 UQ + Kafka 2 토픽(disclosure.new.v1 + news.collected.v1 retention 7d) + Redis dedup/rate limit + REST 2 endpoint Swagger 4단 + STOMP 0 LOCK + Phase 분리 LOCK 3 PR 직렬 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. | 시장 데이터·외부 API | W3.5 v3.6.7 patch |
| 0076 | **KIS WS NXT/시간외 시세 sync 표준 정합 — Proposed (2026-05-15 architect 자율 분석 발의 · 사용자 결정 의제 7건 위임)** — 누적 hotfix #443·#444·#445 root cause 분석. KIS open-trading-api raw fetch 100% 인용 — H0STOUP0 시간외 단일가 16:00~18:00 raw 명시 + approval_key 24h cache reuse 표준(`auth_ws()` `reAuth_ws()`) + 단일 WS session 다중 tr_id 표준 패턴. 14 § LOCK — §1 컨텍스트(결함 매트릭스 + 누적 hotfix + 사용자 의제 발의) · §2 KIS 표준 패턴(tr_id 풀세트 9건 + 단일 session 다중 tr_id + magicJar 현행과 차이 매트릭스) · §3 OPSP8996 root cause 가설 매트릭스 6건(A-매 doConnect invalidate ★ 가장 가능성 / B-destroy ACK 부재 / C-heartbeat 10s 짧음 / D-15:30 KIS auto-disconnect / E-keychange race / F-multi-tr_id session 충돌) · §4 PINGPONG heartbeat 표준 정합 + watchdog timeout fine-tune · §5 시간외/NXT 활성 시간 매트릭스(H0STCNT0 09:00~15:30 + H0STOUP0 16:00~18:00 + H0NXCNT0 08:00~20:00) · §6 magicJar 현재 코드 구조 인용(PR #443·#444·#445 후) · §7 권유 fix 매트릭스 7 의제 A·B·C·D·E·F·G · §8 Rollback · §9 비범위 · §10 사용자 결정 의제 architect 권장 = A-1+B-2+C-1+D-1+E-1+F-1+G-1 · §11 사용자 채택 후 sub-agent 디스패치 매트릭스 Phase 1·2·3·4 · §12 KIS-first raw fetch 출처 매트릭스 6건 · §13 변경 이력 · §14 사용자 메모리 정합 7건. 코드 변경 0 LOCK (분석 사이클). spec 본문: `_workspace/specs/pr-447-kis-ws-nxt-standard-spec.md`. CHANGELOG slot single row anchor `pr-447-slot-architect`. 사용자 결정 위임 — 채택 후 별도 사이클 sub-agent 디스패치 (Phase 1·2 직렬 + Phase 3 후속 + Phase 4 별도). | 시장 데이터·외부 API | W3.5 분석 (v3.6.8 분석 · 채택 시 v3.6.9 patch 후보) |
| 0082 | **사용자 수동 주문 발행 트랙 (T-MAN) — Proposed (2026-05-18 architect 자율 결정 · 사용자 결함 진단 위임 "주문을 하는 화면이 없어 --;; 잔액조회도" · "확장형으로 별도 페이지 있어도될거같은데?" · "가상계좌 잔고 등 통합테스트 하려면 이게 있어야해")** — `POST /api/orders/manual` 신규 endpoint + `/orders/new` 페이지 신설 + `OrderPreviewModal` 이중 confirm + 60s cooldown + 3중 게이트(env MAGICJAR_MODE × env MAGICJAR_ALLOW_REAL × user_account.real_trading_consent_at) 동일 적용 + ADR 0068 `OrderRouterKafkaListener` 12 step 100% 재사용(자본 path 단일 LOCK) + ADR 0067 T-RISK Guard chain 우회(KillSwitch만 재검증 — 사용자=최고 관리자 비전 정합) + sentinel signalId=0L · strategyId=0L + Kafka payload `source="MANUAL"` 필드 add-only(도메인 변경 0). 6 의제 architect 결정 LOCK(A-1 Kafka 비동기·B-1 sentinel·C-1 T-RISK 우회·D-1 KIS 자체 검증 흡수·E-1 호가 표시 0·F-2 신규 ADR). UI 8 layer 안전망(종목 6자리·수량≥1·LIMIT 가격>0·3중 게이트 표시·KillSwitch LOCK·이중 confirm·60s cooldown·REAL 강조). KIS endpoint 신규 호출 0(ADR 0068 §14-5 cross-ref만 — `examples_llm/domestic_stock/order_cash/order_cash.py` 2026-05-18 WebFetch verbatim 시그니처 LOCK) + AI 호출 0(룰 5) + mock 합성 0(시장가 "체결 후 확정" 명시) + ADR 0037 Rate Limit 영향 0. 모듈 경계 정합(ADR 0004): api 신규 4 파일(controller+service+messaging+dto) + consumer 1 파일 수정 add-only(deserialize source 필드) + frontend 신규 3 + 수정 add-only 3 + Flyway V31 micro(FK 존재 시 옵션). dogfooding 케이스 9(수동 MOCK 주문 — UI → backend → Kafka → consumer → KIS → DB → STOMP + signal seed SQL 부담 0) + 케이스 10(3중 게이트 차단). T-O Phase 4 plan §4.2 케이스 6·7·8 강화 통합(signal seed SQL 부담 해소). T-ACCT(ADR 0081)와 합류 — DashboardPage 잔고 변화 evidence path. spec 본문: `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md`. backlog §65 신규. Phase 1(backend-core) + Phase 2(frontend) 병렬 dispatch 가능 + Phase 3(consumer) 직렬 후속. v3.6.15 patch cut 권장(`feedback_release_policy_patch_first` 정합 — T-ACCT v3.6.14와 묶음 cut 안 함, 영역 분리). | 운영·UX fitness · 자동매매 fitness | W3 보강 v3.6.15 patch (T-MAN 신규 트랙) |
| 0081 | **계좌 잔고·예수금 사용자 노출 endpoint + UI (T-ACCT 신규 트랙) — Proposed (2026-05-18 architect 자율 결정 · 사용자 결함 진단 위임 "지금 잔액조회 예수금 조회 이런게 아예 없는데?")** — KIS `inquire-balance` output2 11 필드(예수금·평가금·평가손익·자산증감) 사용자 노출 endpoint 3건(`GET /api/account/balance·positions·summary` Swagger 4단) + Dashboard UI 격상(`DashboardPlaceholder → DashboardPage` + `AccountSummaryCard` + `HoldingsTable` + TanStack Query 30s auto-refresh) + 신규 port `domain/account/port/KisBalanceQueryPort` + 신규 adapter `api/infrastructure/kis/ApiKisBalanceQueryAdapter`(api 직접 KIS REST 호출 · consumer 0 touch 관심사 분리) + Redis cache `account:balance:{userId}` TTL 30s + `KisCapitalSnapshotProviderAdapter` 실제 어댑터 교체(ADR 0067 §7-1 `@ConditionalOnMissingBean` cross-ref · POSITION_LIMIT 룰 활성화 · revert 시 default no-op 자동 fallback). 모듈 경계 정합(ADR 0004): domain Spring 의존 0 / api consumer 의존 0 / Flyway 변경 0(Redis cache only). Phase 분리 LOCK 3 PR(backend-core domain+api 단일 PR + backend-core consumer/risk + frontend Dashboard). KIS-first raw 4건 inline KDoc 의무(`inquire_balance.py` + `chk_inquire_balance.py` · output2 영문 key + 한글 의미 LOCK · TR_ID `TTTC8434R`/`VTTC8434R` 분기). KIS endpoint 호출 0 추가(ADR 0037 정합 — `inquire-balance` 1건 재사용 · Redis TTL 30s). AI 호출 0 LOCK(룰 5). mock 합성 0 LOCK(`feedback_no_mock_data`). spec 본문: `_workspace/specs/2026-05-18-account-balance-positions-spec.md`. backlog §64. fitness gap 정합(`feedback_v3_skepticism_no_regression` — 자동매매 필수 + v1/v2 잔재 아닌 신규). v3.6.14 patch cut 권장. Phase 1·2 backend 머지 후 Accepted 격상 예정. | 운영·UX fitness · 자동매매 fitness | W3 보강 v3.6.14 patch (T-ACCT 신규 트랙) |
| 0083 | **indicator-impl 라이브러리 모듈 신설 — IndicatorSource 모듈 경계 결함 fix (결함 3) — Proposed (2026-05-18 architect 자율 결정 · 사용자 옵션 B 명시)** — 사용자 dogfooding evidence(2026-05-18 KST 최고 관리자) "GET /api/indicators?code=066570&name=ema&interval=1d&params=period=20 → 400". root cause: api 모듈 `IndicatorEngineConfig`가 Spring autowire `List<IndicatorSource>` 수집하나 `NativeIndicatorSource`·`KisStrategyBuilderSource`(모두 `@Component`)가 consumer 단독 거주 + api는 consumer 의존 0 LOCK(ADR 0004 §의존 방향) → api ApplicationContext sources=emptyList() → 81지표 전부 400. 결정: `indicator-impl/` 라이브러리 모듈 신설(root 직속, `kotlin("jvm")+kotlin("plugin.spring")+spring-context`, Spring Boot main 0) + 패키지 루트 `kr.spdaewoon.magicjar.indicator.impl.*` + `api`·`consumer` 양쪽 `implementation(project(":indicator-impl"))` 의존 추가 + 15 파일 git mv(consumer→indicator-impl, ~3,433 line, 패키지 rename `consumer.application.indicator.source(.{native,kis})` → `indicator.impl(.{native,kis})`). 잔류 3건(consumer): `LiveCandleMerger`·`LiveCandleMergingSource`·consumer 측 `IndicatorEngineConfig`(자본 보호 hotfix 2026-05-15 Redis key `candle:today:{code}:1d` 정합 보존). 모듈 매트릭스 5→6 갱신(domain·pattern-engine·**indicator-impl**·api·batch·consumer · ADR 0004·0054·0083 정합). ADR 0054 pattern-engine 패턴 1:1 정합 재사용(운영 인지 비용 0). sub-agent dispatch: backend-core 단일 PR(Gradle 4 파일 + 15 파일 git mv + 패키지 rename). 검증 의무: `./gradlew check` GREEN + 부팅 로그 `IndicatorEngine registered sources: [native, kis-strategy-builder]` + dogfooding curl 200. **결함 3-B (api 측 LiveCandleMerger wiring 부재)** 별도 backlog §66 후속. v3.6.14 patch cut 합류 권장. spec 본문: `_workspace/specs/2026-05-18-indicator-impl-module-spec.md`. backlog §66. fitness gap 정합(`feedback_v3_skepticism_no_regression` — 자동매매 필수 81지표 사용자 노출 + W2 T-IND 트랙 결함 정정 · v1/v2 잔재 아닌 모듈 경계 결함). | 기술 스택 + 모듈 구조 | W3 보강 v3.6.14 patch (결함 3 fix) |
| 0077 | **OPSP8996 ERROR 재발 deep dive (v3.6.11 hotfix) — Proposed (2026-05-17 architect 자율 분석 발의 · 사용자 결정 의제 6건 위임)** — v3.6.10 cut(ADR 0076 Phase 1·2·3 머지 완료) 후에도 OPSP8996 ERROR 86건/1시간 재발. ADR 0076 6 가설(A·B·C·D·E·F)이 모두 "자격증명 유효 + KIS 응답 OPSP8996 도착" 가정에 한정된 점 발견 → 현 evidence "자격증명 미등록(MOCK type) + getApprovalKey 'ERROR_APPROVAL_KEY' 문자열 fallback + opsp8996RetryCount reset 0 누락 + 무한 retry" 시나리오 신규 root cause 발굴. 15 § LOCK — §1 컨텍스트(docker logs evidence 시계열 + ADR 0076 6 가설 미발동 매트릭스 + fitness gap) · §2 magicJar 현행 코드 path 추적(부팅 chain 5단 + KIS 응답 무한 loop + 결함 #1~#5 매트릭스) · §3 KIS open-trading-api 표준 패턴 raw 인용(auth_ws None 반환 + KISWebSocket max_retries=3 cap + OPSP8996 분기 0건) · §4 신규 root cause 가설 G·H·I·J 매트릭스(G-자격증명 미등록 무한 loop ★★★ root cause 확정 / H-MOCK type 시세 connect 정합 결함 ★★★ [feedback_kis_key_realonly_for_data] 정합 / I-retry counter reset 0 누락 + cap 0건 / J-WatchlistIngestionWorker 사전 검증 0건) · §5 사용자 메모리 정합 검증([feedback_kis_key_realonly_for_data] + [project_kis_key_management] + [feedback_verification_strict]) · §6 ADR 0076 미커버 영역 매트릭스 · §7 KIS 표준 패턴 보강 raw 인용 · §8 Rollback · §9 비범위 · §10 사용자 결정 의제 6건 architect 권장 = G-1(fail-fast 예외 throw + connect 차단) + H-1(시세 WS REAL 고정) + I-1(counter reset + cap 3 dispose) + J-1(onStartup 사전 검증) + K-1(UI banner backlog) + L-1(v3.6.11 단일 PR 묶음) · §11 사용자 채택 후 sub-agent 디스패치 매트릭스(Phase 1 market-data 단일 PR · KisWebSocketBridge.kt + WatchlistIngestionWorker.kt + unit test 4건) · §12 권장 사이클 흐름(v3.6.11 hotfix → v3.6.12+ backlog 분리) · §13 KIS-first raw fetch 출처 매트릭스 · §14 변경 이력 · §15 사용자 메모리 정합 9건. ADR 0076 보강(흡수 아님 — neutral 신규 시나리오 추가). 코드 변경 0 LOCK (분석 사이클). spec 본문 없음(hotfix [feedback_spec_plan_scope] 정합). 사용자 결정 위임 — 채택 후 market-data 단일 디스패치(v3.6.11 단일 PR ~150 LOC). | 시장 데이터·외부 API | v3.6.11 hotfix 분석 (채택 시 v3.6.11 patch 후보) |

### 후속 예정 (Proposed — `_workspace/arch/adr/README.md` §12 정합)

| 후보 번호 | 제목 (예정) | 진입 트랙 |
|---|---|---|
| 0058 | 57 캔들 패턴 표준 (본문 ADR로 격상 검토 — T-PAT 머지 정합) | T-PAT |
| ~~0059~~ | ~~Screener 표준~~ → **ADR 0071 흡수 완료** (2026-05-14) | T-SC (closed) |
| 0060 | Performance 표준 | T-PERF |
| 0061 | **Backtest 결과 영속 — Accepted (2026-05-12)** — 3 테이블(backtest_run·backtest_trade·backtest_position) + 5 endpoint + BacktestPersister·BacktestQueryService. ADR 0056 §2-6 supersede. | T-BT R2 (backlog §37 DONE) |
| 0062 | **5m·15m·30m·90m·1h 합산 가공 — Accepted (2026-05-11)** — backlog §31 트랙 (§10 본 표 인용) | sub-agent 디스패치 매트릭스 진행 (ADR 0062 §7-1·§9) |
| 0063 | **NXT/통합거래 매트릭스 cover — Accepted (2026-05-11)** — backlog §32 트랙 (§10 본 표 인용) | sub-agent 디스패치 매트릭스 진행 (ADR 0063 §결정 후 트리거) |
| 0064 | **Redis boundary fanout + STOMP 실시간 합산 갱신 — Proposed (2026-05-12)** — backlog §34 트랙 (§10 본 표 인용) | 사용자 결정 대기 → 채택 후 market-data + frontend 디스패치 |
| 0066 | **T-SIG 실시간 시그널 엔진 — Accepted (2026-05-12)** — backlog §40 DONE. W3 두 번째 트랙. 사용자 채택 A-1·B-1·C-1·D-1·E-1·F-1. strategy-engine(Phase 1) + backend-core(Phase 2) + frontend(Phase 3) 3 PR 직렬 디스패치 완료 | v3.4.0 마감 |
| 0067 | **T-RISK 리스크 관리 — Accepted (2026-05-12)** — backlog §41 DONE. W3 세 번째 트랙. 사용자 채택 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1. strategy-engine(Phase 1) + backend-core(Phase 2) + frontend(Phase 3) 3 PR 직렬 디스패치 완료. PR #351·#353·#354·#355·#356·#357 머지 | v3.5.0 마감 |
| 0068 | **T-O 주문 실행 — Proposed (2026-05-12)** — backlog §43 트랙 (§10 본 표 인용). W3 네 번째 트랙. 결정 의제 8건 사용자 검토 위임. architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1. KIS-first raw 인용 4건(TTTC0012U·TTTC0011U·TTTC0013U·H0STCNI0). 실계좌 3중 게이트. 채택 시 strategy-engine(Phase 1 consumer+domain) + backend-core(Phase 2 api+Flyway V20~V23) + frontend(Phase 3 /orders + SettingsModal 동의 토글) 직렬 디스패치 | 사용자 결정 대기 |
| 0070 | **T-RECON + T-NOTIFY 묶음 spec/plan — Accepted (2026-05-13)** — backlog §46~§51 트랙 (§10 본 표 인용). W3 마무리 prereq. architect 자율 결정 (사용자 위임). ADR 0041·0044 baseline reuse + 정밀 명세 §1~§15. Phase 분리 LOCK 3 PR 직렬(strategy-engine → backend-core RECON → backend-core NOTIFY). KIS-first raw 4건. Flyway V25·V26·V27. Kafka 신규 2 + listen 7. REST 3 endpoint. frontend 0 LOCK. spec: `_workspace/specs/pr-392-recon-notify-spec.md` | v3.6.2 patch 진행 중 |
| 0071 | **T-SC Screener spec/plan — Accepted (2026-05-14)** — backlog §52~§58 예정 트랙 (§10 본 표 인용). W3 다섯 번째 트랙. architect 자율 결정 (사용자 위임 동일). 8 의제 LOCK A-1~H-1. Phase 분리 LOCK 3 PR 직렬(strategy-engine Phase 1 → backend-core Phase 2 → frontend Phase 3). universe=영속 reuse + candle=영속 reuse → KIS endpoint 호출 0 LOCK + AI 호출 0 grep + mock 합성 0. Flyway V28(screening_run + screening_match, env toggle). Kafka 신규 0. STOMP 신규 0. REST 3 endpoint Swagger 4단. 사용자 비전 정합 = Strategy Builder "전략으로 종목 찾기" 흐름 합류 + Watchlist 일괄 등록. spec: `_workspace/specs/pr-393-t-sc-spec.md` | v3.6.3 patch 진행 중 |
| 0072 | **T-J + T-PERF 묶음 spec/plan — Accepted (2026-05-14)** — backlog §59~§67 예정 트랙 (§10 본 표 인용). W3 마지막 사이클. architect 자율 결정 (사용자 위임 동일). 8 의제 LOCK A-1~H-1. Phase 분리 LOCK 3 PR 직렬(backend-core T-J Phase 1 → backend-core T-PERF Phase 2 → frontend Phase 3). 모든 데이터 영속 reuse(5 baseline) → KIS endpoint 호출 0 LOCK + AI 호출 0 grep 6 디렉토리 + mock 합성 0. Flyway V29(trading_journal + journal_note 멱등성 UQ) + V30(performance_summary + pnl_daily). Kafka 신규 publish 0 + listen 5. STOMP 0. REST 5 endpoint Swagger 4단 한국어. 5 지표 fixed(총 P&L · 일별 P&L · MDD · 승률 · 평균 손익비). batch DailyPerformanceJob 매일 00:30 KST. 사용자 비전 정합 = ★★★ 운영 회고 의무 + W4 AI 합류 prereq. spec: `_workspace/specs/pr-402-t-j-perf-spec.md` | v3.6.4 patch 진행 중 |
| 0073 | **T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 — Superseded by ADR 0075 (2026-05-15)** — backlog §52 등재 트랙. W3.5 prereq. analysis only — 사용자 결정 8 의제 채택 후 ADR 0075 정밀 spec으로 위임. | v3.6.5 analysis 완료 · 0075로 위임 |
| 0075 | **T-NEWS 트랙 진입 spec/plan — Accepted (2026-05-15)** — backlog §52 흡수 + §53 신규 Phase 4 RSS 잔류 트랙 (§10 본 표 인용). W3.5 트랙 (v3.6.7 patch). architect 자율 결정 (사용자 자율 위임 "T-NEWS 트랙 진입 진행해" + 8 의제 architect 권장 안 채택). ADR 0073 supersede. 14 § LOCK — 사용자 결정 8 의제 채택(1=A 옵션·2-1 1분·3-1 Phase 2 .env·4-2 Slack 알림만·5-1 T-AI 흡수·6-1 sidebar·7-1 2 토픽·8 v3.6.7 patch). Phase 분리 LOCK 3 PR 직렬(market-data Phase 1 DART 1분 강화 + Flyway V32 disclosure + Kafka disclosure.new.v1 → backend-core Phase 2 Naver Open API + Flyway V33 news_article + Kafka news.collected.v1 + .env NAVER_OPEN_API_* + Watchlist 우선 cron → frontend Phase 3 NewsPanel + DisclosurePanel + sidebar mount + 60s polling). Flyway V32 disclosure + V33 news_article (멱등성 UQ ux_disclosure_rcept_no + ux_news_article_source_stock_url_hash IF NOT EXISTS). Kafka 신규 2 토픽(disclosure.new.v1 + news.collected.v1 retention 7d). Redis dedup + rate limit (DART 60/분 + Naver 17/분 ZSET 슬라이딩). REST 2 endpoint Swagger 4단(GET /api/symbols/{code}/news + /disclosures 페이징 + 기간 필터 + onlyCorrection). STOMP 0 LOCK(polling 60s 채택). DART list.json + Naver search/news.json raw fetch 인용(헌법 §3 5단계 절차). KIS endpoint 호출 0 LOCK(시세 단일 KIS 정합) + AI 호출 0 grep 검증 6 디렉토리 + mock 합성 0("데이터 없음" 한국어 + Naver 키 미등록 안내). ~25 산출 파일 매트릭스. 검증 시나리오 3건. Phase 4 RSS 한경·구글 backlog §53 신규 잔류. AI 감성 분석 T-AI 흡수 backlog §62. spec: `_workspace/specs/pr-434-t-news-spec.md` (PR 번호 434 가정). CHANGELOG slot 4 anchor(architect + market-data Phase 1 + backend-core Phase 2 + frontend Phase 3) | v3.6.7 patch 진입 (사용자 메모리 release_policy_patch_first 정합 — v3.7.0=W4 LOCK) |
| 0076 | **KIS WS NXT/시간외 시세 sync 표준 정합 — Proposed (2026-05-15)** — 누적 hotfix #443·#444·#445 root cause 분석. KIS open-trading-api raw fetch 100% 인용 (H0STOUP0 시간외 단일가 16:00~18:00 raw 명시 + approval_key `auth_ws()` 24h cache reuse 표준 + 단일 WS session 다중 tr_id 표준 패턴). 14 § LOCK. 의제 7건 architect 권장 = A-1(approval_key 24h cache reuse — PR #444 invalidate revert) + B-2(H0STOUP0 16:00~18:00 dynamic subscribe) + C-1(호가는 1차 H0STASP0만 유지) + D-1(heartbeat 30s + 정규장 종료 후 60s) + E-1(destroy ACK 2s 대기) + F-1(SubscriptionResponse OPSP8996 self-heal + 1회 retry) + G-1(시간외 룰엔진 매칭 동등 + 주문 시간 RiskGuard 게이트). 채택 후 Phase 분리 sub-agent 디스패치 매트릭스 §11. 코드 변경 0 LOCK (분석 사이클). spec 본문: `_workspace/specs/pr-447-kis-ws-nxt-standard-spec.md`. CHANGELOG slot single row anchor `pr-447-slot-architect`. 사용자 결정 위임 — architect 권장안 채택 시 별도 사이클 sub-agent 디스패치 (Phase 1·2 market-data 직렬 + Phase 3 H0STOUP0 후속 + Phase 4 strategy-engine 별도) | v3.6.8 분석 (채택 시 v3.6.9 patch 후보) |
| 0077 | **OPSP8996 ERROR 재발 deep dive (v3.6.11 hotfix) — Proposed (2026-05-17)** — v3.6.10 cut 후 86건/1시간 ERROR 재발. ADR 0076 6 가설 미발동 시나리오 — 자격증명 미등록(MOCK type) + getApprovalKey 'ERROR_APPROVAL_KEY' 문자열 fallback + opsp8996RetryCount reset 0 누락 + 무한 retry 신규 root cause. 신규 가설 G·H·I·J 발굴. 결함 #1 silent fallback / #2 counter reset 누락 / #3 connect 사전 검증 0 / #4 MOCK type 시세 connect ([feedback_kis_key_realonly_for_data] 정합 결함) / #5 sendSink invalid msg drop. KIS raw 인용 — auth_ws None 반환 + KISWebSocket max_retries=3 cap. 의제 6건 architect 권장 = G-1(fail-fast + connect 차단) + H-1(시세 REAL 고정) + I-1(counter reset + cap 3 dispose) + J-1(onStartup 사전 검증) + K-1(UI banner backlog) + L-1(v3.6.11 단일 PR). 코드 변경 0 LOCK. 사용자 결정 위임 — 채택 시 market-data 단일 디스패치(v3.6.11 단일 PR ~150 LOC). | v3.6.11 hotfix 분석 (채택 시 v3.6.11 patch 후보) |
| 0081 | **계좌 잔고·예수금 사용자 노출 endpoint + UI (T-ACCT 신규 트랙) — Proposed (2026-05-18)** — 사용자 결함 진단 "지금 잔액조회 예수금 조회 이런게 아예 없는데?" 정합. 3 endpoint(`GET /api/account/balance·positions·summary`) + Dashboard UI(`DashboardPlaceholder → DashboardPage` 격상 + `AccountSummaryCard` + `HoldingsTable`) + 신규 port `KisBalanceQueryPort` + 신규 adapter `ApiKisBalanceQueryAdapter`(api 직접 KIS 호출 · consumer 0 touch) + Redis cache `account:balance:{userId}` TTL 30s + CapitalSnapshotProvider 실제 어댑터 교체(ADR 0067 §7-1 cross-ref). KIS `inquire-balance` output2 11 필드 LOCK + raw URL 4건 inline KDoc 의무. Phase 1·2 backend 단일 PR(domain+api) + Phase 3 consumer/risk + Phase 4 frontend. v3.6.14 patch cut. spec: `_workspace/specs/2026-05-18-account-balance-positions-spec.md`. backlog §64. | v3.6.14 patch (T-ACCT 신규 트랙 — W3 보강 fitness gap) |
| 0083 | **indicator-impl 라이브러리 모듈 신설 — IndicatorSource 모듈 경계 결함 fix (결함 3) — Proposed (2026-05-18)** — 사용자 dogfooding evidence "GET /api/indicators?code=066570&name=ema → 400". 사용자 옵션 B 명시. `indicator-impl/` 신설(root 직속 라이브러리 jar) + api·consumer 양쪽 의존 추가 + 15 파일 git mv(consumer→indicator-impl, ~3,433 line, 패키지 rename) + 잔류 3건(consumer: LiveCandleMerger·LiveCandleMergingSource·IndicatorEngineConfig — 자본 보호 hotfix 2026-05-15 정합 보존). 모듈 매트릭스 5→6 갱신. ADR 0054 pattern-engine 1:1 정합. backend-core 단일 PR 권장. 결함 3-B(api 측 LiveCandleMerger wiring) 별도 backlog. v3.6.14 patch cut 합류. spec: `_workspace/specs/2026-05-18-indicator-impl-module-spec.md`. backlog §66. | v3.6.14 patch (결함 3 fix — W3 보강) |

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
| 2026-05-18 (ADR 0082) | **ADR 0082 T-MAN 신규 트랙 spec/plan 신규 발행 (Proposed · spec 사이클 · v3.6.15 patch prep)** — §10 인덱스 0082 row 추가 (T-ACCT 0081 row 직전) + §10 헤더 카운트 갱신(`0064·0076·0077·0078·0081·0082 Proposed`). 사용자 결함 진단 (최고 관리자 2026-05-18 KST) "주문을 하는 화면이 없어 --;; 잔액조회도" · "C가 권위적이긴 하지만 실제로 자동매매 하는거 결국엔 호가, 가격, 수량, 포지션을 넘겨서 주문을 성사 할테니 확장형으로 별도 페이지 있어도될거같은데?" · "하려는 이유는 가상계좌 잔고 등 통합테스트 하려면 이게 있어야해" 정합. orchestrator + architect 진단 매트릭스: (A) `OrderRouterKafkaListener` 자동 path 12 step LOCK 존재(`signal.generated.v1` → `order.intent.v1`) + 수동 entry point 0건 (B) `KisOrderHttpAdapter` REAL/MOCK 분기 + TR_ID 4건 + raw 인용 baseline 재사용 가능 (C) `OrderIntent`·`OrderRequest` 도메인 `signalId: Long`·`strategyId: Long` NOT NULL — 수동 주문 sentinel 의제 (D) `OrdersPage` read-only + Cancel만 — [신규 주문] button 0건 (E) `OrderQueryController`·`OrderCancelController`·`InstrumentSearchInput`·`SettingsModal` 모두 재사용 가능. 신규 결정: spec `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md` + ADR 0082 Proposed. 6 의제 architect 결정 LOCK — A-1(Kafka 비동기 path 자동과 동일 · 자본 path 단일 LOCK · OrderRouter 12 step 검증 흐름 100% 재사용 · 3중 게이트·KillSwitch·멱등성·KIS REST submit·체결 추적·STOMP·audit 모두 자동 흡수) + B-1(sentinel signalId=0L · strategyId=0L · Kafka payload `source` 필드 add-only default "SIGNAL" + idempotencyKey="manual:${userId}:${epochSec}" · 도메인 변경 0 · 자동 path 영향 0 · audit_event.payloadJson.source="MANUAL" 마커가 추적 책임 흡수) + C-1(T-RISK Guard chain 우회 · KillSwitch만 step 4 재검증 · 사용자=최고 관리자 비전 정합 · UX 직관 · 3중 게이트+이중 confirm+60s cooldown+KillSwitch 4 layer 안전망 보상) + D-1(position 검증 1차 MVP KIS 자체 흡수 · KIS 단일 출처 정합 · T-ACCT Phase 4 머지 후 frontend 사전 검증 backlog §66 신규 후속) + E-1(호가·현재가 표시 1차 MVP 0 · WS slot 영향 회피 · ADR 0048 41 slot 한도 보존 · KIS `inquire-price` REST 1회 호출 backlog §66 후속) + F-2(신규 ADR 0082 — T-MAN 독립 + ADR 0068 cross-ref · ADR 0068 본 트랙 마감 evidence 훼손 회피 · `feedback_spec_plan_scope` 정합). 명세 매트릭스 LOCK — backend 신규 4 파일(api/controller/order/`ManualOrderController` + api/service/order/`ManualOrderService` + api/messaging/order/`ManualOrderIntentKafkaProducer` + api/controller/order/dto/`ManualOrderRequest·Response`) + Swagger 4단 어노테이션 + Bean Validation(`@Min(1)`·`@Pattern("\\d{6}")`·`@NotNull`) + Flyway V31 micro(FK 존재 시 옵션 `DROP CONSTRAINT IF EXISTS fk_order_request_signal · fk_order_request_strategy` — backend-core가 V20·V21·V22·V23 본문 확인 후 결정) + consumer 1 파일 add-only(`OrderRouterKafkaListener.deserialize` source 필드 default "SIGNAL" · 12 step 본체 0 touch) + frontend 신규 3 파일(`pages/orders/NewOrderPage.tsx` + `components/order/OrderPreviewModal.tsx` + `api/hooks/useManualOrderMutation.ts`) + 수정 add-only 3(`OrdersPage.tsx` [신규 주문] 버튼 + `api/order.ts` `manualIssue()` + DTO + routes `/orders/new` 1줄). UI 8 layer 안전망 LOCK — (1) 종목코드 6자리 input 검증 (2) 수량 ≥ 1 submit disable (3) LIMIT 시 가격 > 0 (4) 3중 게이트 상태 표시(mode+ALLOW_REAL+consent badge) — REAL 미허용 시 SettingsModal 안내 (5) KillSwitch 상태 LOCK 표시 — ON 시 발행 disable (6) 미리보기 → OrderPreviewModal 이중 confirm — checkbox 미체크 시 [발행] disable (7) 60초 cooldown(동일 사용자+동일 종목) — UI 카운터 + LocalStorage 저장 + 발행 차단 (8) REAL 모드 시 강조 — "이 주문은 실 자본 변동을 발생시킵니다" 붉은색 확대. KIS-first raw fetch(헌법 §3 LOCK) — 본 ADR은 KIS endpoint 신규 호출 0건 + raw 인용 신규 0 + ADR 0068 §14-5 cross-ref만(TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U · `examples_llm/domestic_stock/order_cash/order_cash.py` raw URL + 2026-05-18 WebFetch verbatim 시그니처 LOCK — `def order_cash(env_dv, ord_dv, cano, acnt_prdt_cd, pdno, ord_dvsn, ord_qty, ord_unpr, excg_id_dvsn_cd, sll_type="", cndt_pric="")` + body schema CANO·ACNT_PRDT_CD·PDNO·ORD_DVSN·ORD_QTY·ORD_UNPR·EXCG_ID_DVSN_CD·SLL_TYPE·CNDT_PRIC + response output KRX_FWDG_ORD_ORGNO·ODNO·ORD_TMD). 자본 path 단일 LOCK(`OrderRouterKafkaListener` 12 step 권위 100% reuse · 자동·수동 분기 0) + AI 호출 0 LOCK(룰 5 · LLM provider import 0 · grep 6 디렉토리 의무) + mock 합성 0 LOCK(`feedback_no_mock_data` · 시장가 "체결 후 확정" 명시 · `inquire-price` REST 1회 호출 비범위) + KIS endpoint 신규 호출 0(ADR 0037 Rate Limit 영향 0). 모듈 경계 정합(ADR 0004): domain 본문 변경 0 · api 신규 4 파일 add-only · consumer 1 파일 수정 add-only(source 필드만) · frontend 신규 3 + 수정 add-only 3 · Flyway V31 micro 옵션. dogfooding 케이스 9(수동 MOCK 주문 발행 — UI `/orders/new` → 종목 검색 → BUY MARKET 수량 1 → 미리보기 → 발행 → /orders redirect → STOMP REQUESTED·ACK 토스트 → H0STCNI9 모의 체결 → FILLED → T-ACCT DashboardPage 잔고 변화 evidence · signal seed SQL 부담 0) + 케이스 10(3중 게이트 차단 검증 — env REAL + ALLOW_REAL=false → UI 발행 disabled + backend precheck 403 REAL_GATE_DENIED + 자본 변동 0). T-O Phase 4 plan §4.2 케이스 6·7·8 강화 통합(signal seed SQL 부담 해소 — 케이스 6: signal seed SQL → UI 수동 발행 / 케이스 7: source="MANUAL" badge 노출 / 케이스 8: 동일 path). T-ACCT(ADR 0081)와 합류 — 케이스 9 잔고 변화 evidence path = T-ACCT DashboardPage `AccountSummaryCard` 30s auto-refresh. sub-agent dispatch plan: Phase 1(backend-core api/controller·service·messaging·dto 단일 PR ~250 LOC) + Phase 2(frontend pages·components·api·hooks·routes 단일 PR ~350 LOC) **병렬 가능**(영역 분리·DTO 명세 spec §3 LOCK·add-only·modify-shared risk 0) + Phase 3(backend-core consumer/order source 필드 add-only ~30 LOC) 직렬 후속. CHANGELOG slot 3 anchor 사전 발행(`anchor-spec-t-man-manual-order-issuance` + `pr-N-slot-backend-core` + `pr-N-slot-frontend` + `pr-N-slot-end`). release: v3.6.15 patch cut 권장(`feedback_release_policy_patch_first` 정합 — v3.7.0=W4 LOCK · T-ACCT v3.6.14와 묶음 cut 안 함 영역 분리 · `feedback_dev_priority_qa_batch` 정합). ADR 0068 본문 변경 0(자본 path 100% 재사용 cross-ref만) + ADR 0067 본문 변경 0(T-RISK Guard chain 우회 cross-ref만) + ADR 0024 본문 변경 0(KillSwitch 재검증 reuse). ADR README §9 메타·운영·하네스 10→11건 + §10 인덱스 0082 row + §11 후속 ADR 번호 0083으로 시프트 + §13 후속 예정 0082 row + §14 변경 이력 row. backlog §65 신규(T-MAN 트랙). 사용자 자율 위임 정합(옵션 A "확장형 별도 페이지" 명시 + sub-agent dispatch plan 위임). 사용자 메모리 정합 12건(`project_system_vision` 룰 본체 + 사용자=최고 관리자 + 룰 path 0 touch + `feedback_kis_first_policy` raw 인용 cross-ref + `feedback_kis_key_realonly_for_data` 주문 액션 3중 게이트 동일 + `feedback_v3_skepticism_no_regression` fitness gap 검증 §1-4 의무 수행 + `feedback_no_mock_data` 시장가 "체결 후 확정" + `feedback_release_policy_patch_first` v3.6.15 patch + `feedback_spec_plan_scope` 신규 트랙 spec/plan + ADR 의무 정합 + `feedback_orchestrator_active_parallel` Phase 1·2 병렬 권장 + `feedback_swagger_pattern` 4단 어노테이션 + 한국어 + `feedback_verification_strict` evidence 매트릭스 명시 + `feedback_3tier_hierarchy` architect 단독 발행 + orchestrator dispatch + `feedback_dev_priority_qa_batch` T-ACCT와 묶음 cut 안 함 영역 분리). 코드 변경 0 LOCK (spec 사이클). spec 본문: `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md`. (architect, opus 4.7 1M) |
| 2026-05-18 (ADR 0081) | **ADR 0081 T-ACCT 신규 트랙 spec/plan 신규 발행 (Proposed · 분석+spec 사이클 · v3.6.14 patch prep)** — §10 인덱스 0081 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074·0075·0079·0080 Accepted + 0064·0076·0077·0078·0081 Proposed + 0073 Superseded by 0075 + 0035 Superseded by 0080`) + §10 후속 예정 0081 row 추가. 사용자 결함 진단 (최고 관리자 2026-05-18 14:50 KST) "지금 잔액조회 예수금 조회 이런게 아예 없는데?" 정합. orchestrator + architect 진단 매트릭스: (A) ADR 0042 Bootstrap용 `KisBalanceAdapter` 존재하나 사용자 노출 endpoint 0건 + output1(보유 종목)만 파싱 + output2(예수금·평가금·평가손익) 완전 누락 (B) `KisReconcileBalanceAdapter`도 T-RECON 내부 cron 용도 + holdings only (C) `api/controller/PositionController`는 DB position snapshot 조회만(실시간 KIS REST 0건) (D) frontend Dashboard·AppHeader 잔고 UI 0건(`DashboardPlaceholder.tsx` placeholder만) (E) `CapitalSnapshotProviderAdapter` Phase 2 미합류 → `getCapital(userId)→0` → ADR 0067 §5-5 POSITION_LIMIT 룰 무력. 신규 결정: spec `_workspace/specs/2026-05-18-account-balance-positions-spec.md` + ADR 0081 Proposed. 3 endpoint LOCK + 신규 port `KisBalanceQueryPort` + 신규 adapter `ApiKisBalanceQueryAdapter`(api 직접 KIS 호출 · consumer 0 touch · 관심사 분리) + DTO 3건 명세 LOCK + frontend Dashboard 격상 + `KisCapitalSnapshotProviderAdapter` 실제 어댑터 교체(`@ConditionalOnMissingBean` 정합). KIS-first raw fetch 의무(헌법 §3 LOCK): `inquire_balance.py` + `chk_inquire_balance.py` raw URL inline KDoc 4건 의무 + output2 11 필드(`dnca_tot_amt`·`nxdy_excc_amt`·`prvs_rcdl_excc_amt`·`cma_evlu_amt`·`tot_evlu_amt`·`nass_amt`·`pchs_amt_smtl_amt`·`evlu_amt_smtl_amt`·`evlu_pfls_smtl_amt`·`asst_icdc_amt`·`asst_icdc_erng_rt`) 영문 key + 한글 의미 매트릭스 LOCK + TR_ID `TTTC8434R`(실전) / `VTTC8434R`(모의) 분기. 모듈 경계 정합(ADR 0004): domain Spring 의존 0 / api consumer 의존 0 / consumer api 의존 0 / Flyway 변경 0(영속 미합류 — Redis cache only). sub-agent dispatch plan: Phase 1·2 단일 PR(backend-core domain+api 4 파일 신규 add-only) + Phase 3 backend-core(consumer/risk CapitalSnapshotProvider 교체) + Phase 4 frontend(api client + Dashboard 격상 + 5 컴포넌트 신규) + Phase 5 옵션(AppHeader 배지 + PositionsPage BalanceCard). mock 합성 0 LOCK(`feedback_no_mock_data`) + AI 호출 0 LOCK(룰 5 · LLM provider import 0) + KIS endpoint 호출 0 추가(ADR 0037 정합 — Redis TTL 30s + `inquire-balance` 1건 재사용). release: v3.6.14 patch cut 권장(`feedback_release_policy_patch_first` 정합 — T-ACCT는 W3 보강 트랙). ADR 0042 본문 변경 0(관심사 분리 · consumer KisBalanceAdapter 0 touch) + ADR 0070 본문 변경 0(T-RECON adapter 0 touch) + ADR 0067 §7-1 cross-ref 1줄 add-only(default no-op `@ConditionalOnMissingBean` supersede 명시). ADR README §10 인덱스 0081 row + §10-2 운영·UX fitness 갭 row + §13 후속 예정 0081 row + §14 변경 이력 row. backlog §64 신규(T-ACCT 트랙). 사용자 자율 위임 정합("신규 트랙으로 분리 — architect spec/plan 발행" 명시). 사용자 메모리 정합 9건(`feedback_v3_skepticism_no_regression` fitness gap 검증 + `feedback_kis_key_realonly_for_data` 정보 수집 실전 키 + `feedback_kis_first_policy` KIS-only + `feedback_no_mock_data` 합성 0 + `feedback_release_policy_patch_first` v3.6.14 patch + `feedback_orchestrator_active_parallel` Phase 병렬화 + `project_system_vision` 사용자 최고 관리자 실시간 잔고 확인 + `feedback_spec_plan_scope` 신규 트랙 spec/plan 의무 + `feedback_3tier_hierarchy` architect spec/plan + sub-agent dispatch). 코드 변경 0 LOCK (spec 사이클). spec 본문: `_workspace/specs/2026-05-18-account-balance-positions-spec.md`. (architect, opus 4.7 1M) |
| 2026-05-17 (ADR 0077) | **ADR 0077 OPSP8996 ERROR 재발 deep dive 신규 발행 (Proposed · 분석 사이클 · v3.6.11 hotfix)** — §10 인덱스 0077 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074·0075·0079 Accepted + 0064·0076·0077·0078 Proposed + 0073 Superseded by 0075`) + §10 후속 예정 0077 row 추가. v3.6.10 cut(ADR 0076 Phase 1·2·3 머지 완료) 직후 docker logs evidence — OPSP8996 ERROR 86건/1시간 재발 + attempt=69~85 1초 내 17건 연속 + `KIS WS approval_key 발급 불가 — type=MOCK 자격증명 미등록 (SetupWizard에서 등록 후 재부팅 필요): userId=thde`. ADR 0076 6 가설(A·B·C·D·E·F)이 모두 "자격증명 유효 + KIS 응답 OPSP8996 도착" 가정에 한정된 점 발견 → 신규 시나리오 "자격증명 미등록 + getApprovalKey 'ERROR_APPROVAL_KEY' 문자열 fallback + opsp8996RetryCount reset 0 누락 + 무한 retry" root cause 발굴. KIS open-trading-api raw fetch 100% 인용 — `auth_ws()` 실패 시 print + None 반환(예외 raise 0건) + `KISWebSocket(max_retries: int = 3)` cap 명시 + OPSP8996 분기 0건(일반 Exception 처리) + `examples_user` 디렉토리 리스트. 15 § LOCK — §1 컨텍스트(docker logs evidence 시계열 + ADR 0076 6 가설 미발동 매트릭스 + fitness gap 4 질문) · §2 magicJar 현행 코드 path 추적(부팅 chain WatchlistIngestionWorker.onStartup → kisWsPort.connect → doConnect → getApprovalKey IllegalStateException → "ERROR_APPROVAL_KEY" 문자열 fallback → KIS subscribe 32건 송신 → KIS 응답 OPSP8996 다수 → handleOpsp8996 attempt 무한 누적 + 결함 #1~#5 매트릭스) · §3 KIS open-trading-api 표준 패턴 raw 인용(auth_ws None 반환 + KISWebSocket max_retries=3 cap + OPSP8996 분기 0건 + magicJar 현행 vs KIS 표준 차이 매트릭스 5건) · §4 신규 root cause 가설 G·H·I·J 매트릭스(G-자격증명 미등록 무한 loop ★★★ root cause 확정 + evidence 직접 증거 / H-MOCK type 시세 connect 정합 결함 ★★★ [feedback_kis_key_realonly_for_data] 정합 + KIS examples REAL 한정 / I-retry counter reset 0 누락 + cap 0건 F-1 design intent 위배 / J-WatchlistIngestionWorker 사전 검증 0건 guard 패턴 권장) + 가설 D 재검증 · §5 사용자 메모리 정합 검증([feedback_kis_key_realonly_for_data] 정합 결함 ★ + [project_kis_key_management] SettingsModal 정합 + [feedback_verification_strict] docker logs 검증 의무) · §6 ADR 0076 미커버 영역 매트릭스(자격증명 미등록 + MOCK type 시세 + retry counter logic 결함 3건) · §7 KIS 표준 패턴 보강 raw 인용 · §8 Rollback 시나리오 4건 · §9 비범위(가설 D + UI 알림 + watchdog cap + PINGPONG 실측 + multi-tenant credential) · §10 사용자 결정 의제 6건 architect 권장 = G-1(fail-fast 예외 throw + connect 차단 — KIS 표준 None 반환 정합) + H-1(시세 WS REAL 고정 mode 무관 — [feedback_kis_key_realonly_for_data] 정합) + I-1(counter reset + cap 3 dispose — KIS 표준 max_retries=3 정합 + F-1 design intent 일치) + J-1(onStartup 사전 검증 + log.warn 1회) + K-1(UI banner backlog 분리) + L-1(v3.6.11 단일 PR 묶음 — hotfix 단일 영역 ~150 LOC + [feedback_spec_plan_scope] 정합) · §11 사용자 채택 후 sub-agent 디스패치 매트릭스(Phase 1 market-data 단일 PR · KisWebSocketBridge.kt + WatchlistIngestionWorker.kt + unit test 4건 자격증명 0건 시 connect skip + MOCK 미등록 시 REAL 사용 + OPSP8996 cap 3 dispose + retry counter reset) · §12 권장 사이클 흐름(v3.6.11 hotfix → v3.6.12+ K-1 UI banner + J-3 KisCredentialValidator 추출 + I-3 OPSP8996 분기 자체 제거 검토 backlog 분리) · §13 KIS-first raw fetch 출처 매트릭스 3건 신규 + ADR 0076 baseline reuse · §14 변경 이력 · §15 사용자 메모리 정합 9건(feedback_kis_first_policy + feedback_kis_key_realonly_for_data + project_kis_key_management + feedback_verification_strict + feedback_v3_skepticism_no_regression + project_system_vision + feedback_spec_plan_scope + feedback_no_mock_data + feedback_3tier_hierarchy). ADR 0076 보강 — 흡수 아님 (neutral 신규 시나리오 추가). 코드 변경 0 LOCK (분석 사이클). spec 본문 없음(hotfix [feedback_spec_plan_scope] 정합). ADR README §5 시장 데이터 카테고리 15건 갱신 (0077 Proposed 추가) + §12 reserved 슬롯 0077 발동 완료 표기(`_Closed_`) + §13 후속 예정 0077 row 추가 + §14 변경 이력 row. 사용자 결정 위임 — 채택 시 market-data 단일 디스패치(v3.6.11 단일 PR ~150 LOC + smoke test docker logs 10분 ERROR 0건 검증). (architect, opus 4.7 1M) |
|---|---|
| 2026-05-15 (ADR 0076) | **ADR 0076 KIS WS NXT/시간외 시세 sync 표준 정합 spec/plan 신규 발행 (Proposed · 분석 사이클 · v3.6.8)** — §10 인덱스 0076 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074·0075 Accepted + 0064·0076 Proposed + 0073 Superseded by 0075`) + §10 후속 예정 0076 row 추가. 누적 hotfix #443·#444·#445 root cause 분석. KIS open-trading-api raw fetch 100% 인용 — H0STOUP0 시간외 단일가 16:00~18:00 raw 명시(`overtime_ccnl_krx.py` docstring) + approval_key `auth_ws()` 24h cache reuse 표준 + 단일 WS session 다중 tr_id 표준 패턴(`kws.subscribe()` 반복 호출, 6 raw URL 인용). 14 § LOCK — §1 컨텍스트 결함 매트릭스 + 누적 hotfix 매트릭스 + 사용자 의제 발의("거기선 잘되는게 우린 왜 안되겠니?") + fitness gap · §2 KIS 표준 패턴 tr_id 9건(H0STCNT0·H0NXCNT0·H0STASP0·H0NXASP0·H0UNASP0·H0STOAA0·H0STOAC0·H0STOUP0·H0STCNI0) + 단일 session 다중 tr_id + magicJar 현행과 차이 매트릭스 · §3 OPSP8996 root cause 가설 매트릭스 6건(A-매 doConnect invalidate ★ 가장 가능성 / B-destroy ACK 부재 / C-heartbeat 10s 짧음 / D-15:30 KIS auto-disconnect / E-keychange race / F-multi-tr_id session 충돌) · §4 PINGPONG heartbeat 표준 정합 ✓ + watchdog timeout fine-tune 필요 · §5 시간외/NXT 활성 시간 매트릭스(H0STCNT0 09:00~15:30 + H0STOUP0 16:00~18:00 + H0NXCNT0 08:00~20:00 + H0STOAA0 16:00~18:00) · §6 magicJar 현재 코드 구조 인용(PR #443·#444·#445 통합 후) · §7 권유 fix 매트릭스 7 의제 A·B·C·D·E·F·G · §8 Rollback · §9 비범위(post-MVP/backlog 7건) · §10 사용자 결정 의제 architect 권장 = A-1+B-2+C-1+D-1+E-1+F-1+G-1 · §11 사용자 채택 후 sub-agent 디스패치 매트릭스 Phase 1·2·3·4(Phase 1 market-data A-1+F-1 / Phase 2 market-data D-1+E-1 / Phase 3 market-data B-2 H0STOUP0 + KisMarketSessionFsm 합류 / Phase 4 strategy-engine G-1 시간 게이트 별도 사이클) · §12 KIS-first raw fetch 출처 매트릭스 6건 · §13 변경 이력 · §14 사용자 메모리 정합 7건. 의제 권장안 핵심: **A-1**(PR #444 invalidate revert + 24h cache reuse — `auth_ws()` 표준 정합) · **B-2**(H0STOUP0 16:00~18:00 dynamic subscribe + KisMarketSessionFsm 합류) · **D-1**(heartbeat 10s→30s + 정규장 종료 후 60s) · **E-1**(destroy ACK 2s 대기) · **F-1**(SubscriptionResponse OPSP8996 self-heal + 1회 retry 지수 백오프) · **G-1**(시간외 룰엔진 매칭 동등 + 주문 시간 RiskGuard 게이트). 코드 변경 0 LOCK (분석 사이클). spec 본문: `_workspace/specs/pr-447-kis-ws-nxt-standard-spec.md`. ADR README §5 시장 데이터 카테고리 14건 갱신 (0076 Proposed) + §11 후속 ADR 번호 0077로 시프트 + §12 후속 예정 0076 row 추가. backlog §54 신규 (의제 §7-C·§9 비범위 7건 등록). CHANGELOG slot single row anchor `pr-447-slot-architect`. 사용자 결정 위임 — 채택 후 별도 사이클 sub-agent 디스패치 (Phase 1·2 market-data 직렬 + Phase 3 H0STOUP0 후속 + Phase 4 strategy-engine 별도). 사용자 메모리 정합 (feedback_kis_first_policy raw fetch 100% 인용 + feedback_v3_skepticism_no_regression 자본 보호 critical + feedback_3tier_hierarchy architect 분석 → 사용자 결정 → sub-agent 디스패치 + feedback_spec_plan_scope 분석 spec/plan only + feedback_verification_strict 누적 hotfix 비판 + feedback_no_mock_data 시간외 실 KIS tick + project_system_vision AI 0). (architect, opus 4.7 1M) |
| 2026-05-15 (ADR 0075) | **ADR 0075 T-NEWS 트랙 진입 spec/plan 신규 발행 정합 (v3.6.7 patch — 사용자 결정 8 의제 채택)** — §10 인덱스 0075 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074·0075 Accepted + 0064 Proposed + 0073 Superseded by 0075`) + §10 0073 row supersede 표기 + §10 후속 예정 0075 row 추가 (0073 supersede + 0075 진입). §A.4 News & Disclosure 본문 풀 갱신 (ADR 0075 정합 — DART cron 1분 강화 + Flyway V32 disclosure + Kafka disclosure.new.v1 + Naver Open API + Flyway V33 news_article + Kafka news.collected.v1 + Watchlist 우선 polling 분당 17건 hard limit + sidebar polling 60s + REST 2 endpoint Swagger 4단 + KIS endpoint 호출 0 + AI 호출 0 + 끊겨도 본체 영향 0 + T-NEWS 트랙 v3.6.7 patch 진입). ADR 0073 supersede (analysis only → 결정 채택 + 정밀 spec 위임). 14 § LOCK — 사용자 결정 8 의제 채택 매트릭스(1=A 옵션 별도 트랙 신설·2-1 1분·3-1 Phase 2 .env·4-2 Slack 알림만·5-1 T-AI 흡수·6-1 sidebar·7-1 2 토픽 분리·8 v3.6.7 patch). Phase 분리 LOCK 3 PR 직렬(market-data Phase 1 DartDisclosureAdapter cron 5분→1분 강화 + Flyway V32 disclosure + DisclosureKafkaProducer disclosure.new.v1 + REST GET /api/symbols/{code}/disclosures Swagger 4단 + is_correction 분류 → backend-core Phase 2 NaverNewsAdapter + Flyway V33 news_article + NewsKafkaProducer news.collected.v1 + REST GET /api/symbols/{code}/news Swagger 4단 + Watchlist 우선 cron + .env NAVER_OPEN_API_CLIENT_ID·SECRET 추가 + Redis ZSET 분당 17건 rate limit → frontend Phase 3 NewsPanel + DisclosurePanel + useSymbolNews + useSymbolDisclosures hook + SymbolDetailPage sidebar mount + refetchInterval 60s polling + 정정공시 빨간 배지 강조 + 빈 상태 한국어 + Naver 키 미등록 안내). Flyway V32 disclosure + V33 news_article 분리 LOCK (멱등성 UQ ux_disclosure_rcept_no + ux_news_article_source_stock_url_hash IF NOT EXISTS). Kafka 신규 2 토픽(disclosure.new.v1 + news.collected.v1 retention 7d schema LOCK eventId UUID + schemaVersion=1). Redis dedup (news:dedup:rcept + news:dedup:url TTL 24h) + rate limit (news:ratelimit:dart 60건/분 + news:ratelimit:naver 17건/분 ZSET 슬라이딩). STOMP 신규 0 LOCK (polling 60s 채택 — 의제 6-1). REST 신규 2 endpoint Swagger 4단(GET /api/symbols/{code}/news + /disclosures 페이징 + 기간 필터 + onlyCorrection). DART list.json + Naver search/news.json **풀 raw fetch 인용 의무 충족** (헌법 §3 5단계 절차 — 자체 추론 0 + 공식 출처 raw fetch). KIS endpoint 호출 0 LOCK (ADR 0057 시세 단일 정합 + 뉴스 보조 master §B-3 OK) + AI 호출 0 grep 검증 의무 6 디렉토리 (market-data + backend-core 인프라 + controller + frontend sidebar — sentiment 컬럼 NULL only 1차) + mock 합성 0 ("데이터 없음" 한국어 + Naver 키 미등록 시 "환경변수 NAVER_OPEN_API_CLIENT_ID 설정 후 자동 시작" 안내). 검증 시나리오 3건 (DART 1분 정정공시 분류 + Slack P0 정합 ADR 0044 reuse · Naver graceful skip + 키 등록 후 polling · sidebar 60s polling + unmount cleanup + 정정공시 배지). 비범위 + backlog 흡수 (Phase 4 RSS 한경·구글 → backlog §53 신규 잔류 + 감성 분석 → T-AI backlog §62 + 뉴스 본문 풀 텍스트 영구 비범위 + 영어 뉴스 post-MVP + STOMP 정정공시 push backlog §53 + SettingsModal NaverApiSection T-AI 사이클 흡수 ADR 0094+). Rollback 시나리오 3건 (cron 빈도만 revert · NaverNewsAdapter toggle 0 · sidebar mount 단독 revert). 산출 ~25 파일 매트릭스. ADR README §5 시장 데이터 카테고리 13건 갱신 (0075 Accepted + 0073 Superseded) + §11 후속 ADR 번호 0076로 시프트 + §12 후속 예정 0073 supersede + 0075 row 추가. tracks-roadmap §2 T-NEWS row Accepted 격상 (W3.5 → v3.6.7 patch) + §3 T-NEWS outline 신규 + §8 변경 이력 row. backlog §52 ADR 0075 흡수 마킹 + §53 신규(Phase 4 RSS 잔류). 사용자 자율 위임 정합 ("ADR 0073 T-NEWS 트랙 진입" + "T-NEWS 트랙 진입 진행해" 2026-05-15). 사용자 메모리 정합 (feedback_release_policy_patch_first v3.7.0=W4 LOCK → v3.6.7 patch 진입 · feedback_v3_skepticism_no_regression fitness gap 검증 · feedback_kis_first_policy 뉴스 보조 OK · feedback_no_mock_data 한국어 안내 · feedback_secret_handling .env 단독 저장 + UI 0건 1차). 직전 architect dispatch 529 Overloaded fail 후 재dispatch. spec 본문: `_workspace/specs/pr-434-t-news-spec.md` (PR 번호 434 가정). CHANGELOG slot 4 anchor(architect + market-data Phase 1 + backend-core Phase 2 + frontend Phase 3 — Phase별 in-place edit). (architect, opus 4.7 1M) |
| 2026-05-14 (ADR 0074 재발행) | **ADR 0074 SettingsModal 풀세트 spec/plan 신규 발행 정합 (재발행 — v3.6.5 patch)** — §10 인덱스 0074 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072·0074 Accepted + 0064·0073 Proposed`) + §10 후속 예정에 0074 row 정합. **재발행 사유**: 직전 architect dispatch 시 ADR 번호 충돌 — T-NEWS(PR #414 머지됨, ADR 0073 점유)와 본 사이클(PR #415 close됨) 둘 다 ADR 0073으로 발행되어 PR #415 close. 본 ADR은 0074로 재발행 + cross-ref 정합(ADR 0073=T-NEWS 점유, 본 ADR=SettingsModal 풀세트). 결정 내용 변동 0 — close된 PR #415 (`agent/architect/settings-kis-change-spec` branch 보존)의 ADR 본문 + spec 본문 cherry-pick 후 번호만 갱신. 12 § LOCK — §1 fitness · §2 KIS 키 변경 풀세트(PUT/GET/DELETE + KisAuthValidator 통과 UPDATE atomicity + Redis access_token + kis:approval DEL + kis:key-changed-lock SET 5초 + Kafka system.kis-key-changed.v1 publish + WS graceful reconnect, MOCK만 DELETE 허용 REAL DELETE 금지) · §3 KillSwitch 수동 토글(SettingsModal Section 4 신설 + GET·PUT /api/system-config/kill-switch + ConfirmDangerToggle reuse + 사유 입력 minLength=5 maxLength=500 + 60초 cooldown + killswitch.engaged.v1 schema 확장 engaged:boolean) · §4 OpenAI/Anthropic 키 카테고리(env 유지 + Section 5 UI 0건 1차 + T-AI 사이클 실구현 분리) · §5 Flyway 0건 LOCK(baseline reuse) · §6 REST 신규 5건 + SystemConfigController 신규 · §7 Redis invalidate 매트릭스 LOCK · §8 frontend UI 매트릭스(Section 2 submit 활성화 + Section 4 신설 + ConfirmDangerToggle 실구현 + 5 hook) · §9 Phase 분리 LOCK(Phase 1 backend-core + Phase 3 consumer add-only 병렬 가능 + Phase 2 frontend 직렬) · §10 검증 시나리오 3건 · §11 ADR 0069 §7 backlog 흡수(§A·§D·§G + §E 사전 정합 + §F·§H·§I·§J 잔류) · §12 Rollback 시나리오. AI 호출 0 grep 검증 의무. mock 합성 0(KillSwitch 초기 row 없을 때 "최초 토글 없음" + KIS 키 hasKey=false 시 "등록된 키 없음"). ADR README §10-2 운영·UX Fitness 갭 4→5건 + §11 후속 ADR 번호 0075로 시프트 + §12 후속 예정 0074 row 추가. tracks-roadmap T-ONBOARD outline 갱신(ADR 0074 산출물 매트릭스 prepend) + §8 변경 이력 row. backlog §44+§45 흡수 마킹. spec 본문: `_workspace/specs/pr-416-settings-kis-change-killswitch-ai-spec.md` (PR 번호 416 LOCK). CHANGELOG slot 4 anchor(architect + backend-core-phase1 + consumer-phase3 + frontend-phase2). 사용자 자율 위임 정합 ("§44~§45부터 하자" 2026-05-14). (architect) |
| 2026-05-14 (ADR 0072) | **ADR 0072 T-J + T-PERF 묶음 spec/plan 신규 발행 정합 (W3 마지막 사이클 — v3.6.4 patch)** — §10 인덱스 0072 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071·0072 Accepted + 0064 Proposed`) + §10 후속 예정에 0072 row 추가 + §C.14 T-PERF 본문 보강(5 지표 fixed LOCK + batch DailyPerformanceJob 매일 00:30 KST cron + inline aggregate fallback + Redis cache TTL 1h + Sharpe·Sortino·CAGR backlog §61 + AI 호출 0 LOCK + ADR 0072 정밀 명세 정합) + §D.16 T-J 본문 보강(5 Kafka 이벤트 listen 자동 INSERT trading_journal + journal_note 1:N + 멱등성 UQ source_table+source_id+event_type + 차트 스냅샷 자동 영속 backlog §60 + AI 호출 0 LOCK + ADR 0072 정밀 명세 정합). 8 의제 LOCK A-1~H-1 — A-1 trading_journal 신규 + 5 Kafka listen 자동 INSERT · B-1 batch + inline aggregate 듀얼 · C-1 5 지표 fixed(총 P&L · 일별 P&L · MDD · 승률 · 평균 손익비) · D-1 performance_summary + pnl_daily 영속 신규 · E-1 REST 5 endpoint Swagger 4단 한국어 · F-1 2 페이지 + AppHeader 메뉴 2 추가 · G-1 5 토픽 listen · H-1 AI 호출 0 LOCK 일관성. Phase 분리 LOCK 3 PR 직렬(backend-core T-J Phase 1: domain+Flyway V29+JournalEventConsumer 5 Kafka listen+REST 3 → backend-core T-PERF Phase 2: domain+Flyway V30+batch DailyPerformanceJob+REST 2+Redis cache TTL 1h+PerformanceCalculator pure math MDD NUMERIC(8,4) → frontend Phase 3: 2 페이지+AppHeader 메뉴 2+LWC v4 equity curve+daily P&L bar chart). 모든 데이터 영속 reuse(order·signal·risk·reconcile·position 5 baseline) → **KIS endpoint 호출 0 LOCK** + AI 호출 0 grep 검증 6 디렉토리 + mock 합성 0("매매일지가 비어있습니다" 한국어 graceful). Flyway V29(trading_journal+journal_note 멱등성 UQ ux_trading_journal_source) + V30(performance_summary+pnl_daily IF NOT EXISTS). Kafka 신규 publish 0 + listen 5(order.filled.v1+signal.generated.v1+risk.violation.v1+reconcile.drift.v1+position.updated.v1). STOMP 0(polling). REST 5 endpoint Swagger 4단(GET /journal/entries 페이징 + GET /journal/entries/{id} + POST /journal/notes + GET /performance/summary + GET /performance/equity-curve). 사용자 비전 정합 = ★★★ 운영 회고 의무(자동매매 본체 검증 핵심) + W4 AI 합류 prereq(사용자 메모 → AI prompt context). 검증 시나리오 19건 dogfooding evidence 의무. spec 본문: `_workspace/specs/pr-402-t-j-perf-spec.md`. ADR README §6 10→11건 + §12 후속 예정 0060 Performance 표준 → ADR 0072 흡수 closed + §11 후속 ADR 번호 0073으로 시프트. tracks-roadmap §2 T-J·T-PERF row 격상(★ 표기 + W3 마지막 사이클 + ADR 0072 Accepted) + §3 T-J·T-PERF outline 풀 보강(ADR 0072 정합) + §8 변경 이력 row 추가. backlog §59~§67 예정 신규 등록(journal.recorded.v1 STOMP push + chart_snapshot 영속 강화 + Sharpe·Sortino·CAGR 풀세트 + W4 AI 회고 코멘트 + soft delete 정책 + 종목별 view + 전략별 view + summary 시계열 mini-chart + RFR 정책). 사용자 자율 최종권한 위임 정합 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). v3.6.3 T-SC 마감 후 운영 회고 합류 단일 사이클 v3.6.4 patch (W3 마지막 사이클). CHANGELOG slot 4 anchor(architect + backend-core-tj + backend-core-tperf + frontend — Phase별 in-place edit). (architect) |
| 2026-05-14 (ADR 0071) | **ADR 0071 T-SC Screener spec/plan 신규 발행 정합** — §10 인덱스 0071 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070·0071 Accepted + 0064 Proposed`) + §10 후속 예정에 0071 row 추가 + §C.10 T-SC 본문 보강(ADR 0071 정밀 명세 정합 + Redis cache 30m + 옵션 영속 V28 + KIS endpoint 호출 0 LOCK + Strategy Builder 합류) + §3-1 [3] DailyScreenerJob row 갱신(1차 inline 동기 평가 모드 + cron은 post-MVP backlog §55) + §6 Kafka `screener.result.v1` row deprecated 표기(v3.6.3 동기 inline 채택, 비동기 모드 backlog §52) + §12 후속 예정 0059 Screener 표준 → ADR 0071 흡수 closed. 8 의제 LOCK A-1~H-1 — A-1 동기 inline + Redis cache · B-1 instrument 영속 + 필터 매트릭스 · C-1 strategy-eval-pool reuse + universe screening timeout 500ms · D-1 cache-only + D-2 옵션 영속 env toggle · E-1 multi-criteria 4기준 fixed weights · F-1 REST 3 endpoint Swagger 4단 · G-1 candle 결손 graceful skip + skippedReasons · H-1 Strategy Builder 합류 + ScreenerResultModal. Phase 분리 LOCK 3 PR 직렬(strategy-engine Phase 1 domain+consumer → backend-core Phase 2 api+Redis+옵션 Flyway V28 → frontend Phase 3 Strategy Builder 합류). universe=instrument 영속 reuse + candle=영속 reuse → **KIS endpoint 호출 0 LOCK** + AI 호출 0 grep 검증 + mock 합성 0. Flyway V28(screening_run + screening_match, env toggle MAGICJAR_SCREENER_PERSIST=true). Kafka 신규 0. STOMP 신규 0. REST 3 endpoint Swagger 4단(POST evaluate + GET runs list + GET runs detail). 사용자 비전 정합 = Strategy Builder "전략으로 종목 찾기" 흐름 단절 회복 + Watchlist 일괄 등록 합류. spec 본문: `_workspace/specs/pr-393-t-sc-spec.md`. ADR README §6 9→10건 + §12 후속 예정 0059 closed + §11 후속 ADR 번호 0072로 시프트. tracks-roadmap §2 T-SC row 갱신(ADR 0071 Accepted + 산출물 매트릭스 prepend) + §3 T-SC outline §15 변경 이력 row 추가. backlog §52~§58 예정 신규 등록(Screener 비동기 모드 + `/screener` 단독 페이지 + 사용자 custom 랭킹 가중치 + DailyScreenerJob cron + Watchlist bulk POST endpoint + Screener AI 해석 + screening_match retention). 사용자 자율 최종권한 위임 정합 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). v3.6.0 T-O + v3.6.2 T-RECON·T-NOTIFY 마감 후 종목 발굴 자동화 단일 사이클 v3.6.3 patch. CHANGELOG slot 3 anchor(architect + strategy-engine + backend-core + frontend — Phase별 in-place edit). (architect) |
| 2026-05-13 (ADR 0070) | **ADR 0070 T-RECON + T-NOTIFY 묶음 spec/plan 신규 발행 정합** — §10 인덱스 0070 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070 Accepted + 0064 Proposed`) + §10 후속 예정에 0070 row 추가 + §6 Kafka topic 매트릭스 갱신(`position.drift.v1` → **`reconcile.drift.v1`** 명명 갱신 + **`reconcile.run.v1`** 신규 publish 7d + **`killswitch.engaged.v1`** 신규 publish 30d + `position.reconciled.v1` row deprecated). ADR 0041·0044 baseline reuse + 정밀 명세 §1~§15. Phase 분리 LOCK 3 PR 직렬(T-RECON Phase 1 strategy-engine → Phase 2 backend-core → T-NOTIFY Phase 1 backend-core). KIS-first raw 4건(TTTC8434R·VTTC8434R + CTRP6548R + H0STCNI0 cross-ref). Flyway V25·V26·V27. REST 3 endpoint. frontend 0 LOCK. 자본 보호 흐름 LOCK(drift → KillSwitch ON → Slack P0 → STOMP). spec 본문: `_workspace/specs/pr-392-recon-notify-spec.md`. ADR README §10-1·§10-2 row에 "ADR 0070 묶음 spec 정밀화" 표기 + §11 후속 ADR 번호 0071로 시프트. tracks-roadmap T-RECON·T-NOTIFY outline에 ADR 0070 산출물 매트릭스 prepend + §8 변경 이력 row. backlog §46~§51 신규 등록 (Risk timeline UI + drift 임계치 사용자 설정화 + MarketSessionFsm cron skip + user별 webhook 분리 + Discord/Email/SMS 보조 채널 + ADR 0045 startup/shutdown publish 합류). 사용자 자율 최종권한 위임 정합 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). v3.6.0 T-O 마감 후 자본 보호 prereq 단일 사이클 v3.6.2 patch. (architect) |
| 2026-05-13 (ADR 0069) | **SettingsModal vs SetupWizard 책임 분리 ADR 0069 신규 발행 정합** — §10 인덱스 0069 row 추가 + §10 헤더 카운트 갱신(`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069 Accepted + 0064 Proposed`) + §3-1 부수 흐름 Onboarding Setup row에 SettingsModal 통합 명시(`ADR 0046 + 0069`) + tracks-roadmap T-ONBOARD outline에 ADR 0069 산출물 매트릭스 prepend(SetupWizard·SettingsModal·forms 6 공통 컴포넌트). README §10-2 운영·UX Fitness 갭 3→4건 + README §11 후속 ADR 번호 0070으로 시프트. dogfooding 결함 응답 — RealTradingConsentToggle UI 접근 가능 path 확보 → T-O 자본 보호 검증 케이스 1·2·3 unblock. ADR 0046 supersede X — reuse + 보강. KIS-first raw 인용 0건(UI 책임 분리 영역). (architect) |
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
| 2026-05-11 (PR #294) | **KIS-only 데이터 정책 ADR 신규 + ADR 0019 supersede + 5m~1h backlog §31 의제 등록** — 사용자 dogfooding 결함 root cause(Yahoo v8 chart endpoint가 `interval=1d&range=max` 조합에서 monthly downsample 응답 반환 → 일봉 위치에 월봉 INSERT) + 사용자 명시 결정 "야후꺼 쓰지 말라니깐" / "kis에 있는 데이터만 사용하라고". ADR 0057 발행(KIS OpenAPI 단일 데이터 출처 — 매트릭스 재정의 + cold-start KIS REST 단일 + 5m·15m·30m·90m·1h "데이터 없음" + Rollback 시나리오 + raw 출처 FHKST03010100·FHKST03010200·FHKST03010230 인용). ADR 0019 상태 → Superseded by 0057. ADR 0028 cold-start KIS REST 정합 갱신. ADR 0029 Yahoo 인용 정정. external-api-policy §3 Yahoo Finance 폐기 표기. candle-data-policy-v2 매트릭스 KIS-only 재정의(§3·§4·§5·§6·§8·§9·§10). master §2-A.2 + §6 모듈 표 + §7 컨테이너 표 + §10 ADR 0019 supersede 표기 + §10 ADR 0057 row 추가 + §12 후속 예정 시프트(기존 0057~0060 → 0058~0061, 0062 5m~1h 결정 ADR 예약). backlog §31 신규 등록(옵션 A·B·C — architect 권장 B+C 하이브리드, 사용자 결정 대기). 코드 변경 0건(market-data sub-agent 별도 PR). fitness gap 분석 + Rollback 시나리오 명시. (architect) |
| 2026-05-11 (PR #294 amend) | **사용자 정책 정정 2건 반영** — (1) ADR 0019 supersede revoke → **Accepted (Yahoo 의존 부분만 보류)** 복원. 사용자 명시 "5m·15m·30m·90m·1h 백엔드 또는 프론트 합산으로 할거고 ADR 폐기해버리면 안될듯 1분봉만 kis에서 제공하니깐 임시로 쓰는거고 나중에 5m·15m·30m·90m·1h 봉에 대한 처리를 보류할뿐임 ... ADR 폐기는 하지말어 백로그로 남겨". ADR 0019 §2 매트릭스 결정 골격은 여전히 유효 — Yahoo 의존 부분만 보류 + 5m·15m·30m·90m·1h은 **1m 합산 가공 방향 확정** (backlog §31 트랙에서 위치·source 결정). (2) KIS 키 분기 제거 — 사용자 명시 "모의키는 사용하지 않고 실전키만 쓴다고 ... 정보 수집 API는 다 실전 키를 사용한다고했던거같은데". 정보 수집 API는 **prod credential 단일 채널** (모의/실전 분기는 주문 라우터에만 적용). 이전 권장안 "옵션 B+C 하이브리드 (실전 키 vs 모의 키 분기)"는 잘못된 가정으로 제거. backlog §31에서 옵션은 **위치(백엔드 vs 프론트) + source 시간 범위(당일 vs `FHKST03010230` 과거 포함)** 로 재정의 (KIS 키 분기 0). ADR 0019·0057·0028·0029·README·external-api-policy·candle-data-policy-v2·master-architecture·backlog §31 모두 동시 정정. CHANGELOG row 1건 amend. 코드 변경 0건. v1/v2 잔재 검증 0건. fitness gap: 사용자 명시 정정으로 회귀 risk 해소. (architect) |
| 2026-05-11 (PR #298 amend + #299 amend) | **ADR 0062·0063 Accepted 갱신 (사용자 결정 인용 2건)** — PR #298 ADR 0062 — Status Proposed → Accepted. 사용자 결정 3건 인용("A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화 — 2026-05-11 사용자 채팅 명시"). §2-4 사용자 채택 결과 절 추가. §9 NXT 결합 영향 절 신규(ADR 0063 정합) — boundary 정렬 매트릭스 확장(5m 78→144·15m 26→48·30m 13→24·90m 5→8·1h 7→12 bar/일) + DB ROW 38K→71K(1.85배) + boundary 마감 트리거 16:00→20:00 KST + sub-agent 작업 분리 권장(NXT cover 선행). PR #299 ADR 0063 — Status Proposed → Accepted. 사용자 결정 3건 인용("A UN 통합 + 시간 1 08:00~20:00 + threshold 700 — 2026-05-11 사용자 채팅 명시"). 영향 ADR/문서 매트릭스(ADR 0019·0028·0029·external-api-policy·candle-data-policy-v2)는 별도 사이클(modify-shared 회피 — add-only 100% 본문 § 추가만). README §5 카운트 8건 → 10건(0062·0063 추가) + §11 후속 ADR 번호 0064부터 시프트 + §12 후속 예정 row 갱신(Accepted 격상). master §10 0062·0063 Accepted row + §12 후속 예정 row 갱신. CHANGELOG row 2건. (architect) |
