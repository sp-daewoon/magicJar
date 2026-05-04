# T2 R2-A — KIS 실시간 시세 WebSocket 본격 구현 (체결가 + 호가 + 지수 + Fallback)

- **작성일**: 2026-05-04
- **작성자**: architect (opus 4.7 1M)
- **상태**: DRAFT
- **선행 plan**: `docs/superpowers/plans/2026-05-02-track2-market-session-and-data-mgmt.md` (R1 EGW hotfix까지 완료, R2 미진행)
- **선행 spec**: `docs/superpowers/specs/2026-05-02-track2-market-session-and-data-mgmt-design.md` (§4.6 TickEvent stale, §5 3-tier fallback, §6 STOMP, §7 Guard, §10 시나리오)
- **관련 ADR**: 0031 (오프라인 1순위) · 0032 (Market Session FSM) · 0033 (3-tier Fallback) · 0035 (KIS HTTP 정밀화) · 0042 (Guard chain) · 0048 (1m-only candle)
- **마스터**: `docs/architecture/master-architecture.md` §A.2 (시세 흐름) · §외부 매트릭스 (KIS)

---

## §1. 배경

### 1-1. 사용자 보고 (2026-05-04 장중 검증)

> "장중인데 차트가 안 움직임. tick 미수신. 분 차트 갱신 정지. Dashboard KOSPI/KOSDAQ 지수 등락률 불변."

### 1-2. 현행 상태 진단

| 영역 | 현황 | 근본 원인 |
|---|---|---|
| `consumer` profile | `dev,real-kis` + `MAGICJAR_MARKET_MOCKTICK_ENABLED=false` | 정상 — mock 폐기 정책 (메모리 `feedback_no_mock_data.md`) |
| Kafka `market.tick.v1` | offset 61715 fixed (정지) | publisher가 더 이상 produce 안 함 (mock 비활성 + WS 미구독) |
| `KisWsClient` | `start()` 호출하는 곳 0 — dead bean | 자동 부팅 흐름 미구현 |
| `KisWsSession` | 체결통보(H0STCNI0/9) 전용 | 시세 trId(H0UNCNT0 등) 없음. AES iv/key 분기는 체결통보만 가정 |
| approval_key | `/oauth2/Approval` 호출 없음 | KIS WS subscribe header에 비어 있음 → 서버 reject |
| 지수 (KOSPI/KOSDAQ) | 어떤 source도 없음 | REST·WS 둘 다 미구현 |
| TickFallbackEmitter | 미구현 | 장외·끊김 시 stale tick 0 |

### 1-3. 정책 컨텍스트

- **mock 합성 금지** (메모리 `feedback_no_mock_data.md`) — 사용자 흐름은 KIS 실 데이터 또는 stale fallback 만 사용. UI는 "데이터 없음"·"stale" 명시.
- **AI 매매 결정권 0** (ADR 0031, 0046) — tick 흐름은 룰/시그널이 단독 판단 가능하도록 영속까지 흘러야 한다.
- **dogfooding 1번 트랙 일시 중단** (메모리 `project_track1_dogfooding_paused.md`) — 본 트랙 R2-A 진행 중에도 1번 트랙 흐름이 무회귀.

---

## §2. 결정 (Decisions)

본 plan 입력으로 메인 세션이 default 10건 위임 + architect KIS 사양 검증으로 확정한 결정.

### 2-A. KIS 실시간 시세 trId 정합 (검증 완료 — GitHub source 기반)

검증 source: `https://github.com/koreainvestment/open-trading-api/tree/main/examples_llm/domestic_stock/{function}/`

| 함수 | 한국명 | tr_id | tr_key 형식 | encrypted | 응답 컬럼 수 | 비고 |
|---|---|---|---|---|---|---|
| `ccnl_total` | 국내주식 실시간체결가(통합) | **`H0UNCNT0`** | 종목코드(6자리) | **N** | 47 | 가격·시간·거래량·등락·매도매수호가1 |
| `asking_price_total` | 국내주식 실시간호가(통합) | **`H0UNASP0`** | 종목코드(6자리) | **N** | ~58 | 매도/매수호가 1~10단계·잔량 |
| `index_ccnl` | 국내지수 실시간체결 | **`H0UPCNT0`** | 지수코드 (KOSPI=`0001`, KOSDAQ=`1001`) | **N** | 30 | 지수값·등락률·거래대금 |
| `index_exp_ccnl` | 국내지수 실시간예상체결 | **`H0UPANC0`** | 지수코드 | **N** | 30 | 장전·장후 예상 (개장 직전 표시용) |
| `market_status_total` | 국내주식 장운영정보(통합) | **`H0UNMKO0`** | 종목코드(6자리) | **N** | 10 | 거래정지/VI/장운영 상태 신호 |
| `ccnl_notice` (참고) | 실시간 체결통보 | `H0STCNI0`/`H0STCNI9` | HTS ID | **Y** (AES) | — | 1번 트랙 기존 구현 |

**중요 발견사항**:
1. **시세 5종은 모두 평문** — AES iv/key 분기는 체결통보(`H0STCNI0/9`)에만 적용. 기존 `KisWsSession`의 iv/key 캐시 로직은 시세 frame엔 NO-OP로 통과해야 한다.
2. **`(통합)` 변형이 KRX/NXT 통합** — 정책 ADR 0031 "오프라인 1순위" + KRX 단일거래소 현실 정합. KRX-only 전용 trId(`H0STCNT0`/`H0STASP0`/`H0STMKO0`)는 보조용으로 보류 — `(통합)` trId가 NXT 미사용 시에도 정상 작동.
3. **지수 코드** — KOSPI=`0001`, KOSDAQ=`1001` (GitHub example 검증). KOSPI200=`2001`, KRX300=`3003`은 후속 (R2-B 또는 그 이후).
4. **approval_key 발급** — `POST /oauth2/Approval` body `{grant_type:"client_credentials", appkey, secretkey, token? }` → response `{approval_key:"..."}` (TTL 1일 추정, KIS 미공식). 매 부팅마다 1회 발급 + 만료 시 1회 재발급.
5. **subscribe frame** — header `{approval_key, custtype:"P", tr_type:"1"|"2", "content-type":"utf-8"}` + body `{input:{tr_id, tr_key}}`. 1 frame = 1 (trId, code) — bulk 구독 불가, 종목·trId 단위 frame 반복 송신.

