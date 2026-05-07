# PR-10 sub-agent 분배 명세

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-10-s1-design.md` v1 + ADR 0019·0029·0048·0040·0025·0014·0028·0050
- 본 문서: orchestrator가 frontend·market-data 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-10-conflict-matrix.md` 참조.
- **본 PR-10은 ADR 0050 (CHANGELOG 슬롯 채번룰) 두 번째 적용 사이클** — PR-9 첫 운영 결과 보강 (hunk 분리 시도 + 영역 분배 강화 — §5 §4-1 참조).

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-10a** | frontend | M~L | `agent/frontend/pr-10-s1-frontend` | **병렬 1** |
| **PR-10b** | market-data | M~L | `agent/market-data/pr-10-s1-market-data` | **병렬 1** |

**디스패치 모드**: **병렬** (충돌 행렬 정합 — `_workspace/specs/pr-10-conflict-matrix.md` §8 결론).

**인터페이스 동결 시점**: 본 spec(`pr-10-s1-design.md` + 본 dispatch + 충돌 행렬 + backlog.md)이 머지되는 즉시 — frontend·market-data 양쪽이 §3~§7(설계 spec) + 본 dispatch §3 (REST/STOMP/Kafka/Redis 시그니처) + §4 (인터페이스) + §5 (CHANGELOG 슬롯) 보고 동시 시작 가능.

**최고 관리자 메모리 정합 — `feedback_orchestrator_active_parallel.md`**: architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능. 본 PR-10은 충돌 행렬에서 modify-shared 2건(consumer SubscriptionRouter — market-data 단독 격리, CHANGELOG — ADR 0050 in-place edit)만 식별 → **완전 병렬 권장**.

---

