# PR-10 T-S1 설계 가이드 (Symbol Detail: 종목 상세 + 현재가 + LWC 차트 + PER/PBR/시총)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-10a (frontend) · PR-10b (market-data) — 병렬 가능 (§A 충돌 행렬 참조)
- 진입 prerequisite: PR-5/6/7/8/9 모두 MERGED ✅. main HEAD = PR-9 머지 후 (`2d556a3` 직후). Watchlist DB row + REST 3 endpoint + watchlist.changed.v1 Kafka producer/consumer + KIS WebSocket bridge + KisRestPriceAdapter + Yahoo cold-start + candle 영속 + Redis quote:last 갱신 흐름 모두 동작.
- **본 PR-10 = 사용자 첫 dogfooding 직전 사이클** — T-S1 머지 후 사용자가 화면에서 watchlist row 클릭 → 종목 상세 → LWC 차트·PER/PBR/시총 직접 검증.
- 정합 SoT:
  - **ADR 0019** 캔들 저장 정책 (Yahoo 매트릭스 + DB 영속 — 본 PR LWC series source 1급 권위)
  - **ADR 0029** 당일 진행 중 캔들 Redis 캐시 (current 1m·today 1d — 실시간 갱신 source)
  - **ADR 0048** KIS WS 41건 한도 + LRU 호가 10 (본 PR이 H0STASP0 호가 slot 첫 사용)
  - **ADR 0040** 관심종목 우선 UI 흐름 (Watchlist row → SymbolDetail 진입점)
  - **ADR 0025** LWC 확장 — TradingView Advanced 폐기, LWC v4 공식 API 한정
  - **ADR 0014** 시세 데이터 Fallback 3-tier (live → cache → last-day-close)
  - **ADR 0028** Watchlist 기반 Ingestion Worker (PR-9 가동 중)
  - **ADR 0037** KIS Rate Limit 강제 (사용자 path 우선)
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (PR-10이 두 번째 적용 사이클 — PR-9 운영 결과 보강)
  - `docs/architecture/master-architecture.md` v2 §A.2(시장 데이터)·§D.17(Chart UI)
  - `docs/architecture/candle-data-policy-v2.md` cold-start + LWC 흐름
  - `docs/operations/kis-api-reference.md` §3 — `inquire-price` `FHKST01010100`(이미 PR-9b 가동 중)·`FHKST03010100`(PR-9 가동 중)
  - `_workspace/specs/pr-9-w2-design.md` §5 Yahoo cold-start + §7 데이터 흐름
  - `_workspace/specs/pr-7-w1-design.md` §6 Watchlist UI 계약
- 작업 원칙 (개발 룰북 정합):
  - 룰 2 — 단일 책임 (PR-10a = frontend SymbolDetail 페이지·LWC 차트·메타 카드, PR-10b = consumer 추가 어댑터·STOMP fanout·api REST endpoint·domain port. **양쪽 다 backend·frontend 영역 분리지만 backend 안에서도 책임 분리** — §A.2 sub-agent 분배 참조)
  - 룰 3 — API 빌딩 한 스텝씩 순차. **본 PR-10 = R3 라운드** (사용자 화면 + 일괄 통합). T-S1 자체가 W1 마지막 트랙이라 R1·R2가 PR-7·PR-9에서 끝남.
  - 룰 4 — 코드 먼저, 테스트 마지막 일괄 (§9 시나리오 골격 — 본 PR-10에서 사용자 dogfooding 시나리오 수동 검증 + sub-agent smoke 1~2건만)
  - 룰 5 — AI 호출 0 (W1 트랙 — LLM provider import 절대 금지)

---

## §1. T-S1 한 줄 목표 + DoD

### 1-1. 목표

> **사용자가 Watchlist 페이지에서 종목 row를 클릭하면 SymbolDetail 화면(`/symbol/:code`)으로 이동한다. 화면에 (1) 현재가·전일대비·등락률 카드, (2) PER/PBR/시총 메타 카드, (3) LWC v4 차트(인터벌 1m·5m·30m·1h·1d 5종 토글), (4) 호가창(10단계, 옵션 — 본 PR-10 1차 표시 권장), (5) 이전 페이지 복귀 버튼이 표시된다. 차트 series는 PR-9에서 영속한 candle DB row + 당일 진행 중 Redis cache(`candle:current` / `candle:today`)에서 조회한다. 현재가는 `quote:last:{code}` Redis cache 1순위 + STOMP `/topic/market.tick.{code}` 실시간 push 옵션. 호가창은 KIS WS `H0STASP0` LRU 10 slot(ADR 0048 §2-1) 동시 구독 — 페이지 진입 시 sub, 페이지 unmount 시 unsub. PER/PBR/시총은 KIS REST `inquire-price`(PR-9b 가동 중 어댑터 재사용) + 종목명·업종은 동일 endpoint 또는 instrument 테이블에서.**