### 2-B. WebSocket 세션 일반화 (default #2 채택, 검증)

기존 `KisWsSession`을 **trId별 callback dispatch 멀티플렉서**로 일반화한다. 1 KIS account = 1 WS session에서 체결통보 + 시세 + 호가 + 지수 + 장운영을 모두 다중화.

```
KisWsSession (1 KIS account = 1 connection)
   │
   ├─ subscribe(trId="H0STCNI0", trKey=hts_id, listener=executionNoticeListener)  ← 1번 트랙
   ├─ subscribe(trId="H0UNCNT0", trKey="005930", listener=quoteListener)
   ├─ subscribe(trId="H0UNASP0", trKey="005930", listener=orderbookListener)
   ├─ subscribe(trId="H0UPCNT0", trKey="0001", listener=indexListener)
   └─ subscribe(trId="H0UNMKO0", trKey="005930", listener=marketStatusListener)
   
   onText(text) → handleFrame:
     - JSON SUBSCRIBE SUCCESS → iv/key 캐시 (체결통보만)
     - "0|trId|count|payload" → trId lookup → registered listener call
     - "1|trId|count|encrypted" → AES decode → trId lookup → listener call
```

**구체 인터페이스**:
```kotlin
interface KisWsRouter {
    fun subscribe(trId: String, trKey: String, listener: KisWsFrameListener)
    fun unsubscribe(trId: String, trKey: String)
    fun start()    // 부팅 시 1회
    fun shutdown() // 종료 시
}

fun interface KisWsFrameListener {
    fun onFrame(trId: String, trKey: String, columns: List<String>)
}
```

체결통보 listener (`KisExecutionNoticeListener`)는 기존 코드 호환성을 위해 어댑터 패턴으로 trId="H0STCNI0/9" 분기에서 raw 컬럼을 다시 `KisExecutionNoticeRaw`로 변환.

### 2-C. 동시 구독 한도 정책 (default #4 채택, 검증)

**KIS 공식 한도**: account당 동시 등록 41건 (KIS Developer 포털 — 본 repo에서 확인 못 함, KIS 공지 인용).

**우리 정책**:
- 시세 종목 **최대 18종목 × (체결가 + 호가) = 36건** = 41 한도 내 5건 여유
- 추가 등록: 지수 2건 (KOSPI + KOSDAQ ccnl) + 체결통보 1건 (HTS ID) = 39건. 5건 여유로 안전.
- LRU eviction — 보유(orders.status=FILLED count > 0) ∪ 관심(watchlist) ∪ 기본 5종목(`005930`·`000660`·`035720`·`005380`·`051910`) 합집합. 19번째 진입 시 기본 5종 외 가장 오래 사용 안 한 종목을 unsubscribe.
- 기본 5종목은 **eviction 면제** — Dashboard 카드 가시화 보장.
- 사용자 노출: 19번째 종목 등록 요청 시 frontend toast `"실시간 한도(18종목) 도달 — XXX(가장 오래된 관심종목)을 해제했습니다."` (R2-A2 frontend)

### 2-D. 자동 구독 부팅 source (default #3 채택, 검증)

부팅 시 다음 합집합으로 초기 구독 — `KisQuoteSubscriber.bootstrap()`.

```kotlin
val initial: Set<String> = (
    holdingCodes  // SELECT DISTINCT code FROM orders WHERE user_id=? AND status='FILLED' AND filled_qty > 0
    union watchlistCodes  // 사용자 관심종목 (R2-A2에서 watchlist 테이블 도입 또는 user_trading_config 확장)
    union DEFAULT_CODES  // listOf("005930","000660","035720","005380","051910")
).take(MAX_SUBSCRIPTIONS)
```

watchlist 변경 listener (`@EventListener<WatchlistChangedEvent>`) — 추가 시 즉시 subscribe / 삭제 시 unsubscribe.

### 2-E. 지수 fallback 정책 (default #5 채택, 검증)

```
T1 live: KIS WS H0UPCNT0 → 1초~수초 간격 tick (장중)
T2 끊김: REST `/uapi/domestic-stock/v1/quotations/inquire-index-price`
         (`FHPUP02100000`) 5초 polling fallback
T3 장외: 마지막 영업일 종가 (Redis quote:lastbusinessday:idx:{0001}:{yyyyMMdd})
```

지수는 ADR 0033 3-tier에 정합 — code path가 종목과 같으나 source key prefix `quote:last:idx:0001` (종목 충돌 방지).

### 2-F. TickFallbackEmitter 본 작업 포함 (default #6 채택, 검증)

R2-A3에서 통합. session=Closed 또는 T1 무신호 10초 시 stale tick emit. ADR 0033 그대로.

### 2-G. 1분 합성 파이프라인 (default #7 검증)

기존 코드 확인 결과 — `consumer/.../application/aggregator/TickToMinuteAggregator.kt`가 이미 존재 + `magicjar.market.tick-aggregator.enabled=true` default 활성 + `event.stale=true`면 분봉 합성 제외 처리 정상. **신규 파이프라인 불요** — TickFallbackEmitter가 stale=true tick을 produce하면 자동으로 분봉에서 제외된다 (ADR 0048 1m-only 정책 정합).

`RealtimeSignalGenerator`는 별도 시그널 평가용 — 본 plan과 분리.

### 2-H. STOMP 토픽 매핑 (default #8 채택, 검증)

| 이벤트 | Kafka 토픽 | api 모듈 listener | STOMP 토픽 (frontend) |
|---|---|---|---|
| 체결가 | `market.tick.v1` (현행, stale 필드 추가됨 §4.6) | `TickStompFanoutListener` (변경) | `/topic/market.tick.{code}` (현행 유지) |
| 호가 | `market.orderbook.v1` (**신규**) | `OrderbookStompFanoutListener` (신규) | `/topic/market.orderbook.{code}` (신규) |
| 지수 | `market.index.v1` (**신규**) | `IndexStompFanoutListener` (신규) | `/topic/market.index.{code}` (`{code}`=`0001`/`1001`) (신규) |
| 장운영 상태 | `market.status.v1` (**신규**) | `MarketStatusStompFanoutListener` (신규) | `/topic/market.status.{code}` (신규) |
| 체결통보 | (현행) | (현행) | `/topic/orders` (현행) |

frontend 기존 `useSymbolQuote`·`useMarketTickStream`은 그대로. 신규 hook: `useOrderbook`·`useIndexStream`·`useMarketStatus` (R2-A2 frontend).