## §2. frontend 위임 (PR-10a)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | frontend (sonnet) |
| 브랜치 | `agent/frontend/pr-10-s1-frontend` |
| base | `main` (PR-9 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+frontend@magicjar.local` |
| 작업 author name | `magicjar-frontend` |
| Task 파일 goal | `T-S1 frontend — SymbolDetailPage(/symbol/:code 라우트) + SymbolHeader + SymbolPriceCard + SymbolMetaCard + SymbolChart(LWC v4 + IntervalToggle 5종 + Pagination) + SymbolOrderbook(옵션 — 1차 포함) + SymbolError + WatchlistRow onClick 1줄 정정 + STOMP 클라이언트(@stomp/stompjs) + TanStack Query 2 hooks(useSymbol·useSymbolCandles) + smoke 1건` |
| Task 파일 deliverables | `frontend/src/pages/Symbol/SymbolDetailPage.tsx + SymbolHeader.tsx + SymbolPriceCard.tsx + SymbolMetaCard.tsx + SymbolChart.tsx + IntervalToggle.tsx + LightweightChart.tsx + SymbolOrderbook.tsx + SymbolError.tsx, frontend/src/api/symbol.ts + hooks/useSymbol.ts + hooks/useSymbolCandles.ts, frontend/src/lib/stomp.ts(@stomp/stompjs wrapper + useStompTopic), frontend/src/App.tsx 라우트 추가, frontend/src/pages/Watchlist/WatchlistRow.tsx onClick 정정, package.json 의존 추가(@stomp/stompjs), smoke test 1건` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | M~L |

### 2-2. 작업 범위 (Done 정의)

다음 모두 완료해야 PR-10a Done:

#### A. 라우트 추가 (App.tsx 정정)

- [ ] `frontend/src/App.tsx` — `/symbol/:code` Route 추가, OnboardingGuard 안에 배치
- [ ] AppHeader top nav에 "차트" 또는 "종목" 메뉴 추가는 본 PR-10a 비범위 (Watchlist에서 클릭으로만 진입)

#### B. SymbolDetailPage 신규 (page)

- [ ] `frontend/src/pages/Symbol/SymbolDetailPage.tsx` — 메인 페이지
  - `useParams<{ code: string }>()` 추출
  - `useSymbol(code)` hook 호출 → 메타·현재가
  - `useSymbolCandles(code, interval)` hook 호출 → 차트 series
  - `useStompTopic('/topic/market.tick.${code}', ...)` 실시간 가격 push 옵션
  - `useStompTopic('/topic/market.candle.minute.${code}', ...)` 1m closing append 옵션
  - `useStompTopic('/topic/market.orderbook.${code}', ...)` 호가 실시간 옵션
  - useEffect mount 시 `POST /api/symbols/${code}/orderbook/subscribe` (호가창 표시 시)
  - useEffect cleanup 시 `POST /api/symbols/${code}/orderbook/unsubscribe` + `navigator.sendBeacon` 시도
  - data 부재 시 SymbolError 컴포넌트 표시
  - [관심종목으로] 버튼 → `useNavigate('/watchlist')`

#### C. 하위 컴포넌트 신규 8건

- [ ] `frontend/src/pages/Symbol/SymbolHeader.tsx` — 종목명·코드·시장·산업 헤더
- [ ] `frontend/src/pages/Symbol/SymbolPriceCard.tsx` — 현재가·전일대비·등락률·high·low·open·volume + stale 배지
- [ ] `frontend/src/pages/Symbol/SymbolMetaCard.tsx` — PER·PBR·시총·EPS·BPS
- [ ] `frontend/src/pages/Symbol/SymbolChart.tsx` — IntervalToggle + LightweightChart wrapper
- [ ] `frontend/src/pages/Symbol/IntervalToggle.tsx` — 1m·5m·30m·1h·1d 5 버튼 (active state 표시)
- [ ] `frontend/src/pages/Symbol/LightweightChart.tsx` — LWC v4 createChart + addCandlestickSeries + addHistogramSeries
  - LWC v4 공식 API 한정 (ADR 0025 정합)
  - props: `candles: CandleDto[]`, `onScrollLeft?: () => void` (페이지네이션 트리거)
- [ ] `frontend/src/pages/Symbol/SymbolOrderbook.tsx` — 매수·매도 10단계 호가표 (장중 표시)
- [ ] `frontend/src/pages/Symbol/SymbolError.tsx` — "종목 정보를 찾을 수 없습니다" + [관심종목으로]

#### D. API 클라이언트·hooks 신규

- [ ] `frontend/src/api/symbol.ts` — DTO 정의 + axios 호출
  ```typescript
  export interface SymbolDto {
    code: string; name?: string; market?: string; industry?: string;
    currentPrice: string; previousClose: string; change: string; changeRate: string;
    high: string; low: string; open: string; volume: number;
    marketCap?: string; per?: string; pbr?: string; eps?: string; bps?: string;
    asOf: string; source: 'live' | 'polling' | 'closed'; stale: boolean;
  }
  export interface CandleDto {
    ts: string; open: string; high: string; low: string; close: string; volume: number;
  }
  export interface CandlesResponseDto {
    code: string; interval: string; candles: CandleDto[]; hasMore: boolean; nextBefore?: string;
  }
  ```

- [ ] `frontend/src/api/hooks/useSymbol.ts` — `useQuery` 5s refetchInterval (STOMP fallback)
- [ ] `frontend/src/api/hooks/useSymbolCandles.ts` — `useInfiniteQuery` interval 키 + before cursor

#### E. STOMP 클라이언트 통합

- [ ] `frontend/package.json` — `@stomp/stompjs` 의존 추가 (latest 버전)
- [ ] `frontend/src/lib/stomp.ts` — Client 싱글톤 + `useStompTopic<T>(topic, onMessage)` 커스텀 훅
  - brokerURL: `ws://localhost:8080/ws` (dev 환경 — env 변수 보강은 backlog 후보)
  - reconnectDelay: 5000ms
  - onConnect 시 subscribe, cleanup 시 unsubscribe
  - 끊김 감지 + frontend useEffect onError handler — UI에 STOMP 끊김 배지 (옵션)

#### F. WatchlistRow onClick 정정 (1줄 변경)

- [ ] `frontend/src/pages/Watchlist/WatchlistRow.tsx` — row div onClick 추가:
  ```tsx
  const navigate = useNavigate()
  // ... onClick={() => navigate(`/symbol/${entry.code}`)} on the row container
  ```
  - 휴지통 버튼 onClick은 stopPropagation 의무 (row 클릭과 분리)

#### G. 검증 게이트 통과
spec §10 함정 모두 인지 + DoD 1·2·3·4·5·6·11·12·13·15 (frontend 영역) 통과.

#### H. smoke test 1건 (룰 4 정합)

- [ ] `frontend/src/pages/Symbol/__tests__/SymbolDetailPage.test.tsx` — vitest mock useSymbol + useSymbolCandles → 헤더·메타카드·차트 렌더링 검증

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(frontend): @stomp/stompjs 의존 + lib/stomp.ts 클라이언트` | `package.json` + `frontend/src/lib/stomp.ts` |
| 2 | `feat(frontend): symbol API 클라이언트 + DTO + 2 hooks` | `frontend/src/api/symbol.ts` + `hooks/useSymbol.ts` + `hooks/useSymbolCandles.ts` |
| 3 | `feat(frontend): LightweightChart wrapper + IntervalToggle (LWC v4 ADR 0025)` | `frontend/src/pages/Symbol/{LightweightChart,IntervalToggle}.tsx` |
| 4 | `feat(frontend): SymbolHeader + SymbolPriceCard + SymbolMetaCard` | `frontend/src/pages/Symbol/{SymbolHeader,SymbolPriceCard,SymbolMetaCard}.tsx` |
| 5 | `feat(frontend): SymbolChart + SymbolOrderbook + SymbolError` | `frontend/src/pages/Symbol/{SymbolChart,SymbolOrderbook,SymbolError}.tsx` |
| 6 | `feat(frontend): SymbolDetailPage(/symbol/:code) + App.tsx 라우트` | `frontend/src/pages/Symbol/SymbolDetailPage.tsx` + `App.tsx` |
| 7 | `feat(frontend): WatchlistRow onClick navigate(/symbol/:code) (ADR 0040 진입점)` | `frontend/src/pages/Watchlist/WatchlistRow.tsx` |
| 8 | `test(frontend): SymbolDetailPage smoke 1건` | test 파일 |
| 9 | `chore(changelog): PR-10a frontend slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 2-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
frontend — Symbol Detail UI (T-S1 — 사용자 첫 dogfooding 직전)

## Summary
- PR-10a: T-S1 frontend — SymbolDetailPage(/symbol/:code) + SymbolHeader + SymbolPriceCard + SymbolMetaCard + SymbolChart(LWC v4 + IntervalToggle 1m·5m·30m·1h·1d 5종) + SymbolOrderbook(매수·매도 10단계) + SymbolError + LightweightChart(addCandlestickSeries + addHistogramSeries) + WatchlistRow onClick navigate (ADR 0040 진입점) + STOMP 클라이언트(@stomp/stompjs + useStompTopic 커스텀훅) + TanStack Query useSymbol·useSymbolCandles 2 hooks + smoke 1건.
- ADR 0019·0029·0040·0025·0014 정합. backend는 PR-10b market-data가 동시 진행 (REST 3 endpoint + STOMP forwarder + 호가 LRU 10).
- 정합: `_workspace/specs/pr-10-s1-design.md` + `pr-10-dispatch-spec.md` + `pr-10-conflict-matrix.md`.

## 신규 ADR
없음 — 본 PR-10a 자체.

## Scope
- frontend: 핵심 영역 — pages/Symbol/ 9 컴포넌트 + api/symbol.ts + hooks 2 + lib/stomp.ts + App.tsx 라우트 + WatchlistRow.tsx 1줄 정정
- backend: 변경 0
- domain: 변경 0
- batch: 변경 0
- consumer: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (W1 마지막 트랙이라 사용자 dogfooding 우선 + 별도 묶음). 본 PR에 smoke 1건만 (SymbolDetailPage 렌더링).

## 사용자 검증 방법
1. PR-10 spec PR 머지 + main 위
2. PR-10b market-data 머지 (REST endpoint + STOMP)
3. `scripts/build-and-deploy.sh`
4. http://localhost:3000 진입 + 셋업 완료 + watchlist 등록
5. 30초 대기 (cold-start) → row 클릭 → /symbol/:code 진입
6. spec §12-2 사용자 dogfooding 체크리스트 12 항목 확인

## 정합 ADR
- 0019 — 캔들 저장 정책 (LWC series source)
- 0029 — 당일 진행 중 캔들 Redis (1m current·1d today)
- 0040 — 관심종목 우선 UI 흐름 (Watchlist row → SymbolDetail 진입점)
- 0025 — LWC v4 공식 API 한정 (TradingView Advanced 폐기)
- 0014 — 시세 데이터 Fallback (stale 배지)
- 0050 — CHANGELOG 슬롯 채번룰 두 번째 적용 (in-place edit)
```

### 2-5. frontend 의존성·정합 의무

- **ADR 0025** — LWC v4 공식 API 한정. `addCandlestickSeries·addHistogramSeries·setData·update` 4개 API 한정. TradingView Advanced 라이브러리 import 0건 의무
- **ADR 0019** — 인터벌 5종(1m·5m·30m·1h·1d) — 7종/11종 풀 매트릭스는 본 PR 비범위 (T-LWC 또는 후속)
- **ADR 0040** — Watchlist row 클릭 → SymbolDetail 진입점 1줄 정정
- **ADR 0050** — CHANGELOG slot row in-place edit
- **룰 5 (AI 호출 자제)**: spring-ai/anthropic/openai SDK import 0건. frontend AI 라이브러리도 0건
- **룰 4 (테스트 마지막 일괄)**: smoke 1건만

**KIS-first 의무**: frontend는 KIS endpoint 직접 호출 0건. 모두 backend api 통과 — KIS endpoint·tr_id는 backend(market-data) 책임. frontend는 backend 응답 schema만 의존.

**LWC v4 검증**: package.json에 `lightweight-charts: ^4.2.0` 이미 존재 (PR-5b 정합). 본 PR-10a는 의존 추가 없이 사용.

---

## §3. market-data 위임 (PR-10b)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | market-data (sonnet) |
| 브랜치 | `agent/market-data/pr-10-s1-market-data` |
| base | `main` (PR-9 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+market-data@magicjar.local` |
| 작업 author name | `magicjar-market-data` |
| Task 파일 goal | `T-S1 market-data — REST 3 endpoint(GET /api/symbols/:code + GET /api/symbols/:code/candles + POST /api/symbols/:code/orderbook/subscribe·unsubscribe) + STOMP forwarder(market.tick·candle.minute·orderbook 3 topic) + WebSocketConfig + KIS WS H0STASP0 호가 stream 추가 + KisWsOrderbookParser + OrderbookSubscriptionRouter(LRU 10 + 200ms throttle + 5분 idle timeout) + symbol.detail.command.v1 Kafka listener + market.orderbook.v1 Kafka topic + CandleQueryService(DB candle + Redis current·today 합류) + SymbolMetaService(KIS inquire-price + Redis 5분 cache) + smoke 2건` |
| Task 파일 deliverables | `api/.../controller/SymbolController.kt + service/{SymbolMetaService,CandleQueryService}.kt + config/WebSocketConfig.kt + messaging/MarketTickStompForwarder.kt + MarketCandleStompForwarder.kt + MarketOrderbookStompForwarder.kt, consumer/.../ws/{KisWsOrderbookParser,KisWebSocketBridge 보강}.kt + ingestion/OrderbookSubscriptionRouter.kt + kafka/SymbolDetailCommandKafkaListener.kt + config/KafkaTopicConfig.kt 보강(market.orderbook.v1·symbol.detail.command.v1), domain/.../market/OrderbookSnapshot.kt + port/{KisWsOrderbookPort?,...}.kt 보강, smoke test 2건` |
| 머지 정책 | rebase |
| 분량 | M~L |

### 3-2. 작업 범위 (Done 정의)

#### A. REST endpoint 3건 (api 모듈)

- [ ] `api/.../controller/SymbolController.kt` — 신규 컨트롤러
  - `GET /api/symbols/{code}` — `SymbolMetaService.getSymbol(code)` 호출 → SymbolDto 응답
  - `GET /api/symbols/{code}/candles?interval=&before=&limit=` — `CandleQueryService.getCandles(...)` 호출 → CandlesResponseDto
  - `POST /api/symbols/{code}/orderbook/subscribe` — Kafka publish `symbol.detail.command.v1` `{action: "ENTER", code, userId}`
  - `POST /api/symbols/{code}/orderbook/unsubscribe` — Kafka publish `{action: "EXIT", code, userId}`
  - OnboardingGuardFilter 통과 의무 (PR-6 정합 — 자동)
  - Swagger 어노테이션 (사용자 메모리 `feedback_swagger_pattern.md` 정합 — 4단 어노테이션 + 한국어 + example)

- [ ] `api/.../service/SymbolMetaService.kt` — 메타 + 현재가 조립
  - input: code
  - 1순위: Redis `quote:last:{code}` (PR-9 가동 중)
  - 2순위: KIS `inquire-price` (PR-9b `KisRestPriceAdapter` 재사용 — port를 통해)
  - PER/PBR/시총: KIS `inquire-price` 응답 필드 (kis-api-reference §4 정합 — `KisInstrumentInfoAdapter.fetchPriceMetrics` 패턴)
  - 5분 cache (Redis `meta:{code}` TTL 300s)
  - timeout 3s
  - 실패 시 cache만 응답 + `stale: true` (mock 합성 0)

- [ ] `api/.../service/CandleQueryService.kt` — 차트 series 조립
  - input: code, interval, before(옵션), limit
  - DB SELECT FROM candle WHERE code AND interval AND ts < before ORDER BY ts DESC LIMIT limit
  - interval==1m: Redis `candle:current:{code}:1m` HGETALL → 가장 최근 candle 합류 (장중일 때)
  - interval==1d: Redis `candle:today:{code}:1d` HGETALL → 가장 최근 candle 합류
  - interval==5m·30m·1h: DB only
  - 응답: ts ASC 정렬 (LWC v4 기대) — DB는 DESC fetch 후 reverse
  - hasMore = 응답 row count == limit
  - nextBefore = 응답 가장 오래된 candle.ts

#### B. STOMP broker config + 3 forwarder (api 모듈)

- [ ] `api/.../config/WebSocketConfig.kt` — 신규
  ```kotlin
  @Configuration
  @EnableWebSocketMessageBroker
  class WebSocketConfig : WebSocketMessageBrokerConfigurer {
      override fun configureMessageBroker(registry: MessageBrokerRegistry) {
          registry.enableSimpleBroker("/topic")
          registry.setApplicationDestinationPrefixes("/app")
      }
      override fun registerStompEndpoints(registry: StompEndpointRegistry) {
          registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
      }
  }
  ```

- [ ] `api/.../messaging/MarketTickStompForwarder.kt` — Kafka `market.tick.v1` listener → STOMP `/topic/market.tick.{code}` (1초 throttle ConcurrentHashMap)
- [ ] `api/.../messaging/MarketCandleStompForwarder.kt` — Kafka `market.candle.minute.closed.v1` listener → STOMP `/topic/market.candle.minute.{code}`
- [ ] `api/.../messaging/MarketOrderbookStompForwarder.kt` — Kafka `market.orderbook.v1` listener → STOMP `/topic/market.orderbook.{code}` (200ms throttle)

#### C. KIS WS H0STASP0 호가 추가 (consumer 모듈)

- [ ] `consumer/.../ws/KisWsOrderbookParser.kt` — H0STASP0 PIPE 메시지 파싱
  - 입력: `0|H0STASP0|001|005930^...`
  - 출력: `OrderbookSnapshot(code, ts, bids: List<PriceLevel>(10), asks: List<PriceLevel>(10))`
  - **KIS open-trading-api 출처 의무** — `examples_llm/domestic-stock/.../websocket_orderbook.py` 정확 인덱스 (sub-agent KIS-first)
  - `kis-code-assistant` MCP 검증 의무 — `mcp__kis-code-assistant__search_domestic_stock_api(query="호가 H0STASP0")`

- [ ] `consumer/.../ws/KisWebSocketBridge.kt` 보강 — 신규 메서드:
  - `subscribeOrderbook(codes: List<String>)` — H0STASP0 tr_type=1 메시지
  - `unsubscribeOrderbook(codes: List<String>)` — tr_type=2
  - onMessage 분기 — H0STCNT0 vs H0STASP0 구분
  - **modify-shared 위험 1**: PR-9b가 만든 KisWebSocketBridge에 메서드 추가 — backend-core 영역 미진입이므로 충돌 0 (market-data 단독 변경)

#### D. OrderbookSubscriptionRouter (LRU 10 + throttle)

- [ ] `consumer/.../ingestion/OrderbookSubscriptionRouter.kt` — 신규
  - `LinkedHashMap<String, Long>` (LRU 10 — 마지막 access 시각 기록)
  - public API: `onSymbolDetailEntered(code, userId)`·`onSymbolDetailExited(code, userId)`·`current(): Set<String>`
  - 진입 시: LRU < 10 → 즉시 sub / LRU == 10 → eldest evict + new sub
  - 200ms debounce (코드별 또는 글로벌 — sub-agent 결정. architect 권장 = 코드별)
  - 5분 idle timeout (`@Scheduled(fixedRate = 60000)` — lastAccess 5분 초과 시 자동 unsub)
  - thread safety: `ReentrantLock` 또는 `synchronized`
  - **architect 결정**: PR-9b SubscriptionRouter는 체결가 30 slot. 본 PR-10b OrderbookSubscriptionRouter는 호가 10 slot 별도 클래스 (책임 분리). 또는 SubscriptionRouter 보강 — sub-agent 결정 (단 본 PR-10b 단독 변경이라 충돌 0)

#### E. symbol.detail.command.v1 Kafka listener

- [ ] `consumer/.../kafka/SymbolDetailCommandKafkaListener.kt` — 신규
  - `@KafkaListener(topics = ["symbol.detail.command.v1"], groupId = "magicjar-consumer-symbol-detail")`
  - manual ack
  - payload `{action: "ENTER" | "EXIT", code: String, userId: String}`
  - ENTER → `OrderbookSubscriptionRouter.onSymbolDetailEntered`
  - EXIT → `OrderbookSubscriptionRouter.onSymbolDetailExited`

#### F. Kafka topic 보강 (consumer 모듈)

- [ ] `consumer/.../config/KafkaTopicConfig.kt` 보강 — 신규 topic 2건:
  - `market.orderbook.v1` (1 partition, retention 1h — orderbook은 저장 가치 낮음)
  - `symbol.detail.command.v1` (1 partition, retention 1h)
  - **modify-shared 위험 2**: PR-9b가 만든 KafkaTopicConfig에 추가 — market-data 단독 변경이라 충돌 0

#### G. 도메인 모델 신규

- [ ] `domain/.../market/OrderbookSnapshot.kt` — add-only
  ```kotlin
  package kr.spdaewoon.magicjar.domain.market
  
  import kotlinx.datetime.Instant
  import java.math.BigDecimal
  
  data class PriceLevel(val price: BigDecimal, val quantity: Long)
  
  data class OrderbookSnapshot(
      val code: String,
      val ts: Instant,
      val bids: List<PriceLevel>, // 10단계
      val asks: List<PriceLevel>, // 10단계
  )
  ```

- [ ] `domain/.../market/port/KisRestPriceMetricsPort.kt` (또는 PR-9b의 `KisRestPort`에 메서드 추가 검토) — PER/PBR/시총 fetch port
  - **modify-shared 위험 3**: PR-9b의 `KisRestPort.fetchPrice` 그대로 유지하고 `fetchPriceMetrics` 메서드 추가 OR 별도 port 신규
  - **architect 결정**: 별도 port 신규 권장 (책임 분리 — 가격 vs 메타) → `KisRestPriceMetricsPort.fetchPriceMetrics(code): PriceMetrics` add-only. 충돌 0
  - PR-9b의 KisRestPriceAdapter 클래스에 `KisRestPriceMetricsPort` 추가 구현 — 어댑터 1개에 port 2개 implement 패턴

- [ ] `domain/.../market/PriceMetrics.kt` — add-only
  ```kotlin
  data class PriceMetrics(
      val per: BigDecimal?,
      val pbr: BigDecimal?,
      val eps: BigDecimal?,
      val bps: BigDecimal?,
      val marketCap: BigDecimal?, // 단위: 억원
      val industry: String?,
  )
  ```

#### H. 검증 게이트 통과
spec §10 함정 모두 인지 + DoD 1·2·3·6·7·8·9·10·15·16 (market-data 영역) 통과.

#### I. smoke test 2건 (룰 4 정합)

- [ ] `consumer/src/test/kotlin/.../OrderbookSubscriptionRouterTest.kt` — LRU 10 토글 + 200ms throttle + 5분 idle timeout 단위 테스트
- [ ] `api/src/test/kotlin/.../SymbolControllerTest.kt` — `GET /api/symbols/{code}` mock service 응답 + DTO 매핑 검증

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): OrderbookSnapshot + PriceLevel + PriceMetrics + KisRestPriceMetricsPort (T-S1)` | `domain/.../market/{OrderbookSnapshot,PriceMetrics,port/KisRestPriceMetricsPort}.kt` |
| 2 | `feat(api): WebSocketConfig (STOMP simple broker /topic prefix)` | `api/.../config/WebSocketConfig.kt` |
| 3 | `feat(api): SymbolController 4 endpoint (GET symbol·candles + orderbook subscribe·unsubscribe)` | `api/.../controller/SymbolController.kt` |
| 4 | `feat(api): SymbolMetaService + CandleQueryService (Redis 합류 + 5분 cache)` | `api/.../service/{SymbolMetaService,CandleQueryService}.kt` |
| 5 | `feat(api): 3 STOMP forwarder (market.tick·candle.minute·orderbook → /topic/...)` | `api/.../messaging/{MarketTickStompForwarder,MarketCandleStompForwarder,MarketOrderbookStompForwarder}.kt` |
| 6 | `feat(consumer): KisWsOrderbookParser (H0STASP0 PIPE 파싱 — KIS-first)` | `consumer/.../ws/KisWsOrderbookParser.kt` |
| 7 | `feat(consumer): KisWebSocketBridge subscribeOrderbook·unsubscribeOrderbook 보강 (H0STASP0)` | `consumer/.../ws/KisWebSocketBridge.kt` |
| 8 | `feat(consumer): OrderbookSubscriptionRouter (LRU 10 + 200ms throttle + 5분 idle timeout)` | `consumer/.../ingestion/OrderbookSubscriptionRouter.kt` |
| 9 | `feat(consumer): SymbolDetailCommandKafkaListener + KafkaTopicConfig 보강(market.orderbook·symbol.detail.command)` | `consumer/.../kafka/SymbolDetailCommandKafkaListener.kt` + `config/KafkaTopicConfig.kt` |
| 10 | `feat(consumer): KisRestPriceAdapter PriceMetrics 어댑터 보강 (PER/PBR/시총)` | `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` (PR-9b 만든 어댑터에 메서드 추가) |
| 11 | `test(api+consumer): SymbolController + OrderbookSubscriptionRouter smoke 2건` | test 파일 |
| 12 | `chore(changelog): PR-10b market-data slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (api·consumer·domain) — Symbol Detail REST + STOMP + 호가 LRU (T-S1)