### 1-2. DoD (Definition of Done) — PR-10 전체 머지 후 검증 (사용자 dogfooding 시나리오)

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | Watchlist row 클릭 → SymbolDetail 진입 | UI: `/watchlist` 페이지 → 005930 row 클릭 | URL `/symbol/005930` 이동, 종목명 "삼성전자" 헤더 표시 |
| 2 | 현재가 카드 표시 | 위 진입 후 1초 이내 | 현재가·전일대비·등락률(%) 카드 (장중 live 또는 stale 배지 ADR 0014 정합) |
| 3 | PER/PBR/시총 카드 표시 | 위 진입 후 2초 이내 | PER 12.5·PBR 1.3·시총 4,800,000(억원) 등 (KIS `inquire-price` source) |
| 4 | LWC 차트 첫 표시 (1d default) | 위 진입 후 3초 이내 | LWC v4 candlestick 차트 — 1d 인터벌 100 row 이상 |
| 5 | 인터벌 토글 1m·5m·30m·1h·1d | UI: 토글 버튼 클릭 | 차트 series 즉시 갱신, 빈 데이터면 "데이터 수집 중" 명시 |
| 6 | 차트 series 페이지네이션 | 차트 좌측 스크롤 또는 [더 보기] 버튼 | 과거 candle 200 row 추가 fetch + 차트 즉시 확장 |
| 7 | 실시간 가격 push (옵션) | 장중 1분 대기 | STOMP `/topic/market.tick.005930` 메시지 도착 → 현재가 카드 1초 throttle 갱신 (옵션 — 1차는 polling 5s OK) |
| 8 | 호가창 표시 (옵션) | SymbolDetail 진입 시 | 매수·매도 10단계 호가표 표시 (장중) — KIS WS `H0STASP0` 구독 |
| 9 | 호가 LRU 토글 | 다른 종목 SymbolDetail 진입 → 또 다른 종목 (10번째 초과) | 가장 오래된 호가 stream unsubscribe + 새 종목 호가 subscribe (ADR 0048 §2-1 정합) |
| 10 | 페이지 unmount 시 호가 unsub | SymbolDetail에서 [관심종목으로] 버튼 또는 다른 라우트 | 즉시 `H0STASP0` unsubscribe + slot 회수 (1초 이내) |
| 11 | 이전 페이지 복귀 | SymbolDetail에서 [관심종목으로] 버튼 | `/watchlist` 이동 + 이전 스크롤 위치 보존(권장 — 본 PR-10은 필수 아님) |
| 12 | 데이터 부재 시 graceful | 잘못된 code 직접 URL 진입 (`/symbol/999999`) | "종목 정보를 찾을 수 없습니다" 명시 + [관심종목으로] 버튼 표시. mock 합성 0 (사용자 메모리 `feedback_no_mock_data.md` 정합) |
| 13 | 차트 candle 0건 graceful | Yahoo cold-start 미완 종목 진입 | "데이터 수집 중 — 잠시 후 새로고침" 명시 + 1d candle 부재 시 1m candle만으로도 차트 표시 시도 |
| 14 | 모바일 반응형 (옵션) | 사용자 모바일 viewport | LWC 차트 + 메타 카드 stacked layout (1차는 desktop 우선, 후속 정합 OK) |
| 15 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer,batch}/src/main` | 0건 |
| 16 | 사용자 dogfooding 통과 | 사용자가 본인 KIS 키로 본인 watchlist 1+ 종목 등록 후 SymbolDetail 진입 | 사용자가 화면에서 "쓸만하다" 평가. 발견된 결함은 backlog 또는 hotfix |

### 1-3. 비즈니스 가치

- magicJar W1 마지막 트랙 — **사용자가 첫 dogfooding** (사용자 메모리 `project_track1_dogfooding_paused.md` 다음 사이클 재개 정합)
- ADR 0040 관심종목 우선 UI 흐름의 마지막 step (등록 → 시세 인입 → **상세 표시**)
- LWC v4 공식 API 첫 본격 사용 (ADR 0025 정합) — TradingView Advanced 폐기 베이스라인
- 후속 트랙(T-SB·T-O·T-PAT·T-IND·T-LWC) 모두 SymbolDetail 화면을 베이스로 확장

---

## §2. ADR 0019·0029·0048 준수 항목 체크리스트

### 2-1. ADR 0019 (캔들 저장 정책)

| ADR 0019 §위치 | 결정 사항 | PR-10 구현 위치 | 상태 |
|---|---|---|---|
| §2-1 영속 매트릭스 | 분봉 1m·5m·15m·30m·90m·1h DB only / 1d·1wk·1mo·1y DB only / 당일 진행중 Redis & DB | api 신규 `CandleQueryService` — DB candle row + Redis candle:current·today 합성 | **PR-10b 핵심** |
| §2-2 UI 인터벌 버튼만 | 자유 입력 폐기 — 1m·5m·30m·1h·1d 5종 토글 (1차 MVP — 2m·15m·90m·1wk·1mo는 후속) | frontend SymbolDetail 차트 컴포넌트 인터벌 picker | **PR-10a 핵심** |
| §2-3 cold-start 흐름 | 사용자 진입 시 모든 interval 즉시 응답 (PR-9 cold-start 정합) | API 응답에서 candle 0건이면 "데이터 수집 중" — frontend graceful | **PR-10a + PR-10b** |
| §2-5 합성 폐기 | `candleAggregator.ts` 폐기 — Yahoo 직접 매트릭스 그대로 노출 | frontend 차트 series는 backend가 그대로 전달 (가공 0) | **PR-10a 정합** |

### 2-2. ADR 0029 (당일 진행 중 캔들 Redis)

| ADR 0029 §위치 | 결정 사항 | PR-10 구현 |
|---|---|---|
| §2-1 candle:current·today | tick 도달마다 Redis 갱신 (PR-9b TickToMinuteAggregator 가동 중) | API `CandleQueryService`가 1m 인터벌 응답 시 마지막 candle = Redis `candle:current:{code}:1m` 합류 | **PR-10b 핵심** |
| §2-3 1m closing 시 STOMP fanout | `/topic/market.candle.minute.{code}` (PR-9 비범위였음 → 본 PR-10에서 첫 발행) | api STOMP broker config + consumer publish (또는 api에서 Kafka listener → STOMP) | **PR-10b 핵심** |
| §2-5 STOMP topic 매트릭스 | `/topic/market.tick.{code}` · `/topic/market.candle.minute.{code}` · `/topic/market.candle.daily.{code}` | api `WebSocketConfig` + `MarketTickStompForwarder` (Kafka listener → STOMP convertAndSend) | **PR-10b 핵심** |

### 2-3. ADR 0048 (KIS WS 41건 + LRU 10 호가)

| ADR 0048 §위치 | 결정 사항 | PR-10 구현 |
|---|---|---|
| §2-1 H0STASP0 호가 10 slot | 사용자 SymbolDetail 동시 호가창 LRU 10 | consumer `SubscriptionRouter` 호가 LRU 추가 (PR-9는 체결가만) | **PR-10b 핵심** |
| §2-1 호가 LRU 토글 | 페이지 진입 시 sub, 페이지 unmount 또는 11번째 진입 시 LRU evict | api → consumer Kafka command (`symbol.detail.entered.v1`/`exited.v1`) 또는 REST 호출 | **PR-10b 핵심** (interface §3-2 LOCK) |
| §2-1 throttle 200ms | 빠른 페이지 전환 시 sub/unsub 폭증 차단 | consumer SubscriptionRouter 200ms debounce | **PR-10b 정합** |

### 2-4. ADR 0040 (관심종목 우선 UI)

| ADR 0040 §위치 | 결정 사항 | PR-10 구현 |
|---|---|---|
| §2-2 진입점 — Watchlist row 클릭 | row 클릭 → `/symbol/:code` | frontend `WatchlistRow.tsx` 정정 — onClick → useNavigate(`/symbol/${entry.code}`) | **PR-10a 핵심** (1줄 변경 modify) |
| §2-4 SymbolDetail 핵심 카드 | 현재가·메타·차트·호가 | frontend `SymbolDetailPage.tsx` 신규 | **PR-10a 핵심** |

### 2-5. ADR 0025 (LWC v4 공식 API only)

| ADR 0025 §위치 | 결정 사항 | PR-10 구현 |
|---|---|---|
| §2-1 LWC v4 한정 | TradingView Advanced 의존 0, lightweight-charts ^4.2.0 (PR-5b 정합) | frontend SymbolDetail 차트 컴포넌트 import 검증 | **PR-10a 정합** |
| §2-2 차트 시그니처 | `createChart`·`addCandlestickSeries`·`setData`·`update` 4개 API 한정 | frontend `LightweightChart.tsx` (또는 동등) — overlay layer는 본 PR-10 비범위 (T-LWC) | **PR-10a 핵심** |

### 2-6. 룰 5 (AI 호출 0)

- `grep -rE "spring-ai|anthropic|openai" {api,consumer,batch}/src/main` → 0건
- frontend도 OpenAI/Anthropic SDK import 0건

---

## §3. REST API 시그니처 LOCK (핵심 결정)

본 절이 PR-10의 **가장 중요한 backend 결정**. 인터페이스 동결.

### 3-1. endpoint 매트릭스 (3개)

| # | Method | path | 용도 | 응답 source | 인증 |
|---|---|---|---|---|---|
| 1 | `GET` | `/api/symbols/{code}` | 종목 메타·현재가 (단일 응답) | KIS `inquire-price`(PR-9b 어댑터 재사용) + Redis `quote:last` 합류 | OnboardingGuardFilter (PR-6 정합) |
| 2 | `GET` | `/api/symbols/{code}/candles?interval={1m\|5m\|30m\|1h\|1d}&before={iso8601}&limit={int<=500}` | 차트 series — DB candle + Redis current 합류 | DB candle row + Redis | OnboardingGuardFilter |
| 3 | `POST` | `/api/symbols/{code}/orderbook/subscribe` (옵션) | 호가창 stream slot 사용 통보 | consumer SubscriptionRouter LRU | OnboardingGuardFilter |
| 4 (옵션) | `POST` | `/api/symbols/{code}/orderbook/unsubscribe` | 페이지 unmount 시 slot 회수 | 동일 | OnboardingGuardFilter |

**대안 검토**: endpoint 3·4 대신 STOMP subscribe 라이프사이클로 호가 slot 관리 가능 (frontend가 `/topic/market.orderbook.{code}` subscribe 시 backend가 자동 인지). 단 **1차 MVP는 명시적 REST endpoint 권장** (라이프사이클 명료성·debugging 용이성). 후속 정합은 backlog 후보.

### 3-2. endpoint 1: `GET /api/symbols/{code}` 응답 스키마

```json
{
  "code": "005930",
  "name": "삼성전자",
  "market": "KOSPI",
  "industry": "전기전자",
  "currentPrice": "60900",
  "previousClose": "60500",
  "change": "400",
  "changeRate": "0.66",
  "high": "61000",
  "low": "60750",
  "open": "60800",
  "volume": 12345678,
  "marketCap": "4800000",
  "per": "12.5",
  "pbr": "1.3",
  "eps": "4872",
  "bps": "46850",
  "asOf": "2026-05-07T14:23:45.123Z",
  "source": "live",
  "stale": false
}
```

**필드 source 매트릭스**:

| 필드 | source | 비고 |
|---|---|---|
| code·name·market·industry | KIS `inquire-price` 또는 instrument 테이블 (PR-9 cold-start) | KIS는 모의 미지원 endpoint 다수 — `inquire-price`만 사용 권장 |
| currentPrice·high·low·open·volume·previousClose | Redis `quote:last:{code}` 1순위 + KIS `inquire-price` fallback | Redis miss 시 KIS REST 1회 호출 |
| change·changeRate | currentPrice - previousClose 즉시 계산 (backend) | |
| marketCap·per·pbr·eps·bps | KIS `inquire-price` (PR-9 fetchPriceMetrics 정합 — kis-api-reference §4) | 시총 단위 = 억원 (KIS 정합) |
| asOf·source·stale | `quote:last` payload (PR-9 정합) | source = "live"·"polling"·"closed" |

**timeout·실패 처리**:
- KIS REST timeout 3s — 실패 시 Redis cache만 응답 (`stale: true`) + meta(per/pbr/시총)는 null로
- Redis miss + KIS 실패 → HTTP 503 + `{"error":"DATA_UNAVAILABLE","message":"종목 데이터를 가져올 수 없습니다"}` (mock 합성 0 — 사용자 메모리 `feedback_no_mock_data.md` 정합)

### 3-3. endpoint 2: `GET /api/symbols/{code}/candles` 응답 스키마

```json
{
  "code": "005930",
  "interval": "1m",
  "candles": [
    {"ts":"2026-05-07T14:23:00Z","open":"60800","high":"60900","low":"60750","close":"60850","volume":1234},
    ...
  ],
  "hasMore": true,
  "nextBefore": "2026-05-07T14:00:00Z"
}
```

**파라미터**:
- `interval`: `1m`·`5m`·`30m`·`1h`·`1d` (5종 — ADR 0019 §2-2 정합 1차 MVP. 2m·15m·90m·1wk·1mo는 후속 정합 — 본 PR-10 비범위)
- `before`: 이전 페이지네이션 cursor — 본 ts 미만 candle만 응답. 미지정 시 가장 최근 candle 응답
- `limit`: default 200, max 500

**source 매트릭스 (interval별)**:

| interval | source | 합류 정책 |
|---|---|---|
| 1m | DB candle (PR-9 영속) + Redis `candle:current:{code}:1m` | 가장 최근 candle = Redis current. closing 후엔 DB가 진실원 |
| 5m·30m·1h | DB candle (PR-9 Yahoo cold-start) | Redis cache 미사용 (1차) |
| 1d | DB candle (PR-9 KIS REST + Yahoo) + Redis `candle:today:{code}:1d` | 당일 row = Redis today. 장 마감 후엔 DailyCloseFinalizer가 DB INSERT (T-MS1 — 1차는 Redis만 또는 미응답) |

**candle 0건 응답**:
- 본 PR-10 cold-start 미완 또는 Yahoo 실패 종목 — `candles: []` + `hasMore: false`
- frontend는 "데이터 수집 중 — 잠시 후 새로고침" 명시

**timeout·실패 처리**:
- DB query timeout 1s — fallback 0 (DB 항상 OK 가정)
- Redis miss → DB만 사용 (1m current 부재)

### 3-4. endpoint 3·4: 호가 slot 라이프사이클 (옵션 — 1차 MVP에 포함)

`POST /api/symbols/{code}/orderbook/subscribe`:
- 응답: `{"subscribed": true, "slot": "live"\|"queued"}` — slot 가득 시 LRU 토글 (ADR 0048 §2-1 — 사용자 페이지 1번에 1개 진입 가정)
- 동작: api → Kafka `symbol.detail.command.v1` publish (또는 직접 consumer SubscriptionRouter REST 호출 — architect 권장 = Kafka publish 일관성)

`POST /api/symbols/{code}/orderbook/unsubscribe`:
- 응답: `{"unsubscribed": true}`

**호가 데이터 전달**:
- KIS WS H0STASP0 → consumer KisWsMessageParser → Kafka `market.orderbook.v1` publish
- api STOMP forwarder → `/topic/market.orderbook.{code}`
- frontend SymbolDetail가 useEffect mount 시 STOMP subscribe + REST subscribe 동시 호출

### 3-5. STOMP topic 매트릭스 (본 PR-10 첫 발행 — ADR 0029 §2-5 정합)

| topic | producer | payload | 용도 |
|---|---|---|---|
| `/topic/market.tick.{code}` | api `MarketTickStompForwarder` (Kafka `market.tick.v1` listener → convertAndSend) | `{code, price, volume, ts, source, stale}` | 현재가 카드 실시간 갱신 (1초 throttle 권장) |
| `/topic/market.candle.minute.{code}` | api `MarketCandleStompForwarder` (Kafka `market.candle.minute.closed.v1` listener) | `{code, interval, ts, OHLCV}` | 1m closing 시 차트 series append |
| `/topic/market.orderbook.{code}` (옵션) | api 동일 패턴 (Kafka `market.orderbook.v1` listener — 신규 topic 본 PR-10) | `{code, ts, bids: [{price, qty}×10], asks: [{price, qty}×10]}` | 호가창 실시간 갱신 |

**1초 throttle**: api 모듈에서 종목별 throttle (in-memory `ConcurrentHashMap<code, lastEmitMs>`) — 사용자 path 부담 차단. 후속 정합은 frontend 단(useEffect debounce)으로 옮길 가능성.

### 3-6. 인증·권한

- 모든 endpoint는 OnboardingGuardFilter 통과 의무 (PR-6 정합) — 셋업 미완 시 401 ONBOARDING_REQUIRED
- 1차 MVP single-tenant — userId 추출은 `system_config` 활성 사용자 1명 (PR-7 정합)
- post-MVP — JWT/세션 기반 multi-tenant

---

## §4. 차트 series 데이터 흐름 LOCK

ADR 0019 + ADR 0029 정합 + 본 spec §3 LOCK.

### 4-1. interval별 source 흐름 (전체)

```
[frontend SymbolDetail mount]
   ↓ useEffect → fetchCandles(code, interval='1d', limit=200)
   ↓
