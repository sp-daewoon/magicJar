# PR-9 sub-agent 분배 명세

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-9-w2-design.md` v1 + ADR 0048(본 PR 발행)·0028·0014·0029·0019·0037·0038·0040·0042·0045·0050
- 본 문서: orchestrator가 backend-core·market-data 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-9-conflict-matrix.md` 참조.
- **본 PR-9은 ADR 0050 (CHANGELOG 슬롯 채번룰) 첫 적용 사이클** — 본 spec PR이 sub-agent별 슬롯 row 미리 발행 (§5 참조).

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-9a** | backend-core | M~L | `agent/backend-core/pr-9-w2-backend` | **병렬 1** |
| **PR-9b** | market-data | M~L | `agent/market-data/pr-9-w2-market-data` | **병렬 1** |

**디스패치 모드**: **병렬** (충돌 행렬 정합 — `_workspace/specs/pr-9-conflict-matrix.md` §8 결론).

**인터페이스 동결 시점**: 본 spec(`pr-9-w2-design.md` + 본 dispatch + 충돌 행렬 + ADR 0048 본문)이 머지되는 즉시 — backend-core·market-data 양쪽이 §3~§7(설계 spec) + 본 dispatch §3 (REST/Kafka/Redis 시그니처) + §4 (인터페이스) + §5 (CHANGELOG 슬롯) 보고 동시 시작 가능.

**최고 관리자 메모리 정합 — `feedback_orchestrator_active_parallel.md`**: architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능. 본 PR-9는 충돌 행렬에서 modify-shared 1건(application.yml — 격리 권고로 PR-9a 단독)만 식별 → **완전 병렬 권장**.

---