## Summary
- PR-10b: T-S1 market-data — SymbolController 4 endpoint(GET symbol·candles + orderbook subscribe·unsubscribe) + SymbolMetaService(KIS inquire-price + Redis 5분 cache) + CandleQueryService(DB candle + Redis current·today 합류) + WebSocketConfig(STOMP simple broker /topic) + 3 forwarder(market.tick·candle.minute·orderbook) + KisWsOrderbookParser(H0STASP0 PIPE 파싱 — KIS-first) + KisWebSocketBridge subscribeOrderbook 보강 + OrderbookSubscriptionRouter(LRU 10 + 200ms throttle + 5분 idle timeout) + SymbolDetailCommandKafkaListener(symbol.detail.command.v1) + KafkaTopicConfig 보강(market.orderbook·symbol.detail.command) + 도메인(OrderbookSnapshot·PriceMetrics·KisRestPriceMetricsPort) + smoke 2건.
- ADR 0019·0029·0048·0014 정합. frontend는 PR-10a가 동시 진행 (SymbolDetailPage + LWC v4 + STOMP 클라이언트).
- 정합: `_workspace/specs/pr-10-s1-design.md` + `pr-10-dispatch-spec.md` + `pr-10-conflict-matrix.md`.

## 신규 ADR
없음 — 본 PR-10b 자체.