[api GET /api/symbols/005930/candles?interval=1d&limit=200]
   ↓
[CandleQueryService]
   ├─ interval==1d:
   │    ├─ DB SELECT FROM candle WHERE code='005930' AND interval='1d' ORDER BY ts DESC LIMIT 200
   │    ├─ Redis GET candle:today:005930:1d (장중일 때) — 가장 최근 row 합류 (HASH → JSON 변환)
   │    └─ 응답 (DESC → ASC 변환 — LWC가 ASC 기대)
   ├─ interval==1m:
   │    ├─ DB candle interval='1m' LIMIT 200
   │    └─ Redis GET candle:current:005930:1m (장중 마지막 미확정 bar) — append
   ├─ interval==5m·30m·1h:
   │    └─ DB candle만 (Redis 1차 미사용)
   ↓
[frontend SymbolDetail]
   ├─ useEffect: candleSeries.setData(candles)
   ├─ STOMP subscribe /topic/market.candle.minute.005930 (interval==1m일 때)
   └─ STOMP onMessage → candleSeries.update(candle)  (LWC v4 partial update)
```

### 4-2. 페이지네이션 흐름

```
사용자가 차트 좌측 끝 도달 → frontend useScroll → fetchCandles(code, interval, before=earliestTs, limit=200)
   ↓