## §2. backend-core 위임 (PR-9a)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet) |
| 브랜치 | `agent/backend-core/pr-9-w2-backend` |
| base | `main` (PR-7·PR-8 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-W2 backend — WatchlistChangedKafkaListener + SubscriptionRouter + WatchlistIngestionWorker(facade) + WatchlistPollingWorker + Yahoo cold-start (consumer YahooCandleFetcher) + KIS REST 1wk·1mo·1y backfill (KisRestCandleAdapter) + Redis source tracker + candle JpaEntity·Repository·Adapter + domain port (SubscriptionPolicy + YahooCandleFetcher) + smoke test 2건` |
| Task 파일 deliverables | `consumer/.../kafka/WatchlistChangedKafkaListener.kt, consumer/.../ingestion/SubscriptionRouter.kt + WatchlistIngestionWorker.kt + WatchlistPollingWorker.kt, consumer/.../infrastructure/yahoo/YahooCandleFetcher.kt, consumer/.../infrastructure/kis/KisRestCandleAdapter.kt + KisRestPriceAdapter.kt (※ market-data와 동시 구현 영역 명세), consumer/.../infrastructure/cache/RedisQuoteCacheAdapter.kt + RedisSourceTrackerAdapter.kt, api/.../persistence/CandleJpaEntity.kt + CandleJpaRepository.kt + CandleRepositoryAdapter.kt (api 모듈 — Yahoo 어댑터 위치 정합), domain/.../market/port/SubscriptionPolicy.kt + YahooCandleFetcher.kt, consumer/.../infrastructure/session/SimpleMarketSessionResolver.kt, application.yml Kafka producer config + topic auto-create, smoke test 2건` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | M~L |

### 2-2. 작업 범위 (Done 정의)

다음 모두 완료해야 PR-9a Done:

#### A. domain port 신규 (add-only)

- [ ] `domain/.../market/port/SubscriptionPolicy.kt` — slot 예산 결정 함수 (pure)
  ```kotlin
  package kr.spdaewoon.magicjar.domain.market.port

  /**
   * KIS WS 41 stream slot 예산 결정 (ADR 0048 §2-1).
   * Pure function — Spring 의존 0.
   */
  object SubscriptionPolicy {
      const val TOTAL_SLOTS = 41
      const val EXECUTION_NOTICE_RESERVED = 1  // H0STCNI0
      const val PRICE_TICK_MAX = 30            // H0STCNT0
      const val ORDERBOOK_MAX = 10             // H0STASP0 (LRU)

      fun priceTickSlotsAvailable(currentSubscribed: Int): Int =
          (PRICE_TICK_MAX - currentSubscribed).coerceAtLeast(0)
  }
  ```

- [ ] `domain/.../market/port/YahooCandleFetcher.kt` — Yahoo cold-start port
  ```kotlin
  package kr.spdaewoon.magicjar.domain.market.port

  import kr.spdaewoon.magicjar.domain.market.candle.Candle
  import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval

  /**
   * Yahoo Finance v8 chart endpoint 어댑터 (ADR 0019 정합).
   * 구현은 consumer 모듈 또는 api 모듈 (양쪽 가능).
   */
  interface YahooCandleFetcher {
      /**
       * @param ticker  KIS 종목코드 (6자리). 어댑터 내부에서 .KS/.KQ suffix 결정
       * @param interval 1m·5m·15m·30m·90m·1h·1d
       * @param rangeDays 1m=7, 5m·15m·30m·90m=60, 1h=730, 1d=Max(전체)
       * @return Candle 리스트 (raw 컬럼 채움, adj null — T-CA1)
       */
      fun fetch(ticker: String, interval: CandleInterval, rangeDays: Int): List<Candle>
  }
  ```

- [ ] `domain/.../market/port/CandleRepository.kt` (없으면 신규 — 있으면 PR-5a에 이미 존재 가정)
  ```kotlin
  interface CandleRepository {
      fun saveAll(candles: List<Candle>): Int  // ON CONFLICT DO NOTHING — INSERTed 수
      fun countByCodeAndInterval(code: String, interval: CandleInterval): Long
  }
  ```

#### B. consumer Kafka listener (`watchlist.changed.v1` 첫 consumer)

- [ ] `consumer/.../kafka/WatchlistChangedKafkaListener.kt` — spec §4 정합
  - `@KafkaListener(topics = ["watchlist.changed.v1"], groupId = "magicjar-consumer-watchlist-ingestion", containerFactory = "watchlistChangedKafkaListenerContainerFactory")`
  - manual ack (`@Header(KafkaHeaders.ACKNOWLEDGMENT) ack: Acknowledgment`)
  - JSON parse — Jackson `ObjectMapper.readValue` (`WatchlistChangedEvent` data class)
  - action=ADDED → `subscriptionRouter.add(userId, code, priority)` + `coldStartExecutor.submit { ... }` + `redisQuoteCache.warm(code)`
  - action=REMOVED → `subscriptionRouter.remove(userId, code)` + `redisQuoteCache.evict(code)` + `redisSourceTracker.remove(userId, code)`
  - 처리 성공 후 `ack.acknowledge()`
  - 예외 시 ack 호출 안 함 → 재처리 (idempotent 보장)

- [ ] `consumer/.../kafka/WatchlistChangedKafkaConfig.kt` — Bean 설정
  - `ConcurrentKafkaListenerContainerFactory` concurrency=1, ackMode=MANUAL_IMMEDIATE
  - `ConsumerFactory` enable-auto-commit=false, isolation-level=read_committed

- [ ] `consumer/src/main/resources/application.yml` — Kafka consumer config
  ```yaml
  spring.kafka:
    bootstrap-servers: ${MAGICJAR_KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: magicjar-consumer-watchlist-ingestion
      enable-auto-commit: false
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      properties.isolation.level: read_committed
    listener:
      ack-mode: MANUAL_IMMEDIATE
      concurrency: 1
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
  ```

#### C. SubscriptionRouter (slot 예산 + priority eviction + 호가 LRU)

- [ ] `consumer/.../ingestion/SubscriptionRouter.kt` — spec §3-3 정합 + ADR 0048 §2-2 §2-3
  - 의존: `WatchlistRepository`(domain port — V3 정합), `KisWsPort`(market-data 구현), `WatchlistPollingWorker`(자기 이웃)
  - public API:
    - `add(userId: String, code: String, priority: Int)` — slot 여유 검사 + 분배
    - `remove(userId: String, code: String)` — unsubscribe + polling pool 제거 + promotion 재계산
    - `recompute(userId: String)` — 부팅·세션 변경 시 priority 상위 N 일괄 결정
    - `onSessionChanged(prev: MarketSession, current: MarketSession)` — Closed 진입 시 unsubscribeAll, Regular 복귀 시 recompute
  - thread safety: synchronized 또는 ReentrantLock — Kafka listener concurrency=1이지만 부팅 reconcile + scheduled session change 동시 호출 가능

- [ ] `consumer/.../ingestion/WatchlistIngestionWorker.kt` — facade (spec §A 정합)
  - `@Component` + `@PostConstruct` 부팅 reconcile 호출
  - `SubscriptionRouter.recompute(userId)` (모든 사용자 forEach — single-tenant 1차 1명만)
  - `MarketSessionResolver.resolve()` 1분 cron — 변경 감지 시 `SubscriptionRouter.onSessionChanged`
  - `@PreDestroy` graceful shutdown — `KisWsPort.unsubscribe(allCodes)` + `WatchlistPollingWorker.stop()` (timeout 5s)

#### D. WatchlistPollingWorker (eviction + WS 끊김 fallback)

- [ ] `consumer/.../ingestion/WatchlistPollingWorker.kt` — spec §3-2 §7-2 정합
  - `@Component`
  - `private val pool: MutableSet<String> = ConcurrentHashMap.newKeySet()` (thread-safe)
  - public API: `add(code)`, `remove(code)`, `start()`, `stop()`, `running: Boolean`
  - `@Scheduled(fixedRate = 5000)` 5초 주기 — `running=true`일 때만 forEach pool → `KisRestPriceAdapter.fetchPrice(code)` → Redis write + Kafka publish
  - KisRateLimiter (PR-9b 또는 ADR 0037 placeholder — 1차 MVP는 RateLimiter 없이 Thread.sleep(100ms) 마진 OR 본격 구현은 ADR 0037 진입 시. **architect 결정: 1차 MVP는 단순 Thread.sleep — RateLimiter 의무는 ADR 0037 본격 도입 트랙(별도)에서. 본 PR-9는 KIS 호출 빈도가 풀 30 × 5s = 6 req/s ≤ 10 한도 정합으로 충분**)

#### E. Yahoo cold-start

- [ ] `consumer/.../infrastructure/yahoo/YahooCandleFetcher.kt` — spec §5-7 §5-8 정합
  - domain port `YahooCandleFetcher` 구현
  - WebClient 또는 RestClient (Spring 6.1+ — JDK 21 정합)
  - URL: `https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?interval={...}&range={...}`
  - `.KS` → `.KQ` 순차 시도 fallback (instrument market='UNKNOWN' 정합)
  - JSON 파싱 → `Candle` 리스트 (raw, source='YAHOO')
  - timestamp epoch second → `kotlinx.datetime.Instant.fromEpochSeconds`
  - HTTP 429·404 → 60s 대기 + 1회 재시도

- [ ] `consumer/.../ingestion/ColdStartExecutor.kt` (신규 — 별도 thread pool)
  - `Executors.newFixedThreadPool(2, ThreadFactory(name="cold-start-"))`
  - 7 Yahoo interval 직렬 + 3 KIS REST interval 직렬 (총 ≤ 30s)
  - audit row INSERT — "YAHOO_COLD_START_DONE" / "YAHOO_COLD_START_PARTIAL_FAIL" / "YAHOO_COLD_START_FAILED"

- [ ] `consumer/.../infrastructure/kis/KisRestCandleAdapter.kt` — spec §7-4
  - domain port `KisRestPort` 구현 (`fetchCandles(code, interval, count)`)
  - KIS REST `FHKST03010100` `inquire-daily-itemchartprice` (PR-7 KisAuthAdapter 재사용 — `getAccessToken(userId, "REAL")`)
  - 1wk·1mo·1y interval 매핑 (KIS `period_div` 파라미터 — sub-agent가 KIS open-trading-api 정확 매핑 사용 의무 — `feedback_kis_first_policy.md` 정합)

#### F. candle 영속 어댑터 (api 모듈)

- [ ] `api/.../persistence/CandleJpaEntity.kt` — V1 baseline `candle` 테이블 정합
  - 컬럼: id, code, interval (String), ts (Instant), openRaw·highRaw·lowRaw·closeRaw·openAdj·highAdj·lowAdj·closeAdj (BigDecimal nullable), volume (Long), source (String)
  - UQ `(code, interval, ts)` JPA `@Table(uniqueConstraints = ...)`

- [ ] `api/.../persistence/CandleJpaRepository.kt` — Spring Data JPA `interface`
  - `existsByCodeAndIntervalAndTs(...)` (idempotency 검증)
  - `countByCodeAndInterval(code, interval)`
  - **batch INSERT custom method** — `@Modifying @Query(nativeQuery = true)` `INSERT INTO candle ... ON CONFLICT (code, interval, ts) DO NOTHING` 또는 JPA `saveAll` + `@Query` native

- [ ] `api/.../persistence/CandleRepositoryAdapter.kt` — domain port `CandleRepository` 구현
  - `saveAll(candles)` → JPA `saveAll` + flush + return INSERTed count

**architect 결정**: candle JPA는 **api 모듈** 책임 (`api/src/main/kotlin/.../persistence/`). consumer는 도메인 port `CandleRepository` 의존 → consumer 자체 어댑터를 별도 정의 (api와 코드 중복 OK — 모듈 분리 정합 + 단순화). dispatch §3-1에 명시.

**선택 — consumer 자체 candle 어댑터**:
- [ ] `consumer/.../persistence/ConsumerCandleJpaEntity.kt` + `ConsumerCandleJpaRepository.kt` + `ConsumerCandleRepositoryAdapter.kt` (api 모듈 코드 복사 — 1차 단순화)

#### G. Redis 어댑터 신규

- [ ] `consumer/.../infrastructure/cache/RedisQuoteCacheAdapter.kt` — spec §7-1 §7-5
  - domain port `QuoteCachePort` 구현 (이미 PR-5a에 정의)
  - StringRedisTemplate + ObjectMapper (JSON)
  - put: TTL 7일
  - get: cache miss 시 null
  - evict: DELETE
  - warm: KIS REST 1회 fetch (`KisRestPriceAdapter`) → put

- [ ] `consumer/.../infrastructure/cache/RedisSourceTrackerAdapter.kt` — spec §3-5
  - 신규 도메인 port `SourceTracker` (domain port 신규 — add-only)
    ```kotlin
    interface SourceTracker {
        fun set(userId: String, code: String, source: String)
        fun get(userId: String, code: String): String?
        fun remove(userId: String, code: String)
        fun listForUser(userId: String): Map<String, String>
    }
    ```
  - StringRedisTemplate `opsForHash` — key `watchlist:source:{userId}` field `code` value `live|polling|idle`
  - TTL 24h

#### H. MarketSessionResolver 1차 stub

- [ ] `consumer/.../infrastructure/session/SimpleMarketSessionResolver.kt` — spec §8-2 정합
  - domain port `MarketSessionResolver` 구현
  - 시간 기반 — 토·일 Closed, 평일 09:00~15:30 Regular, 시간외는 1차 단순화 (Regular와 동일 처리)

#### I. 검증 게이트 통과
spec §11 함정 모두 인지 + DoD 1·2·3·6·7·8·9·19·20 (backend 영역) 통과.

#### J. smoke test 2건 (룰 4 정합)
- [ ] `consumer/src/test/kotlin/.../WatchlistChangedKafkaListenerTest.kt` — spec §12-1-1
- [ ] `consumer/src/test/kotlin/.../SubscriptionRouterTest.kt` — spec §12-1-2

#### K. (선택) PR-7 default priority 정정

- [ ] `api/.../service/WatchlistService.kt` 1줄 정정 — `request.priority ?: 0` → `request.priority ?: 100`
- **선택 사항** — orchestrator가 위임 시점에 결정. dispatch-spec §2-5 `WatchlistService.kt` 정정 + CHANGELOG slot row 1줄에 명시. 본 PR-9 정책상 의무 아님 (created_at tie-breaker로 충분 — spec §11-12).

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): SubscriptionPolicy + YahooCandleFetcher port + SourceTracker port (T-W2)` | `domain/.../market/port/*.kt` |
| 2 | `feat(api): CandleJpaEntity + Repository + Adapter (V1 candle 테이블 영속)` | `api/.../persistence/Candle*.kt` |
| 3 | `feat(consumer): CandleJpaEntity + Adapter (consumer 자체 모듈)` | `consumer/.../persistence/ConsumerCandle*.kt` |
| 4 | `feat(consumer): YahooCandleFetcher + KisRestCandleAdapter (cold-start 어댑터)` | `consumer/.../infrastructure/yahoo/`·`/kis/` |
| 5 | `feat(consumer): RedisQuoteCacheAdapter + RedisSourceTrackerAdapter` | `consumer/.../infrastructure/cache/*.kt` |
| 6 | `feat(consumer): SimpleMarketSessionResolver (1차 시간 기반 stub)` | `consumer/.../infrastructure/session/` |
| 7 | `feat(consumer): SubscriptionRouter + WatchlistPollingWorker + WatchlistIngestionWorker(facade)` | `consumer/.../ingestion/*.kt` |
| 8 | `feat(consumer): WatchlistChangedKafkaListener (watchlist.changed.v1 첫 consumer) + ColdStartExecutor` | `consumer/.../kafka/`·`/ingestion/ColdStartExecutor.kt` + application.yml |
| 9 | `chore(api): WatchlistService default priority 0→100 정정 (PR-9 spec §6-2 정합)` ★선택 | `api/.../service/WatchlistService.kt` 1줄 |
| 10 | `test(consumer): Watchlist Kafka listener + SubscriptionRouter smoke 2건` | test 파일 |
| 11 | `chore(changelog): PR-9a backend slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 2-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (consumer·domain·api) — Watchlist Ingestion: Kafka listener + SubscriptionRouter + Yahoo cold-start + KIS REST backfill + candle 영속 (T-W2)

## Summary
- PR-9a: T-W2 backend — WatchlistChangedKafkaListener(`watchlist.changed.v1` 첫 consumer) + SubscriptionRouter(slot 1+30+10 + priority eviction + 호가 LRU 토글) + WatchlistIngestionWorker(facade·부팅 reconcile) + WatchlistPollingWorker(eviction·WS 끊김 fallback) + Yahoo cold-start(7 interval × 1회 직렬 — YahooCandleFetcher 어댑터) + KIS REST 1wk·1mo·1y backfill(KisRestCandleAdapter) + Redis 어댑터 2건(QuoteCache·SourceTracker) + candle JPA 영속(api+consumer 자체) + SimpleMarketSessionResolver(1차 시간 기반 stub) + smoke 2건.
- ADR 0048(본 spec PR 발행) + ADR 0028·0014·0029·0019·0042·0045 정합. WS 자체 인입(KIS WebSocket bridge·체결가 H0STCNT0)은 PR-9b market-data가 동시 진행 (분배 §A 참조).
- 정합: `_workspace/specs/pr-9-w2-design.md` + `pr-9-dispatch-spec.md` + `pr-9-conflict-matrix.md`.

## 신규 ADR
없음 (본 PR-9a 자체) — ADR 0048 본 spec PR(architect)에서 발행됨.

## Scope
- domain: SubscriptionPolicy + YahooCandleFetcher + SourceTracker port + (선택) CandleRepository port
- api: CandleJpaEntity + Repository + Adapter
- consumer: 핵심 영역 — Kafka listener + ingestion(Router·PollingWorker·Worker·ColdStartExecutor) + infrastructure(Yahoo·KIS REST candle·Redis·session)
- batch: 변경 0
- frontend: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (T-S1 진입 시 또는 별도 묶음). 본 PR에 smoke 2건만 (Kafka listener + SubscriptionRouter eviction).

## 사용자 검증 방법
1. PR-9 spec PR 머지 + main 위
2. PR-9b market-data 머지 (KisWebSocketBridge 등)
3. `scripts/build-and-deploy.sh`
4. PR-7 UI에서 종목 등록 (예: 005930)
5. `docker logs magicjar-consumer-app | grep WatchlistChangedKafkaListener` → ADDED 수신 + add log
6. `docker logs magicjar-consumer-app | grep "Yahoo cold-start"` → 7 interval 호출 log
7. `psql -c "SELECT interval, count(*) FROM candle WHERE code='005930' GROUP BY interval"` → 7+ row
8. `redis-cli HGETALL watchlist:source:<userId>` → `005930 → live`
9. UI에서 종목 삭제 → unsubscribe log

## 정합 ADR
- 0048 (본 spec PR architect 발행) — KIS WS 41 slot + priority eviction + polling fallback
- 0028 — Watchlist Ingestion (1~5초 polling — 5초 LOCK 정합)
- 0014 — Fallback 3-tier (T1·T2 본 PR-9, T3은 T-MS1)
- 0029 — 당일 진행 중 캔들 Redis (candle:current·candle:today)
- 0019 — 캔들 저장 정책 (Yahoo 매트릭스 + DB 영속)
- 0042 — Position Bootstrap (H0STCNI0 1 slot reserve — 본 PR-9는 reserve만, listener는 T-O)
- 0045 — Graceful Shutdown (SIGTERM 시 unsubscribe + close)
- 0050 — CHANGELOG 슬롯 채번룰 (본 PR이 첫 적용 사이클 — slot row in-place edit)
```

### 2-5. backend-core 의존성·정합 의무

- **ADR 0048** — slot 분배 1+30+10 LOCK + priority eviction created_at DESC tie-breaker LOCK + 5초 polling LOCK
- **ADR 0028** — Watchlist Ingestion Worker = facade. 본 PR-9a가 facade 조립
- **ADR 0019** — Yahoo 매트릭스 + DB 영속. UQ `candle.code+interval+ts` 정합
- **ADR 0029** — candle:current·candle:today Redis 키 — **단 candle:current·candle:today 갱신 자체는 PR-9b market-data 책임 (KisWebSocketBridge.onMessage 핸들러)**. PR-9a는 **TickToMinuteAggregator** (1m closing → DB INSERT) 담당
- **ADR 0042 STAGE 0-4 — H0STCNI0 1 slot reserve**: SubscriptionPolicy 상수 `EXECUTION_NOTICE_RESERVED = 1`로 본 PR-9 reserve만. 실제 listener는 T-O
- **ADR 0050 슬롯 채번룰**: CHANGELOG slot row in-place edit (§5 참조)
- **룰 5 (AI 호출 자제)**: spring-ai·anthropic·openai import 0건
- **룰 4 (테스트 마지막 일괄)**: R3 일괄은 T-S1 또는 별도 묶음 — 본 PR-9a는 smoke 2건만

**KIS-first 의무**: KIS REST endpoint(`FHKST03010100` `inquire-daily-itemchartprice` — 1wk·1mo·1y backfill)는 KIS open-trading-api `examples_llm/.../inquire_daily_itemchartprice.py` 출처. tr_id·endpoint 자체 추론 0건. 분량 부담 시 `kis-code-assistant` MCP로 추가 검증 가능.

---

## §3. market-data 위임 (PR-9b)

**market-data agent 첫 출연 사이클** (사용자 컨텍스트 명시).

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | market-data (sonnet) |
| 브랜치 | `agent/market-data/pr-9-w2-market-data` |
| base | `main` (PR-7·PR-8 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+market-data@magicjar.local` |
| 작업 author name | `magicjar-market-data` |
| Task 파일 goal | `T-W2 market-data — KisWebSocketBridge(KisWsPort 구현, H0STCNT0 체결가 + approval_key + heartbeat + reconnect 지수 백오프) + KisRestPriceAdapter(FHKST01010100 inquire-price polling) + KisApprovalAdapter(approval_key 발급·캐시) + TickToMinuteAggregator(1m closing → DB INSERT) + smoke test 2건` |
| Task 파일 deliverables | `consumer/.../ws/KisWebSocketBridge.kt + KisWebSocketSession.kt, consumer/.../infrastructure/kis/KisRestPriceAdapter.kt + KisApprovalAdapter.kt, consumer/.../market/TickToMinuteAggregator.kt, consumer/.../ws/KisWsMessageParser.kt (PIPE 구분자), Kafka topic auto-create config (market.tick.v1·market.candle.minute.closed.v1), smoke test 2건` |
| 머지 정책 | rebase |
| 분량 | M~L |

### 3-2. 작업 범위 (Done 정의)

#### A. KIS WebSocket bridge (핵심)

- [ ] `consumer/.../ws/KisWebSocketBridge.kt` — domain port `KisWsPort` 구현 (PR-5a에 정의)
  - Spring Reactive WebSocket Client (`org.springframework.web.reactive.socket.client.WebSocketClient`) 또는 OkHttp WebSocket. **architect 권장 = Spring Reactive WebSocket** (Spring Boot 3.3 정합 + non-blocking)
  - URL: 실전 `wss://openapi.koreainvestment.com:9443/api/ws` / 모의 `wss://openapivts.koreainvestment.com:31000/ws` (KIS open-trading-api `examples_llm/.../websocket_*.py` 출처 — sub-agent가 정확 URL 확인 의무)
  - header `approval_key` (KisApprovalAdapter에서 받음)
  - public API:
    - `subscribe(codes: List<String>)` — KIS 표준 메시지 송신 — `{"header": {"approval_key": "...", "tr_type": "1"}, "body": {"input": {"tr_id": "H0STCNT0", "tr_key": "<code>"}}}`
    - `unsubscribe(codes: List<String>)` — `tr_type: "2"`
    - `subscribedCodes(): Set<String>` — internal state
  - onMessage handler — PIPE 구분자 파싱 → Redis write + Kafka publish (§3-2-D 참조)
  - heartbeat (PINGPONG) 자동 응답
  - reconnect 지수 백오프 1·2·5·10·30s — spec §3-4 정합
  - 10초 heartbeat 무신호 감지 — `Disposable` schedule
  - `@PreDestroy` graceful shutdown — `unsubscribe(allCodes)` + `close()` ≤ 5s

- [ ] `consumer/.../ws/KisWebSocketSession.kt` — 단일 connection 상태 관리 (선택)
  - subscribedCodes set
  - lastMessageAt timestamp
  - reconnectCount (back-off)

#### B. PIPE 구분자 파서

- [ ] `consumer/.../ws/KisWsMessageParser.kt` — KIS WS 메시지 파싱
  - 입력 — `0|H0STCNT0|001|005930^090012^60900^...` (위치는 `KIS open-trading-api` `websocket_*.py` 정확 인덱스)
  - 출력 — `TickEvent(code, price, volume, ts)` (도메인 모델)
  - 도메인 위치: `domain/.../market/TickEvent.kt` (add-only, **선택**: backend-core가 만들거나 또는 market-data가 만들어도 무관 — coordinate)

#### C. KIS REST polling adapter

- [ ] `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` — `FHKST01010100` `inquire-price`
  - WebClient (PR-7 KisAuthAdapter·KisBalanceAdapter 패턴 정합)
  - GET `/uapi/domestic-stock/v1/quotations/inquire-price?fid_cond_mrkt_div_code=J&fid_input_iscd={code}`
  - Headers: authorization (Bearer + access_token from KisAuthAdapter), appkey, appsecret, tr_id="FHKST01010100", custtype="P"
  - response output → `TickEvent(code, price, volume, ts=now)` 매핑
  - timeout 3s
  - 사용 위치 — backend-core의 `WatchlistPollingWorker.fetchPrice(code)` 호출

#### D. tick handler — Redis + Kafka publish

KisWebSocketBridge.onMessage (또는 별도 Handler):
1. PIPE 메시지 파싱 → `TickEvent`
2. Redis write — `quote:last:{code}` SET (TTL 7일) + `candle:current:{code}:1m` HMSET 갱신 + `candle:today:{code}:1d` HMSET 갱신
3. Kafka publish — topic `market.tick.v1`, key=code, value=JSON `{code, price, volume, ts, source: "live", stale: false}`

KisRestPriceAdapter는 raw fetch만 — Redis·Kafka write는 backend-core의 WatchlistPollingWorker가 호출. **결론**: PR-9b는 raw fetch까지, write는 PR-9a가 호출자 — sub-agent 사이 인터페이스는 `TickEvent` data class.

#### E. KIS approval_key 발급·캐시

- [ ] `consumer/.../infrastructure/kis/KisApprovalAdapter.kt` — spec §11-1 정합
  - `POST /oauth2/Approval` (별도 endpoint — `tokenP` 아님)
  - body: `{"grant_type": "client_credentials", "appkey": "...", "secretkey": "..."}`
  - response: `{"approval_key": "..."}`
  - Redis cache `kis:approval:{userId}` TTL 23h
  - 분산락 (ADR 0038 정합 — `kis:approval:lock:{userId}` SETNX 30s)
  - **선택**: 기존 `KisAuthAdapter`(PR-7)에 `getApprovalKey(userId)` 메서드 추가 (단일 어댑터). architect 권장 = 별도 `KisApprovalAdapter` (책임 분리 + KIS endpoint 다름)

#### F. TickToMinuteAggregator (1m closing → DB INSERT)

- [ ] `consumer/.../market/TickToMinuteAggregator.kt` — spec §7-1 [5] 정합
  - `@Component`
  - `@Scheduled(cron = "0 * * * * ?")` 매 분 0초
  - forEach KIS WS 구독 종목 — `Redis HGETALL candle:current:{code}:1m` → `Candle(interval='1m', source='KIS')` 영속 → DB INSERT (`CandleRepository.saveAll` — backend-core가 만든 port)
  - Kafka publish `market.candle.minute.closed.v1` — payload `{code, interval: "1m", ts, OHLCV}`
  - Redis `candle:current:{code}:1m` DEL
  - **architect 결정**: PR-9b가 TickToMinuteAggregator 책임 — KIS WS 핸들러와 연관성 + Redis·Kafka 흐름 일관

#### G. Kafka topic 자동 생성

- [ ] `consumer/.../config/KafkaTopicConfig.kt` — Bean 설정
  ```kotlin
  @Configuration
  class KafkaTopicConfig {
      @Bean fun marketTickTopic() = NewTopic("market.tick.v1", 1, 1.toShort())
          .configs(mapOf("retention.ms" to "3600000"))  // 1h
      @Bean fun marketCandleMinuteClosedTopic() = NewTopic("market.candle.minute.closed.v1", 1, 1.toShort())
          .configs(mapOf("retention.ms" to "86400000"))  // 1d
  }
  ```

#### H. 검증 게이트 통과
spec §11 함정 모두 인지 + DoD 1·3·4·5·6·10·11·12·15·16·18 (market-data 영역) 통과.

#### I. smoke test 2건 (룰 4 정합)
- [ ] `consumer/src/test/kotlin/.../KisWebSocketBridgeTest.kt` — spec §12-2-1 (raw 메시지 → Redis·Kafka write 검증)
- [ ] `consumer/src/test/kotlin/.../WatchlistPollingWorkerTest.kt` — spec §12-2-2 (5초 주기 fetch 검증) — **선택**: backend-core가 같은 클래스 테스트하면 중복 — 협의 후 **PR-9b가 KisWsMessageParserTest** + **PR-9a가 WatchlistPollingWorkerTest** 분담 권장

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): TickEvent 도메인 모델 (KIS WS 메시지 → 도메인 변환)` | `domain/.../market/TickEvent.kt` (선택 — backend-core와 협의) |
| 2 | `feat(consumer): KisApprovalAdapter (KIS WS approval_key 발급+Redis 분산락 ADR 0038)` | `consumer/.../infrastructure/kis/KisApprovalAdapter.kt` |
| 3 | `feat(consumer): KisRestPriceAdapter (FHKST01010100 inquire-price polling용)` | `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` |
| 4 | `feat(consumer): KisWsMessageParser (PIPE 구분자 → TickEvent)` | `consumer/.../ws/KisWsMessageParser.kt` |
| 5 | `feat(consumer): KisWebSocketBridge (KisWsPort 구현 + H0STCNT0 + heartbeat + reconnect)` | `consumer/.../ws/KisWebSocketBridge.kt + KisWebSocketSession.kt` |
| 6 | `feat(consumer): TickToMinuteAggregator (1m closing → DB INSERT + Kafka publish)` | `consumer/.../market/TickToMinuteAggregator.kt` |
| 7 | `feat(consumer): Kafka topic auto-create (market.tick.v1·market.candle.minute.closed.v1)` | `consumer/.../config/KafkaTopicConfig.kt` |
| 8 | `test(consumer): KIS WS bridge + parser smoke 2건` | test 파일 |
| 9 | `chore(changelog): PR-9b market-data slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (consumer) — KIS WebSocket bridge + REST polling adapter + 1m closing aggregator (T-W2)

## Summary
- PR-9b: T-W2 market-data — KisWebSocketBridge(KisWsPort 구현 — H0STCNT0 체결가 + approval_key + PINGPONG heartbeat + 지수 백오프 1·2·5·10·30s reconnect + 10s 무신호 감지 + graceful shutdown @PreDestroy) + KisApprovalAdapter(/oauth2/Approval + Redis 분산락 ADR 0038) + KisRestPriceAdapter(FHKST01010100 inquire-price polling adapter — backend-core WatchlistPollingWorker 호출용) + KisWsMessageParser(PIPE 구분자 → TickEvent) + TickToMinuteAggregator(@Scheduled 매 분 0초 → 1m DB INSERT + Kafka publish market.candle.minute.closed.v1) + Kafka topic auto-create + smoke 2건.
- ADR 0048(본 spec PR 발행) + ADR 0028·0014·0029·0038 정합. backend-core PR-9a와 인터페이스 — `TickEvent` 도메인 모델 + `KisWsPort` (domain port) + `KisRestPort.fetchPrice` (확장).
- 정합: `_workspace/specs/pr-9-w2-design.md` + `pr-9-dispatch-spec.md` + `pr-9-conflict-matrix.md`.

## 신규 ADR
없음 — ADR 0048 본 spec PR(architect)에서 발행됨.

## Scope
- domain: (선택) TickEvent 신규
- consumer: 핵심 영역 — ws(Bridge·Session·MessageParser) + infrastructure/kis(KisRestPriceAdapter·KisApprovalAdapter) + market(TickToMinuteAggregator) + config(KafkaTopicConfig)
- api·batch·frontend: 변경 0

## 사용자 검증 방법
1. PR-9 spec PR 머지 + main 위
2. PR-9a backend-core 머지 (Kafka listener·SubscriptionRouter·Yahoo cold-start)
3. `scripts/build-and-deploy.sh`
4. PR-7 UI에서 종목 등록 (예: 장중 005930)
5. `docker logs magicjar-consumer-app | grep KisWebSocketBridge` → "WS 연결 성공" + "subscribe 005930"
6. `kafka-console-consumer --topic market.tick.v1 --max-messages 5` → 5건 (장중)
7. 1분 후 `kafka-console-consumer --topic market.candle.minute.closed.v1 --max-messages 1` → 1건
8. `psql -c "SELECT count(*) FROM candle WHERE code='005930' AND interval='1m'"` → 1+ row
9. `redis-cli GET quote:last:005930` → JSON live source

## 정합 ADR
- 0048 (architect 발행) — KIS WS 41 slot
- 0028 — Watchlist Ingestion (1~5초 polling — 5초 LOCK)
- 0014 — Fallback 3-tier T1 (KIS WS) + T2(polling)
- 0029 — 당일 진행 중 캔들 Redis (candle:current·candle:today)
- 0038 — KIS OAuth + 분산락 (approval_key 정합)
- 0042 — Position Bootstrap (H0STCNI0 reserve)
- 0045 — Graceful Shutdown
- 0050 — CHANGELOG 슬롯 (slot row in-place edit)
```

### 3-5. market-data 의존성·정합 의무

- **ADR 0048** — KIS WS 41 slot 한도 인지 + reconnect 지수 백오프 LOCK
- **ADR 0028** — Watchlist Ingestion polling 1~5초 (5초 LOCK 정합)
- **ADR 0014** — T1 live = KIS WS, T2 cache = Redis quote:last (PR-9a write도 같은 키 — 일관성 확보)
- **ADR 0029** — candle:current·candle:today Redis 갱신 (KIS WS handler 책임)
- **ADR 0038** — approval_key Redis 분산락
- **ADR 0042 STAGE 0-4** — H0STCNI0 1 slot reserve (본 PR-9b는 sub 안 함, slot 예산만)
- **ADR 0045** — graceful shutdown SIGTERM 시 unsubscribe + close ≤ 5s
- **ADR 0050** — CHANGELOG slot row in-place edit
- **룰 5 (AI 호출 자제)**: spring-ai·anthropic·openai import 0건
- **룰 4 (테스트 마지막 일괄)**: smoke 2건만

**KIS-first 의무 (강조)**: KIS WS endpoint(URL·header·메시지 형식·PIPE 인덱스)는 **KIS open-trading-api** repo `examples_llm/domestic-stock/.../websocket_*.py` 정확 출처 사용 의무. 자체 추론 0건. `feedback_kis_first_policy.md` + `kis-code-assistant` MCP 적극 활용. 시스템 프롬프트 명시 — "사용자 메모리 KIS-first 정책 정합 — KIS MCP 적극 활용 + open-trading-api 레포 베이스".

---

## §4. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 4-1. domain port 시그니처 (LOCK)

| port | 시그니처 | 위치 |
|---|---|---|
| `KisWsPort` (PR-5a 기존) | `subscribe(codes)·unsubscribe(codes)·subscribedCodes()` | domain (변경 0 또는 add method) |
| `KisRestPort` 확장 | `fetchPrice(code): TickEvent` 추가 | domain |
| `SubscriptionPolicy` 신규 | `TOTAL_SLOTS = 41`·`PRICE_TICK_MAX = 30`·... | domain |
| `YahooCandleFetcher` 신규 | `fetch(ticker, interval, rangeDays): List<Candle>` | domain |
| `SourceTracker` 신규 | `set/get/remove/listForUser` | domain |
| `MarketSessionResolver` (PR-5a 기존) | `resolve(at): MarketSession` | domain (변경 0) |
| `CandleRepository` | `saveAll·countByCodeAndInterval` | domain (PR-5a 부재 시 add-only) |
| `QuoteCachePort` (PR-5a 기존) | `getLatestPrice·putLatestPrice` | domain (변경 0) |
| `TickEvent` data class 신규 | `code·price·volume·ts` | domain |

**누가 만드는가**:
- `SubscriptionPolicy`·`YahooCandleFetcher`·`SourceTracker`·`CandleRepository` → **PR-9a backend-core**
- `TickEvent`·`KisRestPort` 확장 → **PR-9b market-data** (또는 PR-9a 어느 쪽이든 — coordinate. **권장: PR-9b가 만든다 — KIS WS 메시지 변환 책임**)

### 4-2. Kafka topic 시그니처

| topic | producer | consumer | payload |
|---|---|---|---|
| `market.tick.v1` | consumer (KisWebSocketBridge·WatchlistPollingWorker) | (T-S1 후속) | `{code: String, price: BigDecimal, volume: Long, ts: Instant, source: "live"\|"polling", stale: Boolean}` |
| `market.candle.minute.closed.v1` | consumer (TickToMinuteAggregator) | (T-S1 후속) | `{code: String, interval: "1m", ts: Instant, openRaw·highRaw·lowRaw·closeRaw·volume}` |
| `watchlist.changed.v1` | api (PR-7) | **consumer (본 PR-9 — 첫)** | `{userId, code, action, occurredAt}` |

### 4-3. Redis 키 매트릭스

| 키 | TTL | 작성자 | 형식 |
|---|---|---|---|
| `quote:last:{code}` | 7일 | PR-9b WS handler · PR-9a polling worker | JSON `{price, volume, ts, source}` |
| `candle:current:{code}:1m` | 1m closing 시 evict | PR-9b WS handler | HASH (open·high·low·close·volume·startTs) |
| `candle:today:{code}:1d` | 장 마감 + 30분 | PR-9b WS handler | HASH (OHLCV) |
| `watchlist:source:{userId}` | 24h | PR-9a SubscriptionRouter | HASH (code → live\|polling\|idle) |
| `kis:approval:{userId}` | 23h | PR-9b KisApprovalAdapter | String approval_key |
| `kis:approval:lock:{userId}` | 30s SETNX | PR-9b KisApprovalAdapter | (분산락) |
| `kis:token:{userId}:{accountType}` (PR-7 기존) | 23h | PR-7 KisAuthAdapter | String access_token |

### 4-4. 예시 페이로드

#### KIS WS subscribe 메시지 (consumer → KIS)
```json
{
  "header": {"approval_key": "<from KisApprovalAdapter>", "tr_type": "1", "custtype": "P", "content-type": "utf-8"},
  "body": {"input": {"tr_id": "H0STCNT0", "tr_key": "005930"}}
}
```

#### KIS WS unsubscribe (tr_type "2")
```json
{
  "header": {"approval_key": "...", "tr_type": "2", ...},
  "body": {"input": {"tr_id": "H0STCNT0", "tr_key": "005930"}}
}
```

#### KIS WS 응답 (PIPE 구분자) — H0STCNT0
```
0|H0STCNT0|001|005930^090012^60900^...
```
- 첫 토큰 0 = 실시간 데이터 / 1 = 응답 / etc.
- 두 번째 H0STCNT0 = TR_ID
- 세 번째 001 = 구독 데이터 N건
- 네 번째 = `^` 구분 31개 필드 (KIS open-trading-api 정확 인덱스)

#### Kafka market.tick.v1 message
```json
{"code":"005930","price":"60900","volume":100,"ts":"2026-05-07T09:00:12.123Z","source":"live","stale":false}
```

#### Kafka market.candle.minute.closed.v1 message
```json
{"code":"005930","interval":"1m","ts":"2026-05-07T09:00:00.000Z","openRaw":"60800","highRaw":"61000","lowRaw":"60750","closeRaw":"60900","volume":12345}
```

### 4-5. SoT 변경 시 처리

본 spec(`pr-9-w2-design.md` + `pr-9-dispatch-spec.md` + `pr-9-conflict-matrix.md` + ADR 0048)이 머지된 후 양쪽 sub-agent 진행 중에 시그니처 변경 필요 시:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-9 작업 중엔 spec 변경 0건 의무 (사용자 본인 결정 외).

---

## §5. CHANGELOG 슬롯 채번룰 첫 적용 (ADR 0050 — 사용자 정정 핵심)

본 spec PR이 ADR 0050을 처음 적용. spec PR 머지 시점에 `CHANGELOG.md`에 다음 row 3건 prepend 의무:

### 5-1. 슬롯 row 정확 위치 — `CHANGELOG.md` 표 헤더(3행) 바로 다음 (4번째 row 위치)

`CHANGELOG.md` 현재 구조:
- Line 1: `# magicJar CHANGELOG`
- Line 2: (빈 줄)
- Line 3: `| 날짜 | 변경 내용 | 대상 | 사유 |`
- Line 4: `|------|----------|------|------|`
- Line 5+: 데이터 행 (최신이 위)

본 spec PR이 prepend할 위치 = **Line 5 (현재 첫 데이터 행) 위**.

### 5-2. 슬롯 row 3건 형식

```markdown
| 2026-05-07 | **docs(specs): PR-9 T-W2 설계 + sub-agent 분배 + 충돌 행렬 + ADR 0048 본문 발행 + CHANGELOG 슬롯 첫 적용** — `_workspace/specs/pr-9-w2-design.md` (slot 1+30+10·priority eviction·30s polling 전환·Yahoo 7 interval·MarketSession stub LOCK), `pr-9-dispatch-spec.md` (PR-9a backend-core·PR-9b market-data 분배 + 인터페이스 동결 + KIS WS PIPE 메시지 KIS-first 의무), `pr-9-conflict-matrix.md` (**완전 병렬 권장** — modify-shared 1건 application.yml은 PR-9a 단독 격리 권고, CHANGELOG는 ADR 0050 슬롯 채번룰 첫 적용으로 충돌 0). ADR 0048 본문 발행(`0048-kis-ws-41-subscription-limit.md`) — README §12 후속 예약 → §5 시장 데이터 카테고리 이동, master §10 인덱스 갱신. (architect) | docs/adr | PR-9 T-W2 진입 직전 설계 산출물 + ADR 0048 본문 + CHANGELOG 슬롯 첫 적용 |
| 2026-05-07 | **TBD: PR-9a T-W2 backend** (작성: backend-core) — TBD | api/consumer/domain | TBD |
| 2026-05-07 | **TBD: PR-9b T-W2 market-data** (작성: market-data) — TBD | consumer | TBD |
```

### 5-3. sub-agent in-place edit 의무

PR-9a backend-core (sub-agent):
1. main rebase (architect spec PR 머지 후)
2. `CHANGELOG.md` 열어 `(작성: backend-core)` 마커 row 1줄 검색
3. **그 row 1줄만** in-place edit — `TBD: PR-9a T-W2 backend (작성: backend-core) — TBD` → `feat(consumer): PR-9a T-W2 backend — WatchlistChangedKafkaListener(...) + SubscriptionRouter(...) + ... (backend-core)` (실제 산출물 한 줄 사유로 교체)
4. 사유 column도 `TBD` → `PR-9a T-W2 백엔드 (Kafka listener·SubscriptionRouter·Yahoo cold-start·candle 영속)` 등
5. **다른 row(architect spec row·market-data slot row) 절대 미수정** — 영역 침범 금지
6. commit + push + PR + 머지

PR-9b market-data (sub-agent):
1. main rebase
2. `(작성: market-data)` 마커 row 검색
3. `TBD: PR-9b T-W2 market-data (작성: market-data) — TBD` → `feat(consumer): PR-9b T-W2 market-data — KisWebSocketBridge(H0STCNT0 + approval_key + reconnect 지수 백오프) + KisRestPriceAdapter(FHKST01010100 polling) + TickToMinuteAggregator(1m closing → DB) + KisApprovalAdapter (market-data)` (실제 산출물 사유로 교체)
4. **다른 row 절대 미수정**
5. commit + push + PR + 머지

### 5-4. 일탈·fallback 처리 (ADR 0050 §2-4 정합)

sub-agent가 spec PR 슬롯 row 외 추가 변경 발생 시 (예: 추가 commit 1건):
- 별도 정정 commit으로 본인 row 갱신 OR 새 row append (add-only) — 본인 책임
- 그러면 add-only 충돌 발생 가능 — 둘째 머지자가 자체 rebase 해결

병렬 sub-agent 머지 순서:
- backend-core 먼저 머지 → backend-core row 채워짐 → market-data slot row 그대로 + market-data fillout 후 머지 → CHANGELOG 충돌 0 (ADR 0050 §3-2 정합)
- 동시 push 시점이라도 ADR 0050 in-place edit이라 git이 다른 line 변경으로 인식 → 충돌 0

### 5-5. orchestrator 운영 절차 (디스패치 시점에 sub-agent 프롬프트에 슬롯 위치 명시)

orchestrator가 backend-core·market-data 디스패치 시 다음 정확 위치 정보 전달:

> `CHANGELOG.md` Line ~6 (architect spec PR 머지 후 정확 line 확인 의무)에 본인 슬롯 row 1줄이 `TBD ... (작성: backend-core)` 또는 `(작성: market-data)` 형식으로 있다. 본인 row만 in-place edit. 형식 예시 = dispatch-spec §5-3.

---

## §6. 운영·실패 대응

### 6-1. backend-core가 PR-9a 분량 초과 인지 시

PR-9a를 다음 단위로 분할 가능 (orchestrator 사전 동의 필요):
- PR-9a-1: domain port + candle JPA + WatchlistChangedKafkaListener 골격
- PR-9a-2: SubscriptionRouter + WatchlistPollingWorker
- PR-9a-3: Yahoo cold-start + KIS REST backfill

market-data PR-9b는 PR-9a-1 완료 후 즉시 진행 가능 (도메인 port 의존만).

### 6-2. market-data가 KIS WS 정확 메시지 형식·필드 인덱스 불확실 시

- `kis-code-assistant` MCP로 `mcp__kis-code-assistant__search_domestic_stock_api` 또는 KIS open-trading-api repo `examples_llm/domestic-stock/quotations/inquire-realtime-conclusion/` 실 코드 참조
- architect 호출 → spec §11-2 정밀화 (필드 인덱스 명시 ADR 0048 §6 또는 본 spec)
- 자체 추론 0건 — KIS-first 정책

### 6-3. CI fail 시

backend-core·market-data 본인 영역 fail → 본인 PR에서 수정.

### 6-4. ADR 정합 누락 발견 시

직접 ADR 변경 금지 — architect 호출 → 별도 PR로 정정.

### 6-5. KIS WS endpoint URL·tr_id 정정 필요 시

architect 호출 → ADR 0048 §6 갱신 + spec §11 갱신.

### 6-6. PR-7 default priority 0→100 정정 필요 결정 시

backend-core가 본 PR-9a에서 1줄 정정 OR 별도 hotfix PR. orchestrator 결정.

### 6-7. PR-9b가 PR-9a 미머지 시 진행 가능?

가능 — KIS WS bridge·KisRestPriceAdapter는 backend-core 영역과 격리. domain port `TickEvent`·`KisRestPort` 확장은 PR-9b가 만들 수 있음. 단 backend-core가 같은 port 만들면 충돌 — coordinate.

**권장 순서**:
- Path A — backend-core·market-data 동시 시작, `TickEvent`는 market-data가 먼저 add-only. backend-core는 import만.
- Path B — backend-core 먼저 도메인 port + candle JPA 머지 → market-data 합류 (시간 단축 30%)

---

## §7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-9 sub-agent 분배 명세 v1 발행. PR-9a (backend-core) + PR-9b (market-data) 병렬 권장. 인터페이스 동결 — domain port 신규 5건(SubscriptionPolicy·YahooCandleFetcher·SourceTracker·CandleRepository·TickEvent) + Kafka topic 신규 2건(market.tick.v1·market.candle.minute.closed.v1) + Redis 키 매트릭스 7건 + KIS WS subscribe/unsubscribe 메시지 형식 + PIPE 구분자 응답 형식 (KIS open-trading-api 출처 의무). ADR 0050 첫 적용 — CHANGELOG slot row 3건 사전 발행 의무 §5에 명시. (architect, opus 4.7 1M) |