## Scope
- domain: OrderbookSnapshot + PriceMetrics + KisRestPriceMetricsPort (add-only)
- api: SymbolController + 2 service + WebSocketConfig + 3 STOMP forwarder
- consumer: KisWsOrderbookParser + OrderbookSubscriptionRouter + SymbolDetailCommandKafkaListener + KisWebSocketBridge·KafkaTopicConfig·KisRestPriceAdapter 보강
- batch: 변경 0
- frontend: 변경 0

## 사용자 검증 방법
1. PR-10 spec PR 머지 + main 위
2. PR-10a frontend 머지 (SymbolDetailPage)
3. `scripts/build-and-deploy.sh`
4. http://localhost:3000 진입 + watchlist 등록 + row 클릭
5. `curl http://localhost:8080/api/symbols/005930` → SymbolDto 응답
6. `curl 'http://localhost:8080/api/symbols/005930/candles?interval=1d&limit=200'` → CandlesResponseDto
7. `docker logs magicjar-consumer-app | grep "orderbook subscribe"` → H0STASP0 메시지 송신 log (장중)
8. STOMP `ws://localhost:8080/ws` 클라이언트 → /topic/market.tick.005930 subscribe → 메시지 수신

## 정합 ADR
- 0019 — 캔들 저장 정책 (CandleQueryService source)
- 0029 — 당일 진행 중 캔들 Redis (1m current·1d today STOMP fanout 첫 발행)
- 0048 — KIS WS 41건 + LRU 10 호가 (본 PR이 H0STASP0 첫 사용)
- 0014 — 시세 데이터 Fallback (stale 배지)
- 0050 — CHANGELOG 슬롯 채번룰 두 번째 적용
```

### 3-5. market-data 의존성·정합 의무

- **ADR 0048 §2-1** — 호가 LRU 10 slot LOCK + 200ms throttle LOCK + 5분 idle timeout 의무
- **ADR 0029 §2-5** — STOMP topic 매트릭스 (`/topic/market.tick.{code}`·`/topic/market.candle.minute.{code}`·`/topic/market.orderbook.{code}` 3개)
- **ADR 0019** — candle interval 5종 1차 (1m·5m·30m·1h·1d)
- **ADR 0050** — CHANGELOG slot row in-place edit
- **룰 5 (AI 호출 자제)**: spring-ai/anthropic/openai import 0건
- **룰 4 (테스트 마지막 일괄)**: smoke 2건만

**KIS-first 의무 (강조 ★)**: 
- H0STASP0 호가 PIPE 메시지 인덱스·필드는 **KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_orderbook.py`** 정확 출처 사용 의무. 자체 추론 0건
- `mcp__kis-code-assistant__search_domestic_stock_api(query="호가 H0STASP0")` 호출 의무 (검증)
- `inquire-price` PER/PBR/시총 응답 필드는 `kis-api-reference.md` §4 + `KisInstrumentInfoAdapter.fetchPriceMetrics` 기존 패턴 정합
- `feedback_kis_first_policy.md` 정합 — KIS MCP 적극 활용 + open-trading-api 레포 베이스