[api 동일 endpoint — before 파라미터 사용]
   ↓ DB SELECT WHERE ts < before ORDER BY ts DESC LIMIT 200
   ↓
[frontend candleSeries.setData([...newCandles, ...existingCandles])] — prepend
```

**limit·hasMore**:
- limit=200 default, max=500 (response payload 제어)
- hasMore=true이면 더 fetch 가능 (DB row 더 있음)
- 1m candle Yahoo는 7일 한도 → 7일 이전 fetch 시 hasMore=false

### 4-3. 실시간 갱신 (옵션 — STOMP)

**1차 MVP 권장**: STOMP subscribe + 1초 throttle. **OR** polling 5s (frontend `setInterval` 1번 GET `/api/symbols/{code}` — 단순).

**architect 결정**:
- **현재가 카드** = STOMP `/topic/market.tick.{code}` 1순위 + polling 5s fallback (사용자 메모리 `feedback_no_mock_data.md` 정합 — STOMP 끊겨도 polling 동작)
- **차트 1m append** = STOMP `/topic/market.candle.minute.{code}` 1순위 + 매 1분 fetchCandles 재호출 fallback
- **호가창** = STOMP `/topic/market.orderbook.{code}` only (REST polling은 부담 큼)

frontend는 STOMP 끊김 감지 후 폴링 모드 자동 전환. STOMP 클라이언트는 `@stomp/stompjs` (LWC v4 무관 — 일반 WebSocket lib).

### 4-4. LWC v4 적합 series 시그니처

```typescript
const chart = createChart(container, { width, height, layout: ... })
const candleSeries = chart.addCandlestickSeries({ upColor: '#26a69a', downColor: '#ef5350' })

// 초기 fetch
candleSeries.setData(candles.map(c => ({
  time: Math.floor(new Date(c.ts).getTime() / 1000), // UTCTimestamp
  open: parseFloat(c.open),
  high: parseFloat(c.high),
  low: parseFloat(c.low),
  close: parseFloat(c.close),
})))

// STOMP 실시간 갱신
candleSeries.update({
  time: Math.floor(new Date(closedCandle.ts).getTime() / 1000),
  open, high, low, close,
})
```

**volume series 옵션**: `chart.addHistogramSeries({ priceScaleId: '' })` — 거래량 표시 (1차 MVP 포함 권장).

---

## §5. 호가 LRU 10 slot 사용 LOCK (ADR 0048)

본 PR-10이 ADR 0048 §2-1 호가 slot 첫 사용 사이클.

### 5-1. slot 라이프사이클

```
사용자 SymbolDetail 005930 진입
   ↓ frontend useEffect mount
   ↓ POST /api/symbols/005930/orderbook/subscribe
   ↓ api → Kafka publish symbol.detail.command.v1 {action: "ENTER", code: "005930", userId: "..."}
   ↓