### 2-I. frontend stale 재빌드는 별도 진행 (default #9 채택)

stale Docker 이미지 회귀 방지는 `scripts/build-and-deploy.sh`가 이미 jar staleness 가드 — frontend dist도 동일하게 재빌드 강제. **본 plan 범위 외** — 사용자 검증 시 host에서 명령으로 처리.

### 2-J. Phase 분할 (default #10 채택, 검증)

- **R2-A1**: 체결가 (PR #1) — KIS WS 시세 라우터 + H0UNCNT0 + TickEvent stale 필드 + frontend 차트 회귀 0
- **R2-A2**: 호가 + 지수 (PR #2) — H0UNASP0 + H0UPCNT0 + 신규 STOMP 토픽 + frontend Orderbook 컴포넌트 + Dashboard 지수 카드 갱신
- **R2-A3**: Fallback 통합 (PR #3) — TickFallbackEmitter + Market Session FSM wiring + 18종목 LRU + watchlist 변경 listener

### 2-K. 신규 ADR 발행 여부

**불필요**. 본 plan의 결정들은 기존 ADR 안에서 흡수:
- WS 세션 일반화 (2-B) → ADR 0033 §2-3 profile 분기 + master §A.2 흐름 명시 정합. 구현 패턴이지 도메인 결정이 아니라 별도 ADR 불요.
- 18종목 LRU (2-C) → ADR 0033 + 운영 매개변수. 운영 가이드 (`_workspace/operations/`) 항목으로 추후.
- trId 정합 (2-A) → 본 plan §2-A 표가 SoT. ADR 0035 (KIS HTTP 정밀화)에 시세 trId 표를 첨부 footnote로 갱신 (선택, R2-A1 시점에 architect 직접 1줄 추가).

만일 R2-A 진행 중 의사결정 갈등이 발생하면 그 때 신규 ADR 0049 발행 트리거.

### 2-L. 메인 세션 default 10건 정정 제안

**없음**. 10건 모두 KIS 사양 검증과 정합 — default 그대로 채택. 단 다음 명시:
- (#3) 자동 구독 default 5종목은 사용자가 변경 가능하게 환경변수화 (`MAGICJAR_MARKET_DEFAULT_CODES`, default `005930,000660,035720,005380,051910`).
- (#5) 지수 fallback 5초 polling은 KIS rate limit 안전 (15 req/sec 한도 대비 0.4 req/sec).

---

## §3. R2-A1 체결가 task 분해

**목표**: 장중 `localhost:3000/symbol/005930` 접속 시 차트가 1초 이내에 새 tick으로 갱신.

**branch**: `agent/{agent}/track2-r2-a1-quote-ws` (R2-A1만)
**PR**: 단일 PR
**검증 게이트**: docker compose 9 컨테이너 healthy + 장중 dogfooding tick 수신 + 분봉 합성 정상 + frontend 차트 갱신.

### T1. KIS approval_key 발급 모듈 (KisWsApprovalProvider)

- **경로**: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisWsApprovalProvider.kt`
- **책임**: `POST /oauth2/Approval` 호출 → `approval_key` 캐시 (TTL 23h, REST token과 동일 패턴) + 만료/재발급.
- **인터페이스**:
  ```kotlin
  interface KisWsApprovalProvider {
      fun getApprovalKey(): String   // 캐시 hit / miss 자동
      fun invalidate()
  }
  ```
- **구현**: `KisHttpSession.client` 재사용 (RestClient 기반 — ADR 0035). appKey/appSecret 누락 시 `IllegalStateException` (mock에선 본 Bean 자체 비활성).
- **Profile**: `@Profile("dev | prd")`
- **테스트**: `KisWsApprovalProviderTest` (WireMock — 200 응답 매핑 + 만료 시 재발급 + 401 시 한 번만 재시도).
- **사이즈**: S
- **담당**: market-data
- **author**: `bot+market-data@magicjar.local`

### T2. KisWsRouter (구 KisWsSession 일반화)

- **경로**: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisWsRouter.kt` (신규 추출)
- **책임**: 단일 WS connection에서 trId×trKey 다중 구독 + frame dispatch.
- **메서드**:
  ```kotlin
  @Component
  @Profile("dev | prd")
  class KisWsRouter(
      private val properties: KisProperties,
      private val approvalProvider: KisWsApprovalProvider,
      private val mapper: ObjectMapper,
  ) {
      private data class SubKey(val trId: String, val trKey: String)
      private val listeners = ConcurrentHashMap<SubKey, KisWsFrameListener>()
      private val pending = ConcurrentLinkedQueue<SubKey>()  // 연결 전 누적
      
      fun subscribe(trId: String, trKey: String, listener: KisWsFrameListener) {...}
      fun unsubscribe(trId: String, trKey: String) {...}
      fun start() {...}      // 부팅 시 1회 — connect → SUBSCRIBE 누적 송신 → reconnect 백오프
      fun shutdown() {...}
      
      private fun handleFrame(text: String) {...}  // §2-B 분기
  }
  ```
- **재연결**: exponential backoff (1s → 2s → 5s → 10s 상한). 재연결 시 `listeners` 전부 재구독.
- **iv/key**: 체결통보 trId(`H0STCNI0`·`H0STCNI9`)에만 SUBSCRIBE SUCCESS의 iv/key 캐시. 시세 trId는 SUBSCRIBE SUCCESS 무시.
- **테스트**: `KisWsRouterTest` (WebSocket fake — Spring `WebSocketHandler` 직접 손으로 invoke + 라우팅 검증). 통합 테스트는 별도 옵션 (미구현 OK 1차).
- **기존 `KisWsClient`·`KisWsSession`**: deprecate. `KisWsClient`는 사용처 0이라 즉시 삭제. `KisWsSession`은 1번 트랙 사용처(`KisExecutionNoticeListener`)가 있으니 `KisWsRouter`로 마이그.
- **사이즈**: M
- **담당**: market-data
- **author**: `bot+market-data@magicjar.local`

### T3. 체결통보(H0STCNI0/9) listener 마이그

- **경로**: 변경 — `consumer/src/main/kotlin/com/mj/consumer/application/order/KisExecutionNoticeListener.kt`
- **책임**: 기존 `KisWsSession.subscribe(trId, trKey, onFrame)` → `KisWsRouter.subscribe(trId, trKey, listener)`로 호출부만 갱신.
- **변환 어댑터**: `KisExecutionNoticeRaw.fromColumns(cols: List<String>)`은 변경 없음. listener 람다에서 trId 분기 후 columns→raw 변환.
- **회귀 검증**: 기존 1번 트랙 dogfooding 흐름 (체결통보 수신·DB 동기화) 100% 보존. unit test 회귀 0.
- **사이즈**: S
- **담당**: market-data
- **author**: `bot+market-data@magicjar.local`

### T4. KisQuoteWsAdapter (체결가 listener)

- **경로**: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisQuoteWsAdapter.kt`
- **책임**: H0UNCNT0 frame columns → `TickEvent` (stale=false) → `TickPublisher.publish()`.
- **컬럼 추출** (§2-A 47 columns):
  - `MKSC_SHRN_ISCD` (idx 0) → code
  - `STCK_CNTG_HOUR` (idx 1, HHmmss) → at (KST today)
  - `STCK_PRPR` (idx 2) → price (BigDecimal)
  - `CNTG_VOL` (idx 12) → volume (체결량)
  - `ACML_VOL` (idx 13) → cumulativeVolume (선택, 기존 TickEvent에 없음 — 향후)
- **인터페이스**:
  ```kotlin
  @Component
  @Profile("dev | prd")
  class KisQuoteWsAdapter(
      private val publisher: TickPublisher,
  ) : KisWsFrameListener {
      override fun onFrame(trId: String, trKey: String, columns: List<String>) {
          if (trId != "H0UNCNT0") return
          // ... 변환 + publisher.publish(TickEvent(...))
      }
  }
  ```
- **테스트**: `KisQuoteWsAdapterTest` (raw `^` 분리 columns 입력 → TickPublisher.publish 1회 호출 + 매핑 정확성).
- **사이즈**: S
- **담당**: market-data
- **author**: `bot+market-data@magicjar.local`

### T5. KisQuoteSubscriber (자동 구독 bootstrap)

- **경로**: `consumer/src/main/kotlin/com/mj/consumer/application/market/KisQuoteSubscriber.kt`
- **책임**:
  1. 부팅 시 (`@EventListener<ApplicationReadyEvent>`) `KisWsRouter.start()` 호출 + 초기 구독 종목 산출 + subscribe 일괄.
  2. 정책: holdings ∪ watchlist ∪ default5 (§2-D).
  3. 18종목 한도 적용 (§2-C) — R2-A3에서 LRU 정밀화. R2-A1엔 `take(18)` 단순 절단.
  4. R2-A1: 체결가만(`H0UNCNT0`) 일괄 등록.
- **인터페이스**:
  ```kotlin
  @Component
  @Profile("dev | prd")
  class KisQuoteSubscriber(
      private val router: KisWsRouter,
      private val quoteListener: KisQuoteWsAdapter,
      private val holdingsRepo: HoldingsCodeFetcher,    // 신규 simple port
      @Value("\${magicjar.market.default-codes:005930,000660,035720,005380,051910}")
      private val defaultCodes: List<String>,
  ) {
      @EventListener
      fun onReady(e: ApplicationReadyEvent) {
          router.start()
          val codes = (holdingsRepo.fetchHoldingCodes() + defaultCodes)
              .distinct()
              .take(MAX_SUBSCRIPTIONS)
          codes.forEach { router.subscribe("H0UNCNT0", it, quoteListener) }
      }
      
      companion object { const val MAX_SUBSCRIPTIONS = 18 }
  }
  ```
- **HoldingsCodeFetcher**: api 모듈에 query method `findDistinctFilledCodes(): List<String>` 같은 read-only port. R2-A1엔 빈 List 반환 stub OK (1번 트랙 dogfooding이 일시 중단). 실제 구현은 R2-A3 watchlist 도입 시.
- **테스트**: `KisQuoteSubscriberTest` (Mock router + holdings → router.subscribe 호출 횟수 검증).
- **사이즈**: M
- **담당**: market-data
- **author**: `bot+market-data@magicjar.local`

### T6. TickEvent 영향·하위호환 검증

- 도메인 `TickEvent`는 이미 `stale: Boolean = false`·`asOf: Instant? = null` 필드 보유 (§4.6 spec, 본체 구현 완료). 회귀 0 확인.
- `TickStompFanoutListener` (api 모듈)도 stale·asOf를 STOMP payload에 그대로 포함 (이미 spec 반영) — **재확인**.
- frontend `TickMessage` 타입 (`frontend/src/api/types.ts`)에 `stale?: boolean`·`asOf?: number` optional 추가 (없어도 chart 동작) — R2-A1엔 추가만, 표시는 R2-A3.
- **사이즈**: S
- **담당**: backend-core (api 측 `TickStompFanoutListener` 회귀 확인) + frontend (`types.ts` optional 추가)
- **author**: 둘 다 각자 author

### T7. application.yml + KisProperties 정리

- `consumer/src/main/resources/application.yml` — `kis.ws-url` 주석에 trId 표 한 줄 + `magicjar.market.default-codes` env binding 명시.
- `KisProperties`에 `defaultCodes: List<String>` 필드 추가 (선택 — `@Value`만으로 충분).
- 기존 `KAFKA_LOG_RETENTION_HOURS=1` 설정(개시 5/1) 유지 — tick 폭주 방지.
- **사이즈**: S
- **담당**: market-data
- **author**: `bot+market-data@magicjar.local`

### T8. R2-A1 통합 테스트 + 수동 dogfooding 가이드

- 단위테스트만 1차 (T1·T2·T4·T5 각각).
- 통합 IT (`KisWsRouterIT`)는 **선택** — 내부 fake WebSocketHandler로 한 번에 4개 trId frame 분배 검증. 사이즈 M, 별도 commit.
- 수동 dogfooding: `_workspace/dogfooding-2026-05-04-r2-a1.md` 신규 — 절차:
  1. `.env` KIS 키 확인
  2. `./gradlew :consumer:bootJar && docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d --build consumer api frontend`
  3. `localhost:3000/symbol/005930` 접속 → 1초 내 새 tick → 차트 갱신 확인
  4. `docker logs magicjar-consumer | grep -i "KIS_WS"` 로 router 부팅·구독 로그 확인
  5. `docker exec magicjar-kafka kafka-console-consumer --topic market.tick.v1 --from-beginning --max-messages 5` → KIS source tick 검증
- **사이즈**: S
- **담당**: architect (또는 메인 세션)
- **author**: `bot+architect@magicjar.local`

### T9. PR #1 발행 + 사용자 검증 + 머지

- **branch**: `agent/market-data/track2-r2-a1-quote-ws`
- **author 단일**: `bot+market-data@magicjar.local` (T1·T2·T3·T4·T5·T7), **T6의 frontend types.ts**·**T6 api `TickStompFanoutListener` 회귀**·**T8 dogfooding 문서**는 별도 author 분리 commit.
- **PR 본문**: §2 결정 요약 + §3 task 체크리스트 + dogfooding 절차.
- **QA on-demand 트리거**: 외부 의존(KIS WS) + 보안 경계 무관 + 새 도메인 이벤트 0건 — **호출 안 함** (ADR 0016).

---

## §4. R2-A2 호가 + 지수 task 분해

**목표**: SymbolDetail 페이지에 실시간 호가창 + Dashboard에 KOSPI/KOSDAQ 등락률 1초~수초 갱신.

**branch**: `agent/{agent}/track2-r2-a2-orderbook-index`
**PR**: 단일 PR
**선행**: R2-A1 머지 완료.

### T10. 호가(H0UNASP0) — 도메인 이벤트 + Kafka 토픽

- **신규 도메인 이벤트**: `domain/.../market/event/OrderbookEvent.kt`
  ```kotlin
  data class OrderbookLevel(val price: BigDecimal, val quantity: Long)
  data class OrderbookEvent(
      val code: String,
      val at: Instant,
      val asks: List<OrderbookLevel>,  // size 10
      val bids: List<OrderbookLevel>,  // size 10
      val totalAskQty: Long,
      val totalBidQty: Long,
      val source: String = "KIS_WS",
      val stale: Boolean = false,
      val asOf: Instant? = null,
  )
  ```
- **신규 Kafka 토픽**: `market.orderbook.v1` (consumer producer + api consumer).
- **신규 publisher**: `consumer/.../messaging/producer/OrderbookPublisher.kt`.
- **사이즈**: S
- **담당**: architect (도메인 정의) + market-data (publisher)

### T11. KisOrderbookWsAdapter (호가 listener)

- **경로**: `consumer/.../infrastructure/kis/KisOrderbookWsAdapter.kt`
- **책임**: H0UNASP0 frame columns → `OrderbookEvent` → `OrderbookPublisher.publish()`.
- **컬럼 매핑** (§2-A 호가 컬럼):
  - `MKSC_SHRN_ISCD` → code
  - `BSOP_HOUR` → at (KST today HHmmss)
  - `ASKP1`~`ASKP10` → asks[0..9].price
  - `ASKP_RSQN1`~`ASKP_RSQN10` → asks[0..9].quantity
  - `BIDP1`~`BIDP10`, `BIDP_RSQN1`~`BIDP_RSQN10` → bids
  - `TOTAL_ASKP_RSQN`/`TOTAL_BIDP_RSQN`
- **테스트**: `KisOrderbookWsAdapterTest` (raw columns → publisher.publish).
- **사이즈**: M
- **담당**: market-data

### T12. 지수 (H0UPCNT0) — 도메인 + adapter

- **신규 도메인 이벤트**: `domain/.../market/event/IndexTickEvent.kt`
  ```kotlin
  data class IndexTickEvent(
      val code: String,             // "0001"=KOSPI, "1001"=KOSDAQ
      val at: Instant,
      val value: BigDecimal,        // 지수값
      val change: BigDecimal,       // 전일대비
      val changeRate: BigDecimal,   // 등락률 (%)
      val volume: Long,             // 누적 거래량
      val turnover: Long,           // 누적 거래대금
      val source: String = "KIS_WS",
      val stale: Boolean = false,
      val asOf: Instant? = null,
  )
  ```
- **신규 Kafka 토픽**: `market.index.v1`.
- **신규 adapter**: `KisIndexWsAdapter`. 컬럼 매핑 (§2-A 지수 30 columns):
  - `bstp_cls_code` (idx 0) → code (`0001`/`1001`)
  - `bsop_hour` (idx 1) → at
  - `prpr_nmix` (idx 2) → value
  - `bstp_nmix_prdy_vrss` (idx 4) → change
  - `prdy_ctrt` (idx 9) → changeRate
  - `acml_vol` (idx 5) → volume
  - `acml_tr_pbmn` (idx 6) → turnover
- **신규 publisher**: `IndexPublisher`.
- **사이즈**: M
- **담당**: architect (도메인) + market-data (publisher + adapter)

### T13. KisIndexRestPolling (지수 REST fallback)

- **경로**: `consumer/.../application/market/KisIndexRestPolling.kt`
- **책임**: WS 끊김 또는 KIS rate limit 시 5초 polling 활성. ADR 0033 T2 정합.
- **트리거**: `KisWsRouter`가 H0UPCNT0 frame을 5초 이상 안 받으면 `IndexFallbackTrigger` 이벤트 publish → 본 컴포넌트 활성화.
- **REST 호출**: KIS `/uapi/domestic-stock/v1/quotations/inquire-index-price` (`FHPUP02100000`) — KOSPI/KOSDAQ 각각.
- **publish**: `IndexTickEvent(stale=true, source="KIS_REST", asOf=now)`.
- **사이즈**: M
- **담당**: market-data

### T14. KisQuoteSubscriber 확장 (호가 + 지수 등록)

- 부팅 시 `H0UNCNT0` + `H0UNASP0` 두 trId × 18종목 = 36 register, `H0UPCNT0` × 2 = 38 total.
- 41 한도 내 3건 여유 (체결통보 1건 별도 등록 시).
- **사이즈**: S
- **담당**: market-data

### T15. api 측 신규 STOMP fanout listener

- **경로** (3개):
  - `api/.../messaging/listener/OrderbookStompFanoutListener.kt`
  - `api/.../messaging/listener/IndexStompFanoutListener.kt`
  - `api/.../messaging/listener/MarketStatusStompFanoutListener.kt` (R2-A2엔 placeholder, 본격은 R2-A3 또는 R2-B)
- **STOMP 토픽** (§2-H):
  - 호가 → `/topic/market.orderbook.{code}`
  - 지수 → `/topic/market.index.{code}` (`{code}` = `0001`·`1001`)
- **payload**: 도메인 이벤트 그대로 + STOMP-specific DTO (camelCase 변환).
- **테스트**: 각 listener 단위 (Kafka consumer + SimpMessagingTemplate mock).
- **사이즈**: M
- **담당**: backend-core
- **author**: `bot+backend-core@magicjar.local`

### T16. frontend 호가창 + 지수 카드

- **신규 hook**:
  - `frontend/src/hooks/useOrderbook.ts` — STOMP 구독 + 10단계 호가 buffering.
  - `frontend/src/hooks/useIndexStream.ts` — KOSPI/KOSDAQ 동시 구독.
- **신규 컴포넌트**:
  - `frontend/src/components/Orderbook/Orderbook.tsx` — 10×2 그리드 (매도10·매수10) + 잔량 막대.
  - `frontend/src/pages/Dashboard/IndexCard.tsx` — KOSPI/KOSDAQ 카드 (값 + 등락률 + 색상).
- **변경**:
  - `frontend/src/pages/SymbolDetail/index.tsx` — 차트 우측에 Orderbook 컴포넌트 배치.
  - `frontend/src/pages/Dashboard/index.tsx` — 상단에 IndexCard 2개 (기존 watch list 위).
  - `frontend/src/api/types.ts` — `OrderbookMessage`·`IndexMessage` 신규.
- **테스트**: vitest 5건 (호가 정렬·매도/매수 분리·등락률 색상·웹소켓 fallback).
- **사이즈**: M
- **담당**: frontend
- **author**: `bot+frontend@magicjar.local`

### T17. PR #2 발행 + 사용자 검증 + 머지

- **branch**: `agent/market-data/track2-r2-a2-orderbook-index` (또는 frontend·architect로 분기 — 메인 세션 dispatch 시 결정)
- **검증**: dogfooding 절차 — Dashboard에 KOSPI 등락률이 1초 내 갱신, SymbolDetail에서 호가창 실시간 변동 확인.
- **QA**: 신규 토픽 3개 + 신규 도메인 이벤트 2개 — ADR 0016 트리거 4 (도메인 이벤트 신규) 활성. **QA on-demand 호출** (sub-agent boundary-qa 1회).

---

## §5. R2-A3 Fallback 통합 task 분해

**목표**: 장 마감·KIS 끊김·휴일에 cached 가격이 stale 배지와 함께 차트에 표시. Market Session FSM이 wiring되어 사용자 UI에 명시.

**branch**: `agent/{agent}/track2-r2-a3-fallback-session-wiring`
**PR**: 단일 PR
**선행**: R2-A1·R2-A2 머지 완료.

### T18. MarketSessionResolver wiring (consumer)

- spec §4·§7 + ADR 0032 정의된 sealed class·port 구현체 본격 wiring.
- 본 plan에선 **resolver만** — 본격 Guard chain·REJECTED audit는 트랙2 R2-B/R3 (spec §7·§8) 사이클로 분리.
- **경로**:
  - `consumer/.../application/session/MarketSessionResolverImpl.kt` (KST + market_calendar lookup)
  - `consumer/.../application/session/MarketCalendarBootSeeder.kt` (V10 시드 보강)
- **api 노출**: `GET /api/market/session` — `MarketSessionController` (api 측). spec §6.1 응답 스키마 그대로.
- **사이즈**: M
- **담당**: backend-core

### T19. TickFallbackEmitter

- **경로**: `consumer/.../application/market/TickFallbackEmitter.kt`
- **책임** (ADR 0033 T2/T3):
  - `@Scheduled(fixedDelay=10_000L)` — 10초 주기.
  - resolver.resolve() 결과로 분기:
    - OPEN — 무동작 (T1 정상). 단 마지막 tick from `QuoteCache`가 30초 이상 무신호면 stale=true with `source="cache"` emit.
    - CLOSED/HOLIDAY — 모든 구독 종목에 대해 `quote:lastbusinessday:{code}:{date}` 또는 candle 마지막 close → stale=true, source="last-day-close" emit.
- **읽기 대상** (Redis QuoteCache + JPA candle):
  - `QuoteCache.get(code)` (last tick) — TTL 7일, ADR 0033 §1.
  - candle 마지막 일봉 close (cold cache fallback).
- **사이즈**: M
- **담당**: market-data

### T20. QuoteCacheRedis (cache 어댑터)

- **경로**: `consumer/.../infrastructure/cache/QuoteCacheRedis.kt`
- **port**: `domain/.../market/cache/port/QuoteCache.kt` (이미 spec §4.4 정의, 신규 발행)
- **책임**: TickIngestListener가 매 tick에 `put(code, tick)` — 7일 TTL. TickFallbackEmitter가 `get(code)` — null 또는 stale CachedQuote.
- **신규 listener** (TickIngest): `consumer/.../messaging/listener/TickCacheUpdateListener.kt` — `market.tick.v1` 구독 + `QuoteCache.put`.
- **사이즈**: S
- **담당**: backend-core

### T21. 18종목 LRU + watchlist 변경 listener

- **경로**: `consumer/.../application/market/SubscriptionManager.kt`
- **책임**:
  - holdings·watchlist·default5 합집합 maintain.
  - 변경 이벤트 `WatchlistChangedEvent` 수신 시 `KisWsRouter.subscribe`/`unsubscribe` 호출.
  - 18종목 한도 초과 시 LRU eviction (default5 제외).
  - 사용자 노출용 toast 이벤트 publish (`SubscriptionEvictedEvent` → STOMP fanout `/topic/notifications`).
- **사이즈**: M
- **담당**: market-data

### T22. frontend stale 표시 + Market Session 배지

- **변경**:
  - `frontend/src/components/MarketSessionBadge.tsx` (신규)
  - `frontend/src/hooks/useMarketSession.ts` (신규, /api/market/session polling 1분)
  - SymbolDetail 차트 우상단 + Dashboard 헤더에 MarketSessionBadge.
  - TickMessage `stale=true` 시 가격 색상 흐림 + "캐시 N초 전" 표시.
- **사이즈**: M
- **담당**: frontend

### T23. PR #3 발행 + 사용자 검증 + 머지

- **branch**: `agent/architect/track2-r2-a3-fallback-session-wiring`
- **검증** (ADR 0016 트리거):
  - 도메인 이벤트 2건 신규 (cache port·SubscriptionEvictedEvent) → 트리거 4
  - frontend UI 변경 (stale 배지) → 트리거 5
  - Schedule fallback emitter → 외부 의존 트리거 6
  - **QA on-demand 호출 의무** (boundary-qa).
- **dogfooding**: 토요일 또는 영업일 16:00 이후 — Dashboard "장 마감 (X일 기준)" 배지 + 흐린 가격 + Orderbook 0건 정상.

---

## §6. STOMP 토픽 매핑 표

(§2-H 그대로 — 본 §은 전체 plan 안에서의 cross-reference 단일 출처)

| 데이터 | 도메인 이벤트 | Kafka 토픽 | api listener | STOMP 토픽 | frontend hook |
|---|---|---|---|---|---|
| 체결가 (현행) | `TickEvent` | `market.tick.v1` | `TickStompFanoutListener` | `/topic/market.tick.{code}` | `useSymbolQuote` |
| 호가 (R2-A2) | `OrderbookEvent` | `market.orderbook.v1` | `OrderbookStompFanoutListener` | `/topic/market.orderbook.{code}` | `useOrderbook` |
| 지수 (R2-A2) | `IndexTickEvent` | `market.index.v1` | `IndexStompFanoutListener` | `/topic/market.index.{code}` (0001/1001) | `useIndexStream` |
| 장운영 (R2-B) | `MarketStatusEvent` | `market.status.v1` | `MarketStatusStompFanoutListener` | `/topic/market.status.{code}` | `useMarketStatus` |
| Market Session | `MarketSession` (REST 노출) | — (REST 또는 별도 v1 추후) | `MarketSessionController` GET | (REST polling) | `useMarketSession` |
| 체결통보 (현행) | `OrderEvent` | `order.v1` | (현행) | `/topic/orders` | `useOrderStream` |

---

## §7. 자동 구독 부팅 흐름

```
ApplicationReadyEvent (Spring Boot 부팅 완료)
   │
   └─→ KisQuoteSubscriber.onReady()
         │
         ├─ KisWsRouter.start()
         │      │
         │      ├─ KisWsApprovalProvider.getApprovalKey()  ← (캐시 miss 시 /oauth2/Approval)
         │      └─ WebSocket connect → kis.wsUrl
         │             │
         │             └─ on connection open: pending queue flush
         │
         ├─ holdingCodes = HoldingsCodeFetcher.fetch()
         ├─ watchlistCodes = WatchlistFetcher.fetch()  (R2-A3)
         ├─ defaultCodes = MAGICJAR_MARKET_DEFAULT_CODES env (default 5)
         │
         ├─ initial = (holdings ∪ watchlist ∪ defaults).take(18)
         │
         └─ for each code in initial:
                ├─ R2-A1: router.subscribe("H0UNCNT0", code, quoteListener)
                ├─ R2-A2: router.subscribe("H0UNASP0", code, orderbookListener)
                └─ R2-A3: 추가 — KisIndexSubscriber bootstrap (KOSPI=0001, KOSDAQ=1001)

WatchlistChangedEvent 도착 시 (R2-A3):
   │
   └─→ SubscriptionManager.onChange(event)
         ├─ added codes: router.subscribe(... ) × 2 trId (체결가+호가)
         └─ removed codes: router.unsubscribe(...)
                + 18종목 한도 검사 (LRU eviction)
```

---

## §8. 18종목 한도 정책

### 8-1. 산식

```
KIS account 한도 (실 개정·KIS Developer 기준): 41 등록
  ─ 체결통보 1건 (HTS ID, 1번 트랙 사용)
  ─ 시세 (R2-A1): 18 종목 × H0UNCNT0 = 18
  ─ 호가 (R2-A2): 18 종목 × H0UNASP0 = 18
  ─ 지수 (R2-A2): 2건 × H0UPCNT0 = 2
  ─ (선택) 장운영: 18종목 × H0UNMKO0 = 18  ← 실 활성 시 한도 초과 → R2-B에서 별도 정책 (또는 default 1종목으로 보조)
  ─────────────────────────────────────
  Total (R2-A 종료 시): 1 + 18 + 18 + 2 = 39 ≤ 41 ✓
```

### 8-2. 우선순위 (LRU eviction)

```
1. 면제 (영구): 기본 5종목 (005930, 000660, 035720, 005380, 051910) — Dashboard 카드 표시
2. holdings (현재 보유 — orders.status=FILLED & filled_qty>0)
3. watchlist (사용자 관심)
4. transient (사용자 페이지 진입 시 임시 — 페이지 이탈 30초 후 LRU)
```

19번째 종목 진입 시:
- 우선순위 4 — 즉시 evict
- 우선순위 4가 없으면 — 우선순위 3 LRU (마지막 access)
- 우선순위 3가 없으면 — 우선순위 2 LRU
- 우선순위 1은 evict 불가 — 사용자 에러 toast `"실시간 시세 한도 도달 — 관심종목을 정리해주세요."`

### 8-3. 사용자 노출

frontend toast (R2-A3):
- 정상 evict: `"실시간 한도 도달 — XXX(가장 오래된 관심종목)을 해제했습니다."`
- 거부: `"실시간 시세는 최대 18종목까지 추적합니다. 관심종목을 일부 해제하세요."`

---

## §9. 테스트 전략

### 9-1. 단위 테스트

| Task | 테스트 클래스 | 주요 케이스 |
|---|---|---|
| T1 | `KisWsApprovalProviderTest` | 캐시 hit/miss · 만료 · 401 retry · WireMock |
| T2 | `KisWsRouterTest` | trId×trKey dispatch · iv/key 분기 (체결통보만) · pending queue · 재연결 |
| T4 | `KisQuoteWsAdapterTest` | columns→TickEvent 매핑 · 잘못된 trId 무시 |
| T5 | `KisQuoteSubscriberTest` | bootstrap 시 18 take · default5 fallback |
| T11 | `KisOrderbookWsAdapterTest` | 10단계 호가 매핑 · 빈 호가 단계 처리 |
| T12 | `KisIndexWsAdapterTest` | 0001/1001 분기 · 등락률 계산 |
| T15 | `OrderbookStompFanoutListenerTest`·`IndexStompFanoutListenerTest` | Kafka consumer + SimpMessagingTemplate mock |
| T16 | vitest — `Orderbook.test.tsx`·`IndexCard.test.tsx`·`useOrderbook.test.ts` | 호가 정렬·등락률 색상·구독 정리 |
| T18 | `MarketSessionResolverImplTest` | KST + 캘린더 + 다음 영업일 |
| T19 | `TickFallbackEmitterTest` | OPEN 무동작 · CLOSED stale emit · 30초 무신호 cache emit |
| T20 | `QuoteCacheRedisTest` | put/get/TTL · null on miss |
| T21 | `SubscriptionManagerTest` | LRU · default5 면제 · 한도 초과 reject |

### 9-2. IT (Integration Tests)

- **선택**: `KisWsRouterIT` — Spring `@SpringBootTest` + Reactor Netty fake server (또는 자체 echo handler) — 1 connection에 5 trId frame을 분배 → listener 호출 횟수 검증.
- **선택**: `TickRoundtripIT` (기존 EmbeddedKafka) 회귀 — TickEvent stale=false 그대로 흐름 OK 확인.

### 9-3. dogfooding (수동)

각 R2-A1/2/3 PR 머지 직후 사용자 검증 절차 별도 markdown:
- `_workspace/dogfooding-2026-05-04-r2-a1.md`
- `_workspace/dogfooding-2026-05-04-r2-a2.md`
- `_workspace/dogfooding-2026-05-04-r2-a3.md`

---

## §10. PR 분할 + 커밋 author

### R2-A1 PR

- **branch**: `agent/market-data/track2-r2-a1-quote-ws`
- **author**: `bot+market-data@magicjar.local` (T1·T2·T3·T4·T5·T7·T8 메인) + `bot+frontend@magicjar.local` (T6 frontend `types.ts` optional 한 줄) + `bot+backend-core@magicjar.local` (T6 api `TickStompFanoutListener` stale 통과 회귀 확인 — 변경 0이면 commit 없음)
- **머지 정책**: rebase (ADR 0017 author 보존)

### R2-A2 PR

- **branch**: `agent/market-data/track2-r2-a2-orderbook-index`
- **author**: `bot+architect@magicjar.local` (T10·T12 도메인 이벤트 정의) + `bot+market-data@magicjar.local` (T11·T12 adapter·T13·T14) + `bot+backend-core@magicjar.local` (T15 STOMP listener) + `bot+frontend@magicjar.local` (T16 UI)
- **여러 author** — 메인 세션 dispatch 시 sub-agent 4명 worktree isolation 또는 architect 1인이 author 분리 commit 직접 작성.
- **QA on-demand**: 1회 (boundary-qa, 도메인 이벤트 신규 트리거).

### R2-A3 PR

- **branch**: `agent/architect/track2-r2-a3-fallback-session-wiring`
- **author**: `bot+backend-core@magicjar.local` (T18·T20 cache 어댑터) + `bot+market-data@magicjar.local` (T19·T21) + `bot+frontend@magicjar.local` (T22)
- **QA on-demand**: 1회 (트리거 4·5·6 동시).

---

## §11. 후속 작업 + 리스크

### 11-A. 본 plan 비범위 (R2-B 또는 그 이후)

- **REST quote endpoint 통합** (T2 spec §6.1 잔여) — 현재 `/api/instruments/{code}/candles`만 있고 `/quote`가 없음. R2-B에서.
- **장운영(H0UNMKO0) 본격 구독** — VI 발동 알림 + 거래정지 종목 자동 마킹. R2-B 또는 트랙3.
- **`market_calendar` 자동 갱신 batch job** (T2 spec §9.1 T23) — 1차 정적 시드.
- **MarketSessionGuard·REJECTED audit trail** (spec §7·§8) — 1번 트랙 dogfooding 재개와 함께. T2 R2-B/R3.
- **Spring AI 기반 사용자 시그널 분석 코멘트** — ADR 0046 AI 옵션. 트랙4.

### 11-B. 리스크

| ID | 항목 | 영향 | 완화 |
|---|---|---|---|
| R-01 | KIS approval_key 만료 시점 미공식 (TTL 1일 추정) | WS 재구독 실패 | 401 응답 시 1회 재발급 + 재연결 시 항상 fresh approval_key 발급 |
| R-02 | 41 등록 한도가 KIS 정책 변경 시 잘림 | 18종목 정책 깨짐 | KIS Developer 공지 모니터링 + 한도 환경변수화 (`MAGICJAR_KIS_WS_MAX_REGISTERS=41`) |
| R-03 | 장중에 WS 끊김이 잦으면 Redis cache 미덥지 (오래된 stale) | 사용자 혼동 | TickFallbackEmitter가 `(now - cachedAt) > 60초`이면 stale 표시 강화 + UI 명시 |
| R-04 | KIS WS frame format이 column count 변경 시 매핑 깨짐 | adapter 깨짐 | column count 검증 + 부족 시 warn log + skip (fail-safe) |
| R-05 | TickToMinuteAggregator가 stale tick을 무시 — 분봉 0개 발생 가능 | 차트 분봉 정지 | 정상 동작 (ADR 0048) — 사용자 UI에 stale 표시로 인지 |
| R-06 | docker compose stale image 회귀 (1번 트랙 #65 사례) | 머지해도 안 됨 | dogfooding 절차에 `--build` 명시 + `scripts/build-and-deploy.sh` jar 가드 사용 |
| R-07 | WebSocket Java client 비동기 thread pool exhaustion (구독 종목 폭증 시) | 응답 지연 | `KisWsRouter`는 단일 connection — 채널 1개라 thread 부담 낮음. 모니터링은 `KisMetricsRecorder` 확장 |
| R-08 | KOSPI 지수 코드 표준 변경 (예: `0001` → 다른 prefix) | 지수 카드 정지 | KIS 공지 + master file 동기화 batch job 신설 (R2-B) |

### 11-C. 한도 초과 시 escalation

본 plan의 task 단위는 architect 추정 — 실제 구현 중 다음 갈등 발생 시 architect로 escalate (1회):

- **WS frame frequency가 100/sec 넘어 Kafka producer backpressure 발생** — `acks=1` + buffer.memory 증가로 1차 완화. 안 되면 architect ADR 0049 (`market.tick.v1` partitioning by code 제 1 단계 → 다중 partition).
- **frontend STOMP 메시지 폭증으로 React 리렌더 정체** — throttle hook (60Hz max) — frontend agent 직접 처리. architect escalate 불요.
- **18종목 한도가 사용자 흐름에 부족 (관심종목 > 18)** — UX 결정 — 메인 세션 사용자 컨펌 후 한도 32종목으로 상향 검토 (체결가만 등록 + 호가는 SymbolDetail 진입 시만 transient).

---

## 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-04 | 초안 — KIS 사양 검증 + R2-A1/A2/A3 task 분해 + STOMP 매핑 + 18종목 LRU |