---

## §4. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 4-1. 영역 분배 LOCK ★ (PR-9 add-add 충돌 결함 정정 — backlog §1·§5 정합)

PR-9 운영 결과 dispatch §4-1 영역 분배 모호 → consumer 모듈에 양 sub-agent add-add 충돌 4건. **본 PR-10에서 영역 분배 사전 명시 의무**:

| 영역·디렉토리 | PR-10a (frontend) | PR-10b (market-data) | 충돌 risk |
|---|---|---|---|
| `frontend/src/pages/Symbol/` | **신규 9 파일 (단독 책임)** | 변경 0 | **0%** |
| `frontend/src/pages/Watchlist/WatchlistRow.tsx` | **modify (1줄 onClick)** | 변경 0 | **0%** |
| `frontend/src/api/symbol.ts` + hooks 2 | **신규** | 변경 0 | **0%** |
| `frontend/src/lib/stomp.ts` | **신규** | 변경 0 | **0%** |
| `frontend/src/App.tsx` | **modify (라우트 추가)** | 변경 0 | **0%** |
| `frontend/package.json` | **modify (@stomp/stompjs 추가)** | 변경 0 | **0%** |
| `frontend/src/pages/**` (그 외) | 변경 0 | 변경 0 | 격리 |
| `api/.../controller/` | 변경 0 | **신규 SymbolController.kt** | **0%** |
| `api/.../service/` | 변경 0 | **신규 SymbolMetaService·CandleQueryService 2건** | **0%** |
| `api/.../config/WebSocketConfig.kt` | 변경 0 | **신규** | **0%** |
| `api/.../messaging/` | 변경 0 | **신규 3 forwarder** | **0%** |
| `consumer/.../ws/` | 변경 0 | **modify (KisWebSocketBridge 보강) + 신규 (KisWsOrderbookParser)** | **0%** (PR-9b가 만든 파일 modify는 market-data 본인 영역) |
| `consumer/.../ingestion/` | 변경 0 | **신규 (OrderbookSubscriptionRouter)** | **0%** (PR-9 SubscriptionRouter는 별도 클래스 — 본 PR은 별도 신규) |
| `consumer/.../kafka/` | 변경 0 | **신규 (SymbolDetailCommandKafkaListener)** | **0%** |
| `consumer/.../config/KafkaTopicConfig.kt` | 변경 0 | **modify (market.orderbook·symbol.detail.command 2 topic 추가)** | **0%** (market-data 본인 영역 — PR-9b가 만든 파일 보강) |
| `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` | 변경 0 | **modify (KisRestPriceMetricsPort 추가 implement)** | **0%** (market-data 본인 영역 — PR-9b가 만든 어댑터 보강) |
| `domain/.../market/` | 변경 0 | **신규 (OrderbookSnapshot·PriceMetrics) + 신규 port (KisRestPriceMetricsPort)** | **0%** |
| `consumer/src/main/resources/application.yml` | 변경 0 | (보강 0 권고) | **0%** — Kafka topic 자동 생성은 KafkaTopicConfig.kt가 처리. STOMP는 api 모듈만 |
| `api/src/main/resources/application.yml` | 변경 0 | (필요 시 STOMP·Kafka consumer 설정) | **단독 격리** — market-data 단독 변경. PR-9 application.yml은 consumer 모듈, 본 PR-10은 api 모듈이라 다른 파일 |
| `CHANGELOG.md` | **modify (slot row in-place edit)** | **modify (slot row in-place edit)** | **ADR 0050 in-place edit — 충돌 0 보장** |