[consumer SubscriptionRouter.onSymbolDetailEntered(code)]
   ├─ 호가 LRU 현재 < 10 → 즉시 KisWsPort.subscribeOrderbook([code])
   ├─ 호가 LRU 현재 == 10 → 가장 오래된 unsub + 새 종목 sub
   └─ throttle 200ms — 빠른 전환 시 burst 차단
   ↓ KIS WS H0STASP0 메시지 도착
   ↓ KisWsMessageParser.parseOrderbook → Kafka publish market.orderbook.v1 {code, bids, asks, ts}
   ↓ api MarketOrderbookStompForwarder → /topic/market.orderbook.005930
   ↓ frontend onMessage → setOrderbook(payload)
   ↓ 호가창 표시

사용자 SymbolDetail 떠남 (또는 다른 종목 진입)
   ↓ frontend useEffect cleanup
   ↓ POST /api/symbols/005930/orderbook/unsubscribe
   ↓ api → Kafka publish symbol.detail.command.v1 {action: "EXIT", code: "005930"}
   ↓ consumer SubscriptionRouter.onSymbolDetailExited(005930) → KisWsPort.unsubscribeOrderbook([code])
   ↓ slot 회수 (1초 이내)
```

### 5-2. LRU 매트릭스

| 사용자 행동 | LRU 상태 | 동작 |
|---|---|---|
| 1번째 종목 진입 | 0 → 1 | sub 005930 |
| 10번째 종목 진입 | 9 → 10 | sub 10번째 |
| 11번째 종목 진입 | 10 (full) | LRU evict 1번째 + sub 11번째 |
| 11번째 페이지 unmount | 10 → 9 | unsub 11번째 |
| 빠른 전환 (200ms 안에 5종목) | throttle | 마지막 1종목만 sub (debounce 200ms) |

### 5-3. KIS WS H0STASP0 메시지 형식

PR-9b가 H0STCNT0(체결가)는 이미 파싱. 본 PR-10b는 H0STASP0(호가) 추가:
- KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_orderbook.py` 출처 의무 (KIS-first 정책)
- PIPE + 캐럿 구분자 — sub-agent가 정확 인덱스 확인 의무 (자체 추론 0건)
- 출력 도메인 모델: `OrderbookSnapshot(code, ts, bids: List<PriceLevel>(10), asks: List<PriceLevel>(10))`

### 5-4. frontend 페이지 라이프사이클 vs backend slot 동기화 함정

**함정 1**: 사용자가 SymbolDetail 진입 → 곧바로 [관심종목으로] 클릭 → 200ms 안에 새 종목 진입 시 LRU 토글이 역순으로 동작 가능.
- **처리**: backend SubscriptionRouter에 `pendingSubscriptions` Set + 200ms debounce. frontend는 unmount cleanup에서 `unsubscribe` 호출 의무.

**함정 2**: STOMP 끊김 + 페이지 unmount 시 unsubscribe REST 호출 실패 → backend slot 누수.
- **처리**: backend 5분 idle timeout — 마지막 H0STASP0 메시지 수신 후 5분 무신호 + 마지막 사용자 활동 5분 무신호이면 자동 unsub. **frontend 의존도 낮춤**.

**함정 3**: 사용자가 브라우저 탭 닫기 → 페이지 unmount cleanup 실행 안 될 가능성.
- **처리**: `navigator.sendBeacon('/api/symbols/.../orderbook/unsubscribe')` 시도 + backend 5분 idle timeout fallback.

---

## §6. frontend UI 계약 LOCK

### 6-1. 라우트

- `/symbol/:code` → `SymbolDetailPage` 컴포넌트
- 진입점: `WatchlistRow` onClick → `useNavigate('/symbol/${entry.code}')`
- 이전: `/watchlist`로 [관심종목으로] 버튼

### 6-2. 컴포넌트 계층 (frontend)

```
SymbolDetailPage (page)
├─ SymbolHeader (종목명·코드·시장·산업 + [관심종목으로] 버튼)
├─ SymbolPriceCard (현재가·전일대비·등락률·high·low·open·volume + stale 배지)
├─ SymbolMetaCard (PER·PBR·시총·EPS·BPS)
├─ SymbolChart (LightweightChart wrapper + IntervalToggle + Pagination)
│  ├─ IntervalToggle (1m·5m·30m·1h·1d 5 버튼)
│  ├─ LightweightChart (LWC v4 createChart + addCandlestickSeries + addHistogramSeries)
│  └─ ChartLoadMore (좌측 스크롤 [더 보기] 버튼)
├─ SymbolOrderbook (옵션 — 1차 포함 권장. 매수·매도 10단계 호가표)
└─ SymbolError (data 부재 시 — 종목 정보 없음)
```

### 6-3. TanStack Query hooks (api/hooks/useSymbol.ts 신규)

```typescript
export function useSymbol(code: string) {
  return useQuery({
    queryKey: ['symbol', code],
    queryFn: () => axios.get(`/api/symbols/${code}`).then(r => r.data),
    refetchInterval: 5000, // STOMP 미작동 시 fallback (5s)
    staleTime: 1000,
  })
}

export function useSymbolCandles(code: string, interval: string) {
  return useInfiniteQuery({
    queryKey: ['symbolCandles', code, interval],
    queryFn: ({ pageParam }) => axios.get(
      `/api/symbols/${code}/candles?interval=${interval}&limit=200${pageParam ? `&before=${pageParam}` : ''}`
    ).then(r => r.data),
    initialPageParam: undefined,
    getNextPageParam: (lastPage) => lastPage.hasMore ? lastPage.nextBefore : undefined,
  })
}
```

### 6-4. STOMP client 통합

`frontend/src/lib/stomp.ts` (신규):
- `@stomp/stompjs` 사용 (1차 MVP — 의존 추가 OK. PR-5b는 의존 0)
- `useStompTopic(topic, onMessage)` 커스텀 훅

```typescript
import { Client } from '@stomp/stompjs'

export function useStompTopic<T>(topic: string, onMessage: (payload: T) => void) {
  useEffect(() => {
    const client = new Client({
      brokerURL: 'ws://localhost:8080/ws',
      onConnect: () => {
        client.subscribe(topic, (msg) => onMessage(JSON.parse(msg.body)))
      },
    })
    client.activate()
    return () => { client.deactivate() }
  }, [topic])
}
```

### 6-5. UX 규칙

- **로딩 스피너**: 진입 후 3초 이내 로드 완료 가정. 그 이상이면 "로딩 중..." 명시.
- **데이터 부재**: ADR 0019 §2-3 cold-start 미완 → "데이터 수집 중 — 1분 후 새로고침" + 자동 retry 1분 후
- **stale 배지**: ADR 0014 정합 — `stale: true`면 "장 마감 14:30 기준" 또는 "휴장 중" 노란색 배지
- **error**: HTTP 503·404 → SymbolError 컴포넌트 — "종목 정보를 찾을 수 없습니다" + [관심종목으로] 버튼 표시
- **이전 페이지 복귀**: brower back button + `[관심종목으로]` 버튼 양쪽 지원