**양 sub-agent 사전 비교 → add-add 가능성 0% 검증**:
- ✅ frontend는 frontend 디렉토리만 (api/consumer/domain 0)
- ✅ market-data는 api/consumer/domain만 (frontend 0)
- ✅ CHANGELOG는 ADR 0050 슬롯 채번룰 in-place edit (다른 line 수정)
- ✅ application.yml은 다른 모듈 (api vs consumer) — 같은 파일 수정 0
- ✅ consumer 모듈 PR-9b 만든 파일 보강(KisWebSocketBridge·KafkaTopicConfig·KisRestPriceAdapter)은 market-data 본인 영역 — frontend 영역 침범 0

**결론**: PR-9 add-add 충돌 결함은 본 PR-10에서 **0건 발생 가능 (사전 영역 분배 LOCK)**.

### 4-2. domain port 시그니처 (LOCK)

| port | 시그니처 | 위치 | 누가 만드는가 |
|---|---|---|---|
| `KisWsPort` (PR-5a + PR-9b 보강) | `subscribe·unsubscribe·subscribedCodes` (체결가) + **신규** `subscribeOrderbook·unsubscribeOrderbook` | domain | **PR-10b 단독** (market-data 본인 영역 — PR-9b가 만든 port 보강) |
| `KisRestPriceMetricsPort` 신규 | `fetchPriceMetrics(code): PriceMetrics` | domain | **PR-10b 단독** add-only |
| `OrderbookSnapshot` 신규 | data class | domain | **PR-10b 단독** add-only |
| `PriceMetrics` 신규 | data class | domain | **PR-10b 단독** add-only |
| `PriceLevel` 신규 | data class | domain | **PR-10b 단독** add-only |

**frontend는 domain port 의존 0** — backend api 응답 schema(SymbolDto·CandlesResponseDto·OrderbookDto)만 의존.

### 4-3. REST endpoint 시그니처 (LOCK — design.md §3 정합)

| Method | path | 응답 | 누가 구현 |
|---|---|---|---|
| GET | `/api/symbols/{code}` | SymbolDto | **PR-10b api 단독** |
| GET | `/api/symbols/{code}/candles` | CandlesResponseDto | **PR-10b api 단독** |
| POST | `/api/symbols/{code}/orderbook/subscribe` | `{subscribed, slot}` | **PR-10b api 단독** |
| POST | `/api/symbols/{code}/orderbook/unsubscribe` | `{unsubscribed}` | **PR-10b api 단독** |

**frontend (PR-10a)는 endpoint client만 작성** — `frontend/src/api/symbol.ts` axios 호출.

### 4-4. STOMP topic 시그니처 (LOCK — design.md §3-5 정합)

| topic | producer | consumer | payload | 누가 구현 |
|---|---|---|---|---|
| `/topic/market.tick.{code}` | api MarketTickStompForwarder (Kafka listener) | frontend useStompTopic | `{code, price, volume, ts, source, stale}` | **PR-10b api 단독** producer / **PR-10a frontend 단독** consumer |
| `/topic/market.candle.minute.{code}` | api MarketCandleStompForwarder | frontend useStompTopic | `{code, interval, ts, OHLCV}` | 동일 |
| `/topic/market.orderbook.{code}` | api MarketOrderbookStompForwarder (Kafka `market.orderbook.v1` listener) | frontend useStompTopic | `{code, ts, bids, asks}` | 동일 |

### 4-5. Kafka topic 시그니처 (LOCK)

| topic | producer | consumer | payload | 누가 추가 |
|---|---|---|---|---|
| `market.tick.v1` | consumer KisWebSocketBridge·WatchlistPollingWorker (PR-9 가동 중) | api MarketTickStompForwarder (본 PR-10b 신규) | `{code, price, volume, ts, source, stale}` | api consumer 신규 (market-data) |
| `market.candle.minute.closed.v1` | consumer TickToMinuteAggregator (PR-9 가동 중) | api MarketCandleStompForwarder (본 PR-10b 신규) | `{code, interval, ts, OHLCV}` | api consumer 신규 (market-data) |
| `market.orderbook.v1` | consumer KisWebSocketBridge.onOrderbookMessage (본 PR-10b 신규) | api MarketOrderbookStompForwarder | `{code, ts, bids, asks}` | **신규 양쪽 (market-data 단독)** — KafkaTopicConfig 보강 |
| `symbol.detail.command.v1` | api SymbolController (본 PR-10b 신규) | consumer SymbolDetailCommandKafkaListener (본 PR-10b 신규) | `{action, code, userId}` | **신규 양쪽 (market-data 단독)** |

### 4-6. Redis 키 매트릭스 (LOCK)

| 키 | TTL | 작성자 | 읽기 | 형식 |
|---|---|---|---|---|
| `quote:last:{code}` (PR-9 정합) | 7일 | consumer (PR-9) | api SymbolMetaService | JSON |
| `candle:current:{code}:1m` (PR-9 정합) | 1m closing 시 evict | consumer (PR-9) | api CandleQueryService | HASH |
| `candle:today:{code}:1d` (PR-9 정합) | 장 마감 + 30분 | consumer (PR-9) | api CandleQueryService | HASH |
| **`meta:{code}` (신규)** | 5분 | api SymbolMetaService | api SymbolMetaService | JSON `{per, pbr, eps, bps, marketCap, industry}` |

### 4-7. 예시 페이로드

#### REST GET /api/symbols/005930
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
  "high": "61000", "low": "60750", "open": "60800",
  "volume": 12345678,
  "marketCap": "4800000",
  "per": "12.5", "pbr": "1.3", "eps": "4872", "bps": "46850",
  "asOf": "2026-05-07T14:23:45.123Z",
  "source": "live", "stale": false
}
```

#### REST GET /api/symbols/005930/candles?interval=1d&limit=200
```json
{
  "code": "005930",
  "interval": "1d",
  "candles": [
    {"ts":"2025-05-07T15:30:00Z","open":"61000","high":"61500","low":"60800","close":"61200","volume":12345678},
    {"ts":"2025-05-08T15:30:00Z","open":"61200","high":"61800","low":"61000","close":"61500","volume":13456789}
  ],
  "hasMore": true,
  "nextBefore": "2025-05-07T15:30:00Z"
}
```

#### POST /api/symbols/005930/orderbook/subscribe
요청: `(빈 body, userId는 OnboardingGuard에서 세션 추출)`
응답: `{"subscribed": true, "slot": "live"}`

#### Kafka symbol.detail.command.v1
```json
{"action":"ENTER","code":"005930","userId":"USER_ID","ts":"2026-05-07T14:23:45.123Z"}
```

#### STOMP /topic/market.orderbook.005930
```json
{"code":"005930","ts":"2026-05-07T14:23:45.123Z","bids":[{"price":"60900","quantity":1234},...10단계],"asks":[{"price":"60950","quantity":2345},...10단계]}
```

#### KIS WS H0STASP0 PIPE (sub-agent KIS-first 출처 의무)
```
0|H0STASP0|001|005930^090012^60900^...
```
구체 인덱스는 KIS open-trading-api `examples_llm/.../websocket_orderbook.py` 출처.

### 4-8. SoT 변경 시 처리

본 spec(`pr-10-s1-design.md` + 본 dispatch + `pr-10-conflict-matrix.md` + backlog.md)이 머지된 후 양쪽 sub-agent 진행 중에 시그니처 변경 필요 시:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-10 작업 중엔 spec 변경 0건 의무 (사용자 본인 결정 외).

---

## §5. CHANGELOG 슬롯 채번룰 두 번째 적용 (ADR 0050 — PR-9 보강)

본 spec PR이 ADR 0050 두 번째 적용. **PR-9 첫 운영에서 발견한 hunk-based merge 충돌 1건 회피를 위해 hunk 분리 시도** (architect 자율 결정).

### 5-1. 슬롯 row 정확 위치

`CHANGELOG.md` 현재 구조 (PR-9 머지 후):
- Line 1: `# magicJar CHANGELOG`
- Line 2: (빈 줄)
- Line 3: `| 날짜 | 변경 내용 | 대상 | 사유 |`
- Line 4: `|------|----------|------|------|`
- Line 5: PR-9 spec row (architect)
- Line 6: PR-9a backend row
- Line 7: PR-9b market-data row
- ...

본 spec PR이 prepend할 위치 = **Line 5 (현재 첫 데이터 행) 위 — 새 Line 5·6·7·8 발행** + 각 row 사이에 HTML 주석 anchor line 삽입 시도 (hunk 분리).

### 5-2. 슬롯 row 형식 (3건 발행 + anchor line 시도)

본 spec PR diff에서 prepend되는 정확 형식:

```markdown
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
<!-- pr-10-slot-architect -->
| 2026-05-07 | **docs(specs): PR-10 T-S1 설계 + sub-agent 분배 + 충돌 행렬 + backlog md 신설(5건) + CHANGELOG 슬롯 두 번째 적용** — `_workspace/specs/pr-10-s1-design.md` (REST 3 endpoint LOCK·차트 series source LOCK·호가 LRU 10 라이프사이클 LOCK·frontend UI 계약 LOCK·KIS-first 의무·사용자 첫 dogfooding 직전), `pr-10-dispatch-spec.md` (PR-10a frontend·PR-10b market-data 분배 + 영역 분배 LOCK 강화 — add-add 0% 검증), `pr-10-conflict-matrix.md` (**완전 병렬 권장** — modify-shared 1건 CHANGELOG는 ADR 0050 in-place edit + hunk 분리 anchor 시도, application.yml은 모듈 분리). `_workspace/backlog.md` 신설 (5건 의제 흡수 — ADR 0050 보강·KisRestPort 분리·CLAUDE.md ADR 인덱스 갱신·worktree fallback 정리·backend-core 영역 침범 패턴). (architect) | docs/backlog | PR-10 T-S1 설계 산출물 + backlog md 신설 |
<!-- pr-10-slot-frontend -->
| 2026-05-07 | **TBD: PR-10a T-S1 frontend** (작성: frontend) — TBD | frontend | TBD |
<!-- pr-10-slot-market-data -->
| 2026-05-07 | **TBD: PR-10b T-S1 market-data** (작성: market-data) — TBD | api/consumer/domain | TBD |
<!-- pr-10-slot-end -->
```

**hunk 분리 메커니즘**:
- 각 row 위에 `<!-- pr-10-slot-{agent} -->` HTML 주석 anchor line 삽입
- markdown 파서는 HTML 주석 무시 → 표 깨짐 0
- git hunk-based merge는 anchor line + row가 1 hunk로 묶임 → sub-agent가 본인 anchor + row만 modify
- backend-core가 architect anchor + row 미수정 + market-data anchor + row 미수정 보장
- **단 architect 결정**: HTML 주석이 표에 어떻게 보일지 검증 필요. 시각적으로는 빈 줄로 보일 가능성. 검증은 본 PR diff에서 직접 확인.

**fallback 옵션 (주석이 작동 안 하면)**:
- sub-agent 정확 line number 명시 (in-place edit이라 같은 line만 수정 → git 충돌 0)
- 또는 row 사이에 빈 줄 삽입 (markdown table은 빈 줄 만나면 표 종료 → 단일 표 분할 = 표시 깨짐 — 이 옵션은 부적합)

**architect 결정**: HTML 주석 anchor 우선 시도. 실패 시 fallback = sub-agent 정확 line number 명시.

### 5-3. sub-agent in-place edit 의무

PR-10a frontend (sub-agent):
1. main rebase (architect spec PR 머지 후)
2. `CHANGELOG.md` 열어 `<!-- pr-10-slot-frontend -->` anchor + 그 다음 row 1줄 검색
3. **그 row 1줄만** in-place edit — `TBD: PR-10a T-S1 frontend (작성: frontend) — TBD` → 실제 산출물 한 줄 사유로 교체:
   ```markdown
   | 2026-05-07 | **feat(frontend): PR-10a T-S1 SymbolDetailPage** — SymbolDetailPage(/symbol/:code) + 9 컴포넌트(Header·PriceCard·MetaCard·Chart·IntervalToggle·LightweightChart·Orderbook·Error) + WatchlistRow onClick navigate (ADR 0040 진입점 1줄 정정) + STOMP 클라이언트(@stomp/stompjs + useStompTopic 커스텀훅) + TanStack Query useSymbol·useSymbolCandles 2 hooks + smoke 1건. ADR 0019·0029·0040·0025 정합. (frontend) | frontend | PR-10a T-S1 종목 상세 화면 |
   ```