### 6-6. 모바일 반응형 (1차 MVP — desktop 우선)

- desktop: 좌측 차트 70% + 우측 메타 카드·호가 30%
- 모바일: stacked layout (차트 → 메타 → 호가) — 1차 OK 정도. 본격 정합은 T-LWC 또는 후속.

---

## §7. 종목 메타 fetch LOCK

ADR `feedback_kis_first_policy.md` 정합 — KIS endpoint·tr_id 자체 추론 0건. 모든 KIS endpoint는 KIS open-trading-api 또는 `kis-code-assistant` MCP 출처.

### 7-1. KIS endpoint 매트릭스 (본 PR-10에서 사용)

| 용도 | endpoint | TR_ID | 모의 지원 | PR-9 가동 중? |
|---|---|---|---|---|
| 현재가·메타·PER·PBR·시총 | `/uapi/domestic-stock/v1/quotations/inquire-price` | `FHKST01010100` | ✅ | ✅ (PR-9b `KisRestPriceAdapter` 가동 중) |
| 호가 (REST 옵션) | `/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn` | `FHKST01010200` | ✅ | ❌ (본 PR-10 신규) |
| 호가 (WebSocket) | WS `H0STASP0` | — | ✅ (실시간) | ❌ (본 PR-10 신규) |
| 종목 기본정보 (이름·산업) | `/uapi/domestic-stock/v1/quotations/search-stock-info` | `CTPF1002R` | ❌ | ❌ (본 PR-10 사용 보류 — 모의 미지원이라 instrument 테이블 또는 inquire-price industry 필드만) |

### 7-2. 종목 메타 source 우선순위

```
1순위: instrument 테이블 (PR-9 cold-start 시 INSERT — 단 본 PR-10 시점에 instrument INSERT가 cold-start에 포함 안 됐을 가능성 — T-IM1 본격은 후속)
2순위: KIS inquire-price 응답 (industry·종목명 일부 포함)
3순위: 종목명 부재 시 code 그대로 표시 ("005930")
```

**1차 MVP 결정**: `inquire-price` 응답 필드만으로 SymbolDetail 표시. 종목명·산업이 부재면 graceful (표시 0). T-IM1 진입 시 instrument 테이블 본격 사용 (backlog).

### 7-3. KIS-first 의무 강조 (sub-agent 명시)

market-data sub-agent에 본 spec §7 + dispatch §3-2 §4-1 LOCK으로 명시:
- `inquire-asking-price-exp-ccn`(REST 옵션) 또는 `H0STASP0`(WS) 메시지 형식·필드 인덱스는 **KIS open-trading-api repo 출처 의무**
- 자체 추론 0건. 분량 부담 시 `kis-code-assistant` MCP `search_domestic_stock_api(query="호가")` 호출

### 7-4. fetchPriceMetrics 호출 주기

- 사용자 SymbolDetail 진입 시 1회 호출 + 5분 cache (Redis `meta:{code}` TTL 5분)
- PER/PBR는 분당 변동 거의 없음 — 5분 cache 충분
- 현재가는 quote:last에서 분리해 표시 (PR-9 가동 중 — TTL 7일이지만 매 tick 갱신)

---

## §8. 비범위 (T-SB·T-O·T-PAT·T-IND·T-LWC·T-MS1·T-CA1로 미루기)

본 PR-10은 **단순 표시만**. 다음은 본격 trade·분석·최적화 단계로 미룸:

| 비범위 항목 | 미루기 트랙 | 사유 |
|---|---|---|
| 패턴 마커 (Doji·Hammer 등 차트 overlay) | T-PAT·T-LWC | 57개 패턴 인식 엔진 + LWC overlay layer 본격 구현 |
| 지표 overlay (MA·EMA·RSI 등) | T-IND·T-LWC | 80지표 + IndicatorSource port 도입 후 |
| 매수·매도 버튼·주문 폼 | T-O | KIS 주문 endpoint + Risk Guard chain 6단 후 |
| 시그널 표시 (BUY/SELL/HOLD) | T-SIG | DSL 평가 엔진 후 |
| 백테스트 결과 표시 | T-BT | DSL + 백테스트 엔진 후 |
| AI 분석 코멘트 | T-AI (W4) | AI 옵션 console |
| 종목 비교 overlay (multi-symbol) | T-LWC | 자체 overlay layer |
| 다중 timeframe 동기 차트 | T-LWC | overlay layer |
| Replay (시점 거슬러 시그널 재생) | T-LWC | overlay layer |
| 종목 검색·자동완성 | T-IM1 | instrument 테이블 본격 + 검색 인덱스 |
| 외국인·기관 동향 | T-IND·후속 | `inquire-investor` endpoint |
| 재무 시계열 차트 (DART) | post-MVP | DART 외부 어댑터 |
| 다중 사용자 권한 | post-MVP | JWT/세션 |
| 모바일 native 앱 | post-MVP | RN 또는 Flutter |
| KRX 영업일 캘린더 동기화 (장 마감 정확 처리) | T-MS1 | 정적 캘린더 시드만 1차 |
| DailyCloseFinalizer 16:00 KST 일봉 확정 | T-MS1 | Redis today 1차만 |
| corp action adjusted 컬럼 차트 토글 | T-CA1 | raw vs adjusted 토글 |
| 시간외 단일가 stream | post-MVP | KIS H0STOAA0 비범위 |

---

## §9. 트레이드오프 표

| 결정 | 대안 | 채택 사유 |
|---|---|---|
| REST `/api/symbols/{code}/candles` separate from `/api/symbols/{code}` | 단일 endpoint에 candles 포함 | 페이지네이션·인터벌 토글이 별도 fetch 단위 — 분리가 자연. 단일 endpoint는 payload 폭증 |
| LWC v4 + `@stomp/stompjs` | `socket.io`, `react-stomp` | LWC v4는 ADR 0025 LOCK. STOMP 클라이언트는 `@stomp/stompjs` 표준(Spring 호환) — `react-stomp`는 stale (2년 미관리) |
| STOMP 1순위 + REST polling 5s fallback | STOMP only OR polling only | 사용자 메모리 `feedback_no_mock_data.md` 정합 — STOMP 끊겨도 polling으로 graceful. 본격 production은 STOMP 안정성 검증 후 |
| 호가 slot 라이프사이클 = 명시적 REST endpoint | STOMP subscribe 라이프사이클로 자동 인지 | 명료성·debugging 용이성. 후속 정합 가능 |
| 메타 cache 5분 (Redis) | DB 영속 OR Redis TTL 1시간 | PER/PBR 갱신 주기는 일 단위인데 5분이면 KIS Rate Limit 충분 + 사용자 진입마다 fresh OK |
| 차트 인터벌 5종 (1m·5m·30m·1h·1d) | 7종(2m·15m·90m 추가) OR 11종(1wk·1mo·1y) | 1차 MVP는 사용자 자주 보는 5종 + Yahoo 매트릭스 전체 지원 (ADR 0019 §2-2 정합 — 후속) |
| 호가창 1차 포함 | T-O 시점에 합류 | 사용자 dogfooding에 호가창은 필수. KIS WS H0STASP0 LRU 10은 ADR 0048 §2-1 명시 — 본 PR-10이 첫 사용 |
| api → consumer 통신 = Kafka publish | 직접 REST 호출 | Kafka는 일관성 + 재처리 가능. 단 latency ~50ms 추가 (사용자 path 1회만이라 OK) |