4. **다른 row(architect spec row·anchor line·market-data slot row) 절대 미수정** — 영역 침범 금지
5. anchor line `<!-- pr-10-slot-frontend -->` 자체는 미수정 (그 다음 row만 수정)
6. commit + push + PR + 머지

PR-10b market-data (sub-agent):
1. main rebase
2. `<!-- pr-10-slot-market-data -->` anchor 검색
3. `TBD: PR-10b T-S1 market-data (작성: market-data) — TBD` → 실제 산출물 사유로 교체:
   ```markdown
   | 2026-05-07 | **feat(api+consumer): PR-10b T-S1 backend** — SymbolController 4 endpoint(GET symbol·candles + orderbook subscribe·unsubscribe) + SymbolMetaService(KIS inquire-price + Redis 5분 cache) + CandleQueryService(DB candle + Redis current·today 합류) + WebSocketConfig(STOMP simple broker) + 3 forwarder + KisWsOrderbookParser(H0STASP0 PIPE — KIS-first) + KisWebSocketBridge.subscribeOrderbook 보강 + OrderbookSubscriptionRouter(LRU 10 + 200ms throttle + 5분 idle timeout) + SymbolDetailCommandKafkaListener + KafkaTopicConfig 보강(market.orderbook·symbol.detail.command) + 도메인(OrderbookSnapshot·PriceMetrics·KisRestPriceMetricsPort) + smoke 2건. ADR 0019·0029·0048·0014 정합. (market-data) | api/consumer/domain | PR-10b T-S1 종목 상세 backend |
   ```
4. **다른 row 절대 미수정**
5. commit + push + PR + 머지

### 5-4. 일탈·fallback 처리 (ADR 0050 §2-4 정합)

sub-agent가 spec PR 슬롯 row 외 추가 변경 발생 시:
- 별도 정정 commit으로 본인 row 갱신 OR 새 row append (add-only) — 본인 책임
- add-only 충돌 발생 가능 — 둘째 머지자가 자체 rebase 해결

병렬 sub-agent 머지 순서 (PR-9 정합):
- frontend 먼저 머지 → frontend row 채워짐 → market-data slot row 그대로 + market-data fillout 후 머지 → CHANGELOG 충돌 0 (anchor line + ADR 0050 §3-2 정합)
- 동시 push 시점이라도 in-place edit이라 git이 다른 line 변경으로 인식 → 충돌 0

### 5-5. orchestrator 운영 절차

orchestrator가 frontend·market-data 디스패치 시 다음 정확 위치 정보 전달:

> `CHANGELOG.md`에 `<!-- pr-10-slot-frontend -->` anchor 다음 row 또는 `<!-- pr-10-slot-market-data -->` anchor 다음 row 1줄이 `TBD ... (작성: <agent>)` 형식으로 있다. 본인 row만 in-place edit. 형식 예시 = dispatch-spec §5-3.

### 5-6. 검증 의무 (사용자가 처음 관찰)

본 PR-10이 ADR 0050 두 번째 적용 + hunk 분리 anchor 첫 시도. 사용자 검증 항목:
- spec PR diff에 anchor line 3건 추가됨 확인
- sub-agent PR diff에 anchor line 미수정 + 본인 row 1줄만 수정 확인
- 두 sub-agent 머지 시 CHANGELOG conflict 0건 확인 (PR-9 hunk 충돌 회피 검증)

검증 실패 시 ADR 0050 본문 정정 (별도 미뤄둠 — backlog §1).

---

## §6. 운영·실패 대응

### 6-1. frontend가 PR-10a 분량 초과 인지 시

PR-10a를 다음 단위로 분할 가능 (orchestrator 사전 동의 필요):
- PR-10a-1: 라우트 + SymbolDetailPage + 헤더·메타카드 (no chart)
- PR-10a-2: SymbolChart + LightweightChart + IntervalToggle + Pagination
- PR-10a-3: SymbolOrderbook + STOMP 클라이언트

market-data PR-10b는 영역 분리라 PR-10a 분할과 무관하게 진행 가능.

### 6-2. market-data가 KIS WS H0STASP0 정확 메시지 형식 불확실 시

- `kis-code-assistant` MCP로 `mcp__kis-code-assistant__search_domestic_stock_api(query="호가 H0STASP0")` 의무
- KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_orderbook.py` 실 코드 참조
- architect 호출 → spec §5-3 정밀화 (필드 인덱스 명시)
- 자체 추론 0건 — KIS-first 정책

### 6-3. CI fail 시

frontend·market-data 본인 영역 fail → 본인 PR에서 수정.

### 6-4. ADR 정합 누락 발견 시

직접 ADR 변경 금지 — architect 호출 → 별도 PR로 정정.

### 6-5. STOMP 끊김 운영 결함 발견 시 (사용자 dogfooding 결과)

- 단순 (ws endpoint 보강) → frontend 또는 market-data hotfix
- 정책 변경 (relay broker 도입) → backlog 항목 + 차후 사이클

### 6-6. 호가 LRU 10 동작 불량 발견 시

- 단순 (debounce 조정) → market-data hotfix
- 정책 변경 (slot 예산 재배분) → ADR 0048 정정 + architect 디스패치

### 6-7. 사용자 dogfooding 결함 발견 시 ★

본 PR-10 머지 후 사용자가 화면에서 발견한 결함 처리:
1. **현장에서 즉시 정정 가능**: hotfix PR (frontend 또는 architect 단독)
2. **정책 변경 또는 다중 모듈**: backlog.md에 항목 추가 + 다음 사이클 architect 디스패치
3. **블로킹 결함**: 사용자 → 오케스트라 호출 → architect 즉시 진입 → 새 PR 사이클

자세한 절차는 backlog.md §변경 이력에 사용자 결정 후 갱신.

### 6-8. PR-10b가 PR-10a 미머지 시 진행 가능?

가능 — frontend·api·consumer·domain 영역 완전 분리. domain port·DTO·STOMP topic 모두 add-only. PR-10a (frontend)는 backend가 응답을 잘 주는지 확인 후 머지가 안전 → **권장 머지 순서**: PR-10b 먼저 머지 → PR-10a rebase 후 머지.

### 6-9. PR-10a가 PR-10b 미머지 시 진행 가능?

가능 — frontend는 mock 응답으로 자체 검증 가능 (smoke test도 mock useSymbol). 단 통합 검증은 PR-10b 머지 후.

---

## §7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-10 dispatch-spec v1 발행. PR-10a frontend·PR-10b market-data 병렬 분배 LOCK + 영역 분배 LOCK 강화 (PR-9 add-add 충돌 결함 정정 — add-add 0% 검증) + ADR 0050 슬롯 채번룰 두 번째 적용(hunk 분리 HTML 주석 anchor 시도) + KIS-first 의무 강조 (H0STASP0 KIS open-trading-api 출처 + kis-code-assistant MCP 사용 의무). (architect) |