---

## §10. 발견된 함정 + 위험

### §10-1. 사용자 첫 dogfooding 직전 — 첫 사용 시 깨질 위험 ★ (사용자 메모리 정합)

본 PR-10 머지 후 **사용자 첫 dogfooding** (사용자 메모리 `project_track1_dogfooding_paused.md`). 다음 시나리오에서 깨질 가능성:

1. **사용자가 watchlist 등록 → 곧바로 SymbolDetail 진입**: PR-9 cold-start 미완 (Yahoo 7회 fetch 평균 5~10초) → candle 0건 응답 가능. **처리**: frontend graceful "데이터 수집 중 — 잠시 후 새로고침" 명시 + 자동 1분 후 재fetch.
2. **장 마감 후 (Closed) dogfooding**: KIS WS unsubscribe + 시세 무흐름 + REST polling이 last-day-close 반환. **처리**: ADR 0014 T3는 T-MS1 비범위 → 본 PR-10은 마지막 quote:last 그대로 표시 + stale 배지. T-MS1 진입 시 보강.
3. **호가 LRU 토글 함정**: 사용자가 페이지 빠르게 5번 전환 시 backend slot 폭증 risk. **처리**: §5-2 throttle 200ms LOCK + 5분 idle timeout fallback.
4. **모의 키로 dogfooding 시 일부 endpoint 미지원**: `inquire-price`는 모의 지원이지만 `search-stock-info`는 미지원. **처리**: 본 PR-10은 `inquire-price`만 사용 권장 + 종목명 부재면 code 그대로.
5. **STOMP 끊김 시 사용자 인지 부재**: 차트가 "정지"로 보일 가능성. **처리**: frontend 5s polling fallback + STOMP reconnect 자동.
6. **사용자 KIS approval_key 발급 실패**: PR-9b `KisApprovalAdapter`가 가동 중이지만 첫 사이클이라 unstable. **처리**: 본 PR-10이 새 approval_key 발급 발생시키지 않음 (PR-9 가동 중인 것 재사용). 단 부팅 직후 진입 시 approval_key 캐시 미발급 상태일 가능성 → "잠시 후" 또는 retry 1회.

**기본 권장**: 사용자 dogfooding 전 영업일 (월요일~금요일 09:00~15:30) 진입 + watchlist 1+ 종목 등록 후 30초 대기 → 진입 권장. 시간 외/주말 dogfooding은 stale 배지 검증.

### §10-2. consumer 모듈 영역 침범 함정 (PR-9 결함 정합 — backlog 5번)

PR-9에서 consumer 모듈에 backend-core·market-data 양쪽이 add (ConsumerCandleJpa·KisRestPriceAdapter 등) → add-add 충돌 4건 발생. **본 PR-10에서 영역 분배 사전 LOCK 의무**:
- consumer 모듈 신규 add 파일은 **dispatch §4-1 영역 분배 LOCK 명세에서 정확 sub-agent 책임 명시**
- `_workspace/specs/pr-10-conflict-matrix.md` §3에서 양 sub-agent 사전 비교 (add-add 가능성 0% 검증)

### §10-3. ADR 0050 슬롯 채번룰 보강 — hunk-based merge 한계

PR-9 첫 운영 결과: CHANGELOG hunk-based merge 충돌 1건 발생. 본 PR-10에서 보강 적용:
- 슬롯 row 사이에 anchor line(`<!-- slot-divider:pr-N -->`) 또는 빈 줄 삽입 시도
- 단 CHANGELOG.md는 markdown table이라 빈 줄·주석은 table 깨짐 risk → **architect 결정**: HTML 주석은 markdown table 행 사이에 삽입 가능 (markdown 파서가 무시). 본 PR-10에서 시도.
- 또는 sub-agent 정확 line number 명시 (in-place edit이라 같은 line만 수정 → git 충돌 0)

자세한 적용은 §D + dispatch-spec §5에 명시.

### §10-4. STOMP 클라이언트 의존 추가 — frontend bundle size

`@stomp/stompjs` 추가 시 bundle size ~10KB 증가. **처리**: tree-shake + lazy load 가능. 1차 OK 판단.

### §10-5. api 모듈 STOMP broker config 신규

PR-5a Application stub에 `WebSocketConfig` 부재 가능성. **처리**: 본 PR-10b가 추가 — `@EnableWebSocketMessageBroker` + `/topic` prefix + simple broker (1차 MVP, post-MVP relay broker 후속).

### §10-6. CandleQueryService 합류 로직 — DB ASC vs DESC 혼선

DB candle row는 INSERT 순(보통 ts ASC) — SELECT는 ts DESC LIMIT 200 권장 (최근 우선). LWC v4는 series.setData() ASC 기대 → backend가 응답 시 ASC 정렬 반환 의무. **처리**: API 응답 spec §3-3 정합 — `candles` 배열은 ASC.

### §10-7. 호가 endpoint api 모듈 신규 의존 — Kafka publish vs REST → consumer

`POST /api/symbols/.../orderbook/subscribe` 핸들러가 consumer로 명령 전달:
- 옵션 A: Kafka publish `symbol.detail.command.v1` → consumer listener (일관성)
- 옵션 B: REST → consumer HTTP endpoint (latency 낮지만 모듈 결합)
- **architect 결정**: 옵션 A (Kafka) — magicJar 일관성 + 재처리 가능. dispatch에 LOCK.

### §10-8. 사용자 dogfooding 결과 backlog 항목 발생 가능성 ★

본 PR-10 머지 후 사용자가 화면에서 발견한 결함은:
- 단순 결함 → hotfix PR (architect 또는 frontend 단독)
- 정책 변경 → backlog 항목 추가 + 차후 사이클 처리

**권장 절차**: 사용자 dogfooding 후 24시간 이내 결함 list 정리 → backlog.md 갱신 + orchestrator 다음 사이클 첫 의제로 점검.

### §10-9. KIS open-trading-api H0STASP0 메시지 인덱스 자체 추론 risk

PR-9b가 H0STCNT0 인덱스 KIS-first 정합으로 처리. 본 PR-10b도 H0STASP0 인덱스 KIS-first 의무 — sub-agent 자체 추론 시 데이터 깨짐.

**처리**: dispatch §3-2 §7-3에 KIS open-trading-api `examples_llm/.../websocket_orderbook.py` 출처 + `kis-code-assistant` MCP 사용 의무 명시.

### §10-10. instrument 테이블 부재 시 종목명 graceful

PR-9 cold-start은 instrument INSERT 0 (T-IM1 본격 비범위). 본 PR-10 SymbolDetail 진입 시 `inquire-price` industry 필드 + 종목명 사용. **함정**: `inquire-price`도 종목명을 직접 반환 안 할 가능성 (TR 응답 필드 검증 의무 — sub-agent KIS-first).

**처리**: dispatch §7-2에 `inquire-price` 응답 필드 정합 검증 의무. 종목명 부재면 code 그대로 표시 graceful.

---

## §11. 테스트 시나리오 골격 (R3 일괄)

본 PR-10이 W1 마지막 트랙 — 룰 4 정합으로 R3 일괄 테스트 진행. sub-agent 본인 PR엔 smoke 1~2건만, 통합 테스트는 사용자 dogfooding으로 대체.

### 11-1. backend smoke (PR-10b 부담)

1. `consumer/src/test/kotlin/.../OrderbookSubscriptionRouterTest.kt` — 호가 LRU 10 토글 단위 테스트 (1번째 sub·11번째 evict·페이지 unmount unsub)
2. `api/src/test/kotlin/.../SymbolControllerTest.kt` — `GET /api/symbols/{code}` mock Redis quote:last 응답 + 메타 카드 매핑 검증

### 11-2. frontend smoke (PR-10a 부담)

1. `frontend/src/pages/Symbol/__tests__/SymbolDetailPage.test.tsx` — vitest mock useSymbol → 카드 렌더링 검증

### 11-3. 통합 시나리오 (사용자 dogfooding 수동 검증)

DoD 1~16 모두 — `scripts/build-and-deploy.sh` 후 사용자가 본인 KIS 키로 본인 watchlist 진입.

### 11-4. R3 일괄 테스트는 별도 묶음 — 본 PR-10 머지 후

후속 사이클(별도 PR — `chore(test): W1 R3 일괄 테스트`)에서 본격 통합 테스트 발행. 1차는 dogfooding + smoke만.

---

## §12. 사용자 첫 dogfooding 안내 ★

### 12-1. 진입 흐름

1. **PR-10a + PR-10b 모두 머지 후** main 위
2. `cd /Users/ak-song/magic_ch1`
3. `scripts/build-and-deploy.sh` (전체 통합 기동)
4. http://localhost:3000 진입 (frontend)
5. 셋업 마법사 미완 시 7-step 완료 (KIS 키 입력)
6. `/watchlist` 진입 → 종목 등록 (예: 005930 — 삼성전자)
7. 등록 후 30초 대기 (Yahoo cold-start 7회 fetch + KIS WS 구독)
8. row 클릭 → `/symbol/005930` 이동
9. 화면에서 **확인 의무 항목**:

### 12-2. 사용자 검증 체크리스트

| # | 검증 항목 | 통과 기준 |
|---|---|---|
| 1 | 종목 헤더 | "삼성전자 (005930) · KOSPI · 전기전자" 표시 (또는 industry 부재 graceful) |
| 2 | 현재가 카드 | 60,900 (예시) + 전일대비 +400 (+0.66%) — 장중 또는 stale 배지 |
| 3 | 메타 카드 | PER 12.5 · PBR 1.3 · 시총 4,800,000억원 · EPS 4,872 · BPS 46,850 |
| 4 | LWC 차트 1d 표시 | 100+ row candle (1년 이상) — 좌측 [더 보기] 클릭 시 확장 |
| 5 | 인터벌 토글 | 1m·5m·30m·1h·1d 5 버튼 클릭 → 차트 series 즉시 갱신 |
| 6 | 1m 인터벌 | Yahoo cold-start 7일분 + 당일 진행 중 마지막 bar (Redis current) 합류 |
| 7 | 호가창 (장중) | 매수·매도 10단계 호가표 표시 + 실시간 변동 |
| 8 | 다른 종목 진입 | 11번째 종목 진입 시 1번째 종목 호가 unsub log 확인 (`docker logs magicjar-consumer-app | grep "orderbook unsubscribe"`) |
| 9 | [관심종목으로] 버튼 | `/watchlist` 복귀 + 직전 row 위치 표시 |
| 10 | 잘못된 code 진입 | `/symbol/999999` URL 직접 접근 → "종목 정보를 찾을 수 없습니다" + [관심종목으로] 버튼 |
| 11 | LLM 호출 0 | 화면에 AI 코멘트 0건 (W4 비범위) |
| 12 | 차트·시세 갱신 (장중) | 1분 후 차트 1m bar 추가 + 현재가 카드 변동 |

### 12-3. 사용자 검증 후 결함 처리 절차

본 PR-10 머지 후 사용자 dogfooding에서 발견한 결함:

1. **현장에서 즉시 정정 가능 (1줄·1파일)**: hotfix PR (frontend 또는 architect 단독)
2. **정책 변경 또는 다중 모듈**: backlog.md에 항목 추가 + 다음 사이클 architect 디스패치
3. **블로킹 결함 (사용 불가)**: 사용자 → 오케스트라 호출 → architect 즉시 진입 → 새 PR 사이클

자세한 절차는 backlog.md §변경 이력에 사용자 결정 후 갱신.

### 12-4. 다음 사이클 진입 가이드 (사용자 결정 — backlog 정합)

본 PR-10 머지 + dogfooding 통과 후:
- **W1 종료** — T-W1·T-W2·T-S1 모두 완료
- **W2 진입 후보**: T-IND(80지표) + T-PAT(57패턴) 병렬 또는 T-MS1(영업일 캘린더·last-day-close fallback) 우선 (W1 보강)
- **사용자 결정**: backlog.md §1·§2 의제 + dogfooding 결함 + W2 첫 트랙 결정

---

## §13. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-10 T-S1 설계 가이드 v1 발행 (architect, opus 4.7 1M). REST 3 endpoint LOCK·차트 series source LOCK·호가 LRU 10 라이프사이클 LOCK·frontend UI 계약 LOCK·KIS-first 의무. 사용자 첫 dogfooding 직전 사이클. ADR 0050 슬롯 채번룰 보강 적용 (hunk 분리 시도 — HTML 주석 anchor). consumer 영역 침범 함정 정정(dispatch §4-1 명시 의무). |
