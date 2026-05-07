# PR-9 T-W2 설계 가이드 (Watchlist Ingestion: KIS WS + polling fallback + Yahoo cold-start)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-9a (backend-core) · PR-9b (market-data) — 병렬 가능 (§A 충돌 행렬 참조)
- 진입 prerequisite: PR-5a·PR-5b·PR-6·PR-7·PR-8 모두 MERGED ✅. main HEAD = `c0c262e`. Watchlist DB row + REST 3 endpoint + watchlist.changed.v1 Kafka producer + Position Bootstrap consumer 부팅 흐름 + ADR 0049·0050·0048 슬롯 모두 갖춰진 상태.
- 정합 SoT:
  - **ADR 0048** KIS WS 41건 구독 한도 + 우선순위 정책 (본 PR 발행 — 1급 권위)
  - **ADR 0028** Watchlist 기반 Ingestion Worker (1~5초 polling 정책 — 본 PR이 본격 구현)
  - **ADR 0014** 시세 데이터 Fallback 3-tier (live → cache → last-day-close)
  - **ADR 0029** 당일 진행 중 캔들 Redis 캐시 (tick → candle:current → DB INSERT)
  - **ADR 0019** 캔들 저장 정책 (Yahoo 매트릭스 + DB 영속)
  - **ADR 0037** KIS Rate Limit 강제 (consumer 2/10 share)
  - **ADR 0038** KIS OAuth 24h 토큰 + Redis 분산락 (consumer KisAuthAdapter 재사용)
  - **ADR 0040** 관심종목 우선 UI 흐름 (Watchlist → Ingestion → SymbolDetail)
  - **ADR 0042** Position Bootstrap & Recovery (`H0STCNI0` 체결통보 stream 1 slot reserve)
  - **ADR 0045** Graceful Shutdown & In-flight (SIGTERM 시 KIS WS unsubscribe + close)
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (본 PR이 첫 적용 사이클)
  - `docs/architecture/master-architecture.md` v2 §A.2 · §3-2 · §6 (Kafka 토픽)
  - `docs/architecture/external-api-policy.md` §2-1 §2-2 §3 (KIS WS 41건·KIS Rate Limit·Yahoo 한도)
  - `docs/architecture/candle-data-policy-v2.md` §6 cold-start 흐름
  - `docs/architecture/development-rules.md` §1 룰 5건
  - PR-7 spec `_workspace/specs/pr-7-w1-design.md` §4-3 (Kafka payload 정합)
- 작업 원칙 (개발 룰북 정합):
  - 룰 2 — 단일 책임 (PR-9a = consumer + domain port + api Yahoo 어댑터, PR-9b = consumer KIS WS 어댑터 + REST polling 어댑터). **양쪽 모두 backend 영역이지만 책임 분리** (§A.2 sub-agent 분배 참조)
  - 룰 3 — API 빌딩 한 스텝씩 순차. **본 PR-9는 R2 라운드** (어댑터 + 영속 + Kafka consumer). R1(REST·UI)은 PR-7에서 끝남. R3(테스트 일괄)는 T-S1 또는 별도 묶음.
  - 룰 4 — 코드 먼저, 테스트 마지막 일괄 (§9 시나리오 골격 — sub-agent 본인 PR엔 smoke 1~2건만)
  - 룰 5 — AI 호출 0 (W1 트랙 — LLM provider import 절대 금지)

---

## §1. T-W2 한 줄 목표 + DoD

### 1-1. 목표

> **PR-7에서 사용자가 등록한 Watchlist 종목들을 magicJar consumer가 자동으로 시세 수집하기 시작한다. KIS WebSocket(`H0STCNT0` 체결가)으로 실시간 tick을 받고, 끊김 시 KIS REST(`FHKST01010100` `inquire-price`) 5초 polling으로 자동 전환한다. 신규 종목 등록 시 Yahoo Finance에서 7 interval(1m·5m·15m·30m·90m·1h·1d) 분봉을 cold-start fetch하여 DB candle 테이블에 영속한다. 41 stream slot 한도(ADR 0048)가 차면 priority 낮은 종목은 polling으로 자동 demotion되고 UI 배지(후속 T-S1)에서 사용자 인지 가능하다. Watchlist 변경(`watchlist.changed.v1` Kafka)에 즉시 반응한다 (PR-7이 producer, 본 PR-9가 첫 consumer).**

### 1-2. DoD (Definition of Done) — PR-9 전체 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | KIS WebSocket bridge 부팅 | `docker logs magicjar-consumer-app | grep KisWebSocketBridge` | "KIS WS 연결 성공 — approval_key 발급 완료" + "구독 종목 N건" log |
| 2 | watchlist.changed.v1 ADDED 수신 | PR-7 UI에서 종목 등록 → consumer log | `WatchlistChangedKafkaListener` 수신 로그 + `SubscriptionRouter.add(code=...)` log |
| 3 | KIS WS 구독 시작 | 위 등록 직후 | `H0STCNT0` subscribe 메시지 송신 log + KIS 응답 OK log |
| 4 | tick 수신 → Redis 갱신 | 장중 등록 시 | `redis-cli GET quote:last:005930` → `{"price":"...","ts":"...","source":"live"}` |
| 5 | tick 수신 → Kafka publish | 장중 등록 시 | `kafka-console-consumer --topic market.tick.v1 --max-messages 5` → 5건 |
| 6 | 1m closing → DB INSERT | 1분 closing 시 | `psql -c "SELECT count(*) FROM candle WHERE code='005930' AND interval='1m' AND ts > NOW() - INTERVAL '5 minutes'"` → ≥ 1 |
| 7 | Yahoo cold-start 7 interval | 새 종목 등록 직후 ≤ 30초 | `psql -c "SELECT interval, count(*) FROM candle WHERE code='035720' GROUP BY interval"` → 7 interval row 존재 (1m·5m·15m·30m·90m·1h·1d) |
| 8 | KIS REST 1d/1wk/1mo/1y backfill | (위 동시) | `psql -c "SELECT interval, count(*) FROM candle WHERE code='035720' AND interval IN ('1d','1wk','1mo','1y')"` → 4 interval row 존재 |
| 9 | watchlist.changed.v1 REMOVED 수신 | UI에서 종목 삭제 → consumer log | `unsubscribe(code)` log + Redis `quote:last:{code}` evict log |
| 10 | WS 끊김 시 polling 자동 전환 | 사용자가 docker compose stop infra/redis 30초(KIS WS heartbeat 10s 무신호 시뮬) — **architect note**: 직접 KIS WS 끊김 재현은 어려움. 통합 검증은 사용자 환경에서 5분 자연 끊김 관찰 또는 단위 테스트 fixture로 검증 | `WatchlistPollingWorker.start()` log + 5초 주기 KIS REST 호출 log |
| 11 | polling fallback Redis 갱신 | 위 polling 가동 시 | `redis-cli GET quote:last:005930` → `source: "polling"` 또는 timestamp 5초 이내 갱신 |
| 12 | WS 복구 시 polling 자동 중단 | infra 복구 후 | `WatchlistPollingWorker.stop()` log + WS 재구독 log |
| 13 | priority eviction (41+ 종목) | 사용자가 31번째 종목 등록 (priority=99 default 100보다 낮음) | `SubscriptionRouter`가 priority 100 중 1개 unsubscribe + 새 종목 subscribe log |
| 14 | eviction된 종목 polling 진입 | 위 시나리오 후 | eviction된 종목의 polling 활성 log + 5초 주기 quote 갱신 |
| 15 | Closed session 진입 시 unsubscribe | 영업일 종료 5분 후 또는 토요일 부팅 | `MarketSessionResolver.resolve()=Closed` 인지 + WS close log + slot 회수 |
| 16 | Closed → Regular 전이 시 재구독 | 다음 영업일 09:00 또는 부팅 | 자동 reconnect + priority 상위 N 종목 재구독 log |
| 17 | Yahoo 실패 fallback | 잘못된 ticker(예: "999999") 등록 시 | Yahoo 7회 시도 모두 fail → DB INSERT 0건 + audit row "YAHOO_COLD_START_FAILED" + KIS WS 구독은 정상 (사용자가 차트 진입 시 "데이터 수집 중" 표시 — 본 PR-9 비범위) |
| 18 | Graceful shutdown (SIGTERM) | `docker stop magicjar-consumer-app` | `WatchlistIngestionWorker.shutdown()` 호출 → KIS WS unsubscribe(allCodes) → close 5초 이내 + polling worker stop log |
| 19 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer,batch}/src/main` | 0건 |
| 20 | KIS Rate Limit 통과 (consumer 2/10) | polling 풀 30 × 5s = 6 req/s 검증 | `KisRateLimiter` acquire log 6 req/s ≤ 10 정합 + EGW00201 0건 |

### 1-3. 비즈니스 가치

- magicJar 자동매매 본체의 **시세 인입 파이프라인 본격 가동** — 모든 시그널·룰·차트의 prerequisite
- 사용자 Watchlist 등록 → 즉시 시세 수집 → 1순위 진입점 흐름 완성 (ADR 0040 §2-2 §2-4)
- KIS WS 41 한도 안에서 자연 운영 + 한도 초과 시 polling fallback으로 사용자 풀 size 무제한 확장 (ADR 0048 §2-2)
- Yahoo cold-start으로 차트 즉시 표시 (T-S1 진입 prerequisite)
- WS 끊김·휴장·재기동 모두 graceful — 자동매매 본체 운영 안정성 (ADR 0014·0045 정합)

---

## §2. ADR 0048·0028·0014·0029 준수 항목 체크리스트

본 PR-9가 다중 ADR을 동시 구현하므로, ADR별 의무 항목을 체크리스트로 분리.

### 2-1. ADR 0048 (본 PR 발행) — 41 slot 정책

| ADR 0048 §위치 | 결정 사항 | PR-9 구현 위치 | 상태 |
|---|---|---|---|
| §2-1 slot 예산 (1 + 30 + 10) | `H0STCNI0` 1 + `H0STCNT0` 30 + `H0STASP0` 10 | `consumer/.../ingestion/SubscriptionRouter.kt` slot 상수 + `domain/.../market/port/SubscriptionPolicy.kt` 분배 함수 | **PR-9a 핵심** |
| §2-2 41+1번째 등록 | DB 허용 + priority eviction + polling | `SubscriptionRouter.add` + `WatchlistPollingWorker` | **PR-9a + PR-9b 핵심** |
| §2-3 priority eviction | `priority ASC, created_at DESC` 정렬 | `WatchlistJpaRepository.findByUserIdOrderByPriorityAscCreatedAtDesc` (V3 PR-7에 이미 존재) | **PR-9a 정합** |
| §2-4 WS 끊김 + 30s 재연결 + polling | 지수 백오프 1·2·5·10·30s + polling 5초 | `KisWebSocketBridge` reconnect + `WatchlistPollingWorker` | **PR-9b 핵심** |
| §2-4-D graceful shutdown | SIGTERM 시 unsubscribe + close ≤ 5s | `KisWebSocketBridge.@PreDestroy` | **PR-9b 핵심** |
| §2-5 Closed session unsubscribe | session=Closed 진입 5분 grace + close | `MarketSessionGate.onSessionChanged` listener | **PR-9b 핵심** (단 정적 캘린더 시드는 1차 단순화 — `MarketSessionResolver` stub OK 또는 시드 1주일분만) |
| §2-6 watchlist.changed.v1 매트릭스 | ADDED/REMOVED × slot 여유/가득 4 case | `WatchlistChangedKafkaListener` | **PR-9a 핵심** |
| §2-7 Yahoo cold-start 7 interval | 1m·5m·15m·30m·90m·1h·1d 1회 fetch | `api/.../adapter/yahoo/YahooFinanceClient` + `WatchlistChangedKafkaListener` 호출 | **PR-9a 핵심** |
| §3-1 LLM 0 검증 | 룰 5 정합 | (자동 검증) | **PR-9a + PR-9b 모두** |

### 2-2. ADR 0028 (Watchlist Ingestion Worker)

| ADR 0028 §위치 | 결정 사항 | PR-9 구현 위치 |
|---|---|---|
| §2-1 위치 `consumer/.../ingestion/WatchlistIngestionWorker.kt` | facade — `KisWebSocketBridge`·`WatchlistPollingWorker`·`SubscriptionRouter` 통합 | **PR-9a 핵심 (조립)** |
| §2-1 polling 1~5초 | **5초 lock** (ADR 0048 §2-4-C — Rate Limiter 마진) | `WatchlistPollingWorker` |
| §2-1 풀 변경 즉시 sub/unsub | `watchlist.changed.v1` listener | **PR-9a 핵심** |
| §2-1 Yahoo cold-start 매트릭스 | ADR 0019 + 본 spec §5 | **PR-9a 핵심** |
| §2-3 Watchlist 변경 흐름 | spec §4 데이터 흐름 LOCK | **양쪽 정합** |

### 2-3. ADR 0014 (Fallback 3-tier)

| ADR 0014 §위치 | 결정 사항 | PR-9 구현 |
|---|---|---|
| §2 T1 live | KIS WS tick — `KisWebSocketBridge` | **PR-9b 핵심** |
| §2 T2 cache | Redis `quote:last:{code}` (TTL 7일) | **PR-9a + PR-9b** (양쪽 모두 write) |
| §2 T3 last-day-close | `quote:lastbusinessday:{code}:{yyyyMMdd}` (TTL 14일) | **본 PR-9 비범위** — T-MS1 진입 시. 본 PR-9는 T2까지 |
| §2 stale 표시 | `payload source: "live"\|"polling"` + `stale: bool` | `market.tick.v1` payload 확장 |

### 2-4. ADR 0029 (당일 진행 중 캔들 Redis)

| ADR 0029 §위치 | 결정 사항 | PR-9 구현 |
|---|---|---|
| §2-1 candle:current:{code}:1m | tick 도달마다 갱신 | `consumer/.../market/TickToMinuteAggregator.kt` (신규) |
| §2-1 candle:today:{code}:1d | tick 도달마다 갱신 | (동일) |
| §2-3 1m closing → DB INSERT | 매 분봉 closing | `TickToMinuteAggregator` Spring `@Scheduled` 또는 manual cron |
| §2-3 16:00 DailyCloseFinalizer | 장 마감 후 1d 영속 | **본 PR-9 비범위** — T-MS1 진입 시. 본 PR-9는 1m closing만 |
| §2-5 STOMP fanout | `/topic/market.candle.minute.{code}` | **본 PR-9 비범위** — T-S1 진입 시 (SymbolDetail 차트 표시) |

### 2-5. 룰 5 (AI 호출 0)

- `grep -rE "spring-ai|anthropic|openai" {api,consumer,batch}/src/main` → 0건 (CI 게이트)

---

## §3. 구독·polling 책임 경계 LOCK (핵심 결정)

본 절이 PR-9의 **가장 중요한 결정**. ADR 0048 본문 + 본 spec이 양쪽 sub-agent 인터페이스 동결.

### 3-1. 매트릭스 — 어느 종목·어느 시점에 KIS WS vs polling

| 종목 priority | session | slot 여유 | 동작 source |
|---|---|---|---|
| 0~99 (사용자 명시) | Regular | 여유 | **KIS WS `H0STCNT0`** (실시간) |
| 0~99 | Regular | slot 가득 | priority 비교 → eviction 후 WS 또는 polling |
| 100 (default) | Regular | 여유 | **KIS WS `H0STCNT0`** |
| 100 | Regular | slot 가득 | **REST polling 5s** (eviction 후보) |
| ALL | PreMarket·PostMarket | — | **REST polling 30s** (시간외 KIS WS 미지원) |
| ALL | Closed | — | **idle** (slot 회수, polling 0) — last-day-close는 ADR 0014 T3 (T-MS1) |

### 3-2. polling 주기 (5초 lock 사유)

- KIS Rate Limit consumer share = 2/10 req/s (ADR 0037)
- 풀 30 종목 × 5s = 6 req/s ≤ 2/10 share. **단 share 분할 정책은 사용자 path 우선이라 consumer가 6 req/s 사용해도 OK** — 토큰 버킷 분산 모드 사용 시 자연 한도. 1차 MVP는 share 분할(consumer 2)이 아닌 **전역 10 한도 안에서 자연 분배** (api 사용자 path 0 req/s가 대부분 — 사용자 화면 진입 순간만 폭증)
- **결정**: polling 주기 **5초 fixed** (1차 MVP — 풀 size ≤ 30 정합). post-MVP 풀 100+ 시 동적 (`max(5s, ceil(N/2))`) — ADR 0048 §3-4 trade-off 정합

### 3-3. WS vs polling 책임 boundary

```
SubscriptionRouter (consumer)
  ├─ 입력 — Watchlist 변경 이벤트 + KIS WS 끊김 신호 + Session 변경 신호
  ├─ 의사결정 — slot 여유·priority·session 매트릭스
  └─ 출력 ───┬─ KisWsPort.subscribe(codes)    ─→ KisWebSocketBridge (실 WS handler)
             └─ pollingPool.add(codes)         ─→ WatchlistPollingWorker (실 REST 호출)
```

**SubscriptionRouter는 stateless decision** — slot·priority 계산은 매번 `WatchlistRepository.findByUserIdOrderByPriority` + 현재 subscribe 상태(`KisWsPort.subscribedCodes()`)로 재계산. in-memory cache 0 (단순화 + 정합 보장).

### 3-4. WS 끊김 → polling 자동 전환·복구 정책 LOCK

ADR 0048 §2-4 정합:

```
[KIS WS 정상]
   │ (10초 heartbeat 무신호 OR onClose OR 5분 데이터 0)
   ▼
[KIS WS 재연결 시도] — 지수 백오프 1·2·5·10·30s × N회
   │
   ├─ 30초 안에 성공 → polling 미진입 (heartbeat 회복으로 충분)
   │
   └─ 30초 초과 → polling 활성 (모든 Watchlist 종목, 5s 주기)
                  │
                  ├─ 재연결 백그라운드 계속 시도 (30s 간격)
                  └─ 재연결 성공 → polling stop + WS 재구독 + cache 정합 검증
```

**6회 연속 실패 시 알림** — 본 PR-9는 Kafka topic `kis.ws.disconnected.v1` publish 보류 (T-NOTIFY/W3 진입 시). MVP는 ERROR 로그만.

### 3-5. 종목별 source 추적 (Redis state)

```
Redis HASH: watchlist:source:{userId}
  field=005930 → "live"      (KIS WS 구독 중)
  field=035720 → "polling"   (eviction 후 polling 중)
  field=000660 → "idle"      (Closed session)
TTL: 24h (매 변경 시 갱신)
```

**용도**: UI WatchlistRow 배지 (T-S1 후속), audit·monitoring, debugging.

---

## §4. watchlist.changed.v1 → 구독 변경 흐름 LOCK

PR-7 spec §4-3 payload 정합:
```json
{ "userId": "...", "code": "005930", "action": "ADDED" | "REMOVED", "occurredAt": "2026-05-07T10:23:45.123Z" }
```

### 4-1. consumer Kafka listener 위치·설정

| 항목 | 명세 |
|---|---|
| **위치** | `consumer/.../kafka/WatchlistChangedKafkaListener.kt` |
| **topic** | `watchlist.changed.v1` (PR-7 producer) |
| **group_id** | `magicjar-consumer-watchlist-ingestion` |
| **auto.offset.reset** | `latest` (1차 MVP — 옛 등록 이벤트 재처리 불요. Watchlist 영속 데이터로 부팅 시 reconcile하므로 OK) |
| **enable.auto.commit** | `false` — manual commit (ADR 0045 graceful shutdown 정합) |
| **isolation.level** | `read_committed` |
| **concurrency** | 1 (single-thread — slot router race condition 회피) |

### 4-2. ADDED 동작 매트릭스

```kotlin
fun onWatchlistAdded(userId: String, code: String) {
    // 1. DB candle 부재 검증 → Yahoo cold-start (idempotent)
    val needsColdStart = candleRepository.countByCodeAndIntervalIn(code, COLD_START_INTERVALS) == 0L
    if (needsColdStart) {
        coldStartExecutor.submit { yahooColdStart(code) + kisRestColdStart(code) }
    }

    // 2. SubscriptionRouter에 위임
    subscriptionRouter.add(userId, code, priority = ?)
    //    → slot 여유: KisWsPort.subscribe([code])
    //    → slot 가득 + priority 더 낮음: WatchlistPollingWorker.add(code)
    //    → slot 가득 + priority 더 높음: 기존 priority 100 종목 1건 unsubscribe → WS subscribe(code)

    // 3. Redis quote:last warm (KIS REST 1회 — 첫 가격 즉시 표시 위해)
    redisQuoteCache.warm(code)

    // 4. Redis watchlist:source:{userId} field 업데이트
    redisSourceTracker.set(userId, code, currentSource)
}
```

### 4-3. REMOVED 동작 매트릭스

```kotlin
fun onWatchlistRemoved(userId: String, code: String) {
    // 1. SubscriptionRouter에 위임
    subscriptionRouter.remove(userId, code)
    //    → WS 구독 중: KisWsPort.unsubscribe([code])
    //    → polling 중: WatchlistPollingWorker.remove(code)
    //    → 다른 priority 100 polling 종목이 있으면 promotion (slot 회수 → 재 sub)

    // 2. Redis quote:last:{code} evict (TTL 0 OR DELETE) — 단 last tick은 14일 TTL 보존 권장 (ADR 0014 T3 cold cache)
    //    1차 MVP: 즉시 DELETE (단순화)

    // 3. Redis watchlist:source:{userId} field DELETE

    // 4. DB candle row, position row 보존 (사용자가 보유 중일 수 있음)
}
```

### 4-4. Idempotency

같은 ADDED 메시지 2회 수신 시:
- candle 영속은 idempotent (UQ `candle.code+interval+ts`)
- `KisWsPort.subscribe([code])` — 이미 구독 중이면 무시 (KIS 측 idempotent — 재구독 OK)
- Redis warm — 같은 키 set이라 무관

같은 REMOVED 메시지 2회 수신 시:
- 두 번째 시도는 `subscriptionRouter.remove` 내부에서 "이미 미구독" 무시

**at-least-once 보장 (Kafka manual commit)** — 모든 동작이 idempotent.

### 4-5. 부팅 시 Watchlist reconcile (Bootstrap)

consumer 부팅 직후 `WatchlistIngestionWorker.@PostConstruct`에서:
1. `WatchlistRepository.findAllActive()` — 모든 사용자 모든 watchlist row 조회
2. priority 정렬 후 상위 N개 — `KisWsPort.subscribe(codes)` 일괄 호출
3. 나머지 — polling 활성

**Kafka topic을 latest reset로 두는 것의 정합성**: 부팅 시 reconcile으로 missed 이벤트 보강.

### 4-6. KafkaListenerContainerFactory 설정

```yaml
spring.kafka.consumer:
  bootstrap-servers: ${MAGICJAR_KAFKA_BOOTSTRAP:localhost:9092}
  group-id: magicjar-consumer-watchlist-ingestion
  enable-auto-commit: false
  auto-offset-reset: latest
  key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  isolation-level: read_committed
spring.kafka.listener:
  ack-mode: MANUAL_IMMEDIATE
  concurrency: 1
```

---

## §5. Yahoo cold-start LOCK

ADR 0019 + ADR 0048 §2-7 정합. 본 spec에서 LOCK.

### 5-1. 트리거

| 트리거 | 시점 | 호출 위치 |
|---|---|---|
| `watchlist.changed.v1` ADDED 수신 | 즉시 (idempotent — DB candle 부재 시에만) | `WatchlistChangedKafkaListener.onAdded()` |
| consumer 부팅 reconcile | 부팅 시 — DB candle 부재 종목 자동 fetch | `WatchlistIngestionWorker.@PostConstruct` |
| 사용자 수동 trigger (디버그) | (선택) — `POST /api/candles/coldstart/{code}` | **본 PR-9 비범위** — T-S1 또는 후속 |

### 5-2. 범위 매트릭스

| Source | Interval | 범위 | 단일 호출 결과 row 수 (대략) |
|---|---|---|---|
| Yahoo | 1m | 7일 | ~ 2,700 (390 분 × 7일 영업일) |
| Yahoo | 5m | 60일 | ~ 4,700 (78 × 60) |
| Yahoo | 15m | 60일 | ~ 1,560 |
| Yahoo | 30m | 60일 | ~ 780 |
| Yahoo | 90m | 60일 | ~ 260 |
| Yahoo | 1h | 730일 | ~ 4,700 |
| Yahoo | 1d | Max (~10년) | ~ 2,500 |
| **Yahoo 소계** | — | — | **~ 17,000 row / 종목** |
| KIS REST | 1d | (대안) | (Yahoo 1d로 충당. KIS REST 1d는 보강용 — 본 PR-9 1차에서는 Yahoo만) |
| KIS REST | 1wk | Max | ~ 520 |
| KIS REST | 1mo | Max | ~ 120 |
| KIS REST | 1y | Max | ~ 10 |
| **KIS REST 소계** | — | — | **~ 650 row / 종목** |
| **합계** | — | — | **~ 17,650 row / 종목** |

**디스크 부담 (universe 30 종목)** — ~ 530K row × 80 byte ≈ **42 MB / candle 테이블 — 8GB host 한도 안 (ADR 0019 §4 정합)**.

### 5-3. 호출 순서 (timeline)

```
T+0ms:    watchlist.changed.v1 ADDED 수신
T+10ms:   KisWsPort.subscribe([code]) — 즉시 시세 수집 시작 (병렬)
T+10ms:   coldStartExecutor.submit { ... }  (별도 thread)
T+0~5s:   Yahoo 1m fetch (~2700 row) → DB INSERT (batch insertOrIgnore)
T+5~10s:  Yahoo 5m fetch + INSERT
T+10~12s: Yahoo 15m·30m·90m fetch (병렬 가능 — 단순화 위해 직렬)
T+12~17s: Yahoo 1h fetch + INSERT (4700 row)
T+17~20s: Yahoo 1d fetch + INSERT
T+20~25s: KIS REST 1wk·1mo·1y fetch + INSERT (Rate Limiter 통과)
T+25s~ :  cold-start 완료 → audit row INSERT "YAHOO_COLD_START_DONE"
```

**총 ≤ 30s** 기대. 사용자 등록 응답(REST 201)은 즉시 (Kafka publish 후) — cold-start는 백그라운드.

### 5-4. 실패 fallback 정책

| 실패 유형 | 동작 |
|---|---|
| Yahoo 1회 fetch HTTP 429·404·timeout | 60s 대기 + 1회 재시도 (external-api-policy.md §3-2 정합) |
| Yahoo 2회 모두 실패 (해당 interval만) | DB INSERT 0건 + WARN log + audit row "YAHOO_COLD_START_PARTIAL_FAIL" + interval 명시. 다른 interval은 계속 진행 |
| Yahoo 7 interval 모두 실패 | audit row "YAHOO_COLD_START_FAILED" + 사용자 등록 자체는 성공 유지 (KIS WS 구독은 정상). post-MVP daily backfill cron이 보강 |
| KIS REST 1wk·1mo·1y 실패 | Yahoo 1d로 충분 (사용자 차트는 1d까지 표시 가능) — log WARN |
| 잘못된 ticker (예: "999999") | Yahoo·KIS 모두 빈 응답 → 위 "7 interval 모두 실패" 동작 |

### 5-5. polling 전환 timing

cold-start 진행 중에도 KIS WS 구독은 즉시 시작. cold-start 완료가 차트 표시 prerequisite **아님** — 당일 분봉(1m)은 KIS WS tick으로 실시간 갱신 (ADR 0029).

### 5-6. Yahoo 어댑터 위치 결정

**architect 결정**: `api/.../adapter/yahoo/YahooFinanceClient.kt` — **api 모듈** (consumer 모듈 아님).

**사유**:
- `external-api-policy.md` §3-2 — "호출 위치: api `/api/backfill/yahoo` 단일 진입점"
- consumer가 api 모듈 클래스 직접 import 불가 (모듈 분리 — ADR 0004) — 대신 **REST 호출 패턴** 또는 **domain port 분리**
- **architect 결정**: `domain/.../market/port/YahooCandleFetcher.kt` 신규 port + api 모듈 어댑터(`YahooFinanceClient`) + consumer 모듈 어댑터(같은 port를 별도 구현하거나 또는 consumer가 api endpoint REST 호출)
- **단순화 결정**: consumer 모듈에 자체 `YahooCandleFetcher` 구현 (api 어댑터와 코드 중복 허용 — 1차 MVP). 이유 — REST 모듈 간 호출은 latency·complexity 증가. consumer가 직접 호출이 단순.
- **위치**: `consumer/.../infrastructure/yahoo/YahooCandleFetcher.kt`
- 단 **port는 domain에 분리** — `domain/.../market/port/YahooCandleFetcher.kt`

**future**: T-S1 진입 시 api에서 사용자 차트 직접 호출 시 api 모듈에도 같은 port 어댑터 추가 (코드 중복 OK).

### 5-7. Yahoo v8 chart endpoint 명세

```
GET https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?interval={...}&range={...}
ticker = "005930.KS" (KOSPI) 또는 "005930.KQ" (KOSDAQ)
```

**ticker suffix 결정 (KOSPI vs KOSDAQ)**: instrument 테이블 `market` 컬럼 — 단 PR-7 옵션 C로 stub row의 market='UNKNOWN'. **fallback**: `.KS` 시도 → 빈 응답이면 `.KQ` 시도. 둘 다 빈 응답이면 잘못된 ticker로 audit. T-IM1 진입 시 정확화.

**응답 매핑**:
```json
{
  "chart": {
    "result": [{
      "meta": { "symbol": "005930.KS", "instrumentType": "EQUITY" },
      "timestamp": [1672531200, 1672531260, ...],
      "indicators": {
        "quote": [{
          "open":  [60900.0, ...],
          "high":  [61000.0, ...],
          "low":   [60800.0, ...],
          "close": [60950.0, ...],
          "volume":[123456, ...]
        }]
      }
    }]
  }
}
```

→ Kotlin 매핑: timestamp + quote.open/high/low/close/volume → `Candle` (raw 컬럼 채움, adj 컬럼 null — T-CA1에서 보강).

### 5-8. INSERT 정책

```sql
INSERT INTO candle (code, interval, ts, open_raw, high_raw, low_raw, close_raw, volume, source)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'YAHOO')
ON CONFLICT (code, interval, ts) DO NOTHING
```

UQ `uq_candle_code_interval_ts` 기반 idempotency. 재실행 시 중복 INSERT 0.

---

## §6. 41건 한도 우선순위 정책 LOCK

ADR 0048 §2-1 §2-2 §2-3 정합. 본 spec에서 운영 매트릭스 LOCK.

### 6-1. priority 컬럼 (V1 baseline) 사용

```sql
CREATE TABLE watchlist (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    code VARCHAR(8) NOT NULL,
    priority INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ...
    UNIQUE (user_id, code)
);
```

PR-7 V3 인덱스 `idx_watchlist_user_priority (user_id, priority, created_at)` 활용. **본 PR-9 V4 마이그레이션 0건** — V1·V3 인덱스로 충분.

### 6-2. priority 의미·범위

| priority | 의미 | 사용 |
|---|---|---|
| 0 | 최상위 (사용자 명시) | `POST /api/watchlists -d '{"code":"...", "priority":0}'` (PR-7 spec §4-1-2 정합) |
| 1~99 | 사용자 명시 | (동일) |
| 100 | default (priority 미지정 등록) | PR-7 WatchlistService — `priority ?: 0` ★ |
| 101~ | 후속 자동 강등 (post-MVP) | T-PERF·T-J 진입 시 |

**★ 정합 함정**: PR-7의 `WatchlistService.add` 코드는 `request.priority ?: 0`로 priority 미지정 시 0(최상위)으로 저장. 본 PR-9 정책은 default를 100(중간)으로 두고 사용자 명시 0~99가 우선이어야 함.

**architect 결정**: PR-7 코드 정정은 본 PR-9 비범위 (코드 변경 0건 룰). **본 PR-9에서는 SubscriptionRouter가 priority 0 대량 발생 시에도 `created_at DESC` tie-breaker로 동작**. 단 후속 PR-9a backend-core가 코드 정정 1줄 commit 추가 가능 — `WatchlistService.add` `request.priority ?: 100` (default 100). dispatch-spec 명시.

### 6-3. tie-breaker (같은 priority 사이)

ADR 0048 §2-3 정합:

1. **`created_at DESC`** — 최근 등록 종목 우선 (사용자가 방금 의도적으로 추가)
2. **`code ASC`** (deterministic) — 같은 시간 등록 시 결정성

`findByUserIdOrderByPriorityAscCreatedAtDesc` 쿼리 그대로 사용 (PR-7 V3 정합).

### 6-4. 41+1번째 종목 등록 시 동작 (사용자 시나리오)

| 시나리오 | 동작 |
|---|---|
| 사용자가 31번째 종목 등록 (priority 미지정, 즉 100) — 기존 30개도 priority 100 | DB 등록 OK + 새 종목 polling 진입 (기존 30개 WS 유지) — `created_at DESC`로 새 종목이 후순위라 polling |
| 사용자가 31번째 종목 등록 + priority=50 명시 — 기존 30개 priority 100 | DB 등록 OK + 기존 priority 100 중 1개 (created_at 가장 오래된) unsubscribe + 새 종목 WS subscribe → eviction된 종목은 polling 진입 |
| 사용자가 priority=0 명시 등록 | DB 등록 OK + 기존 priority 100 중 1개 unsubscribe + 새 종목 WS subscribe |
| 사용자가 동일 priority 100 종목 60개 등록 | 상위 30개 WS, 하위 30개 polling. priority change 0인 한 동일 분배 유지 |

**UI 경고 X (1차 MVP)** — eviction 발생 시 사용자에게 toast·notification 0. 단 UI WatchlistRow 배지 (T-S1 후속)에 "polling" 표시. **사유**: 1차 MVP 사용자는 30 종목 미만 풀 사용 — eviction 거의 발생 안 함. T-S1 진입 시 배지 + 사용자 명시 priority 변경 UI(드래그 정렬) 본격화.

### 6-5. promotion (자동) — slot 회수 시 재 sub

다음 시점에 priority 상위 N재계산:

| 트리거 | 동작 |
|---|---|
| Watchlist REMOVED (구독 중 종목) | 구독 slot 회수 → polling 종목 중 priority 최상위 1건 자동 promotion (WS subscribe + polling stop) |
| Closed → Regular 전이 | 모든 priority 상위 N 일괄 재 subscribe |
| WS 재연결 후 | 동일 |

`SubscriptionRouter.recompute()` 함수 — Watchlist 정렬 조회 + 현재 subscribe 상태 비교 + diff 적용.

---

## §7. 데이터 흐름 LOCK

ADR 0029 + ADR 0019 정합. 본 spec에서 전체 흐름 LOCK.

### 7-1. 영업일 장중 (Regular) — KIS WS tick 흐름

```
KIS WS H0STCNT0 message arrival (consumer/.../ws/KisWebSocketBridge)
    ↓ parse — code, price, volume, ts
    ↓
[1] Redis quote:last:{code} SET (TTL 7일) — ADR 0014 T2
    ↓
[2] Redis candle:current:{code}:1m HMSET (open·high·low·close·volume·startTs) — ADR 0029 §2-1
    └─ 매 tick 갱신: high = max(high, price), low = min(low, price), close = price, volume += vol
    ↓
[3] Redis candle:today:{code}:1d HMSET (open·high·low·close·volume) — ADR 0029 §2-1
    ↓
[4] Kafka market.tick.v1 publish
    payload: {code, price, volume, ts, source: "live", stale: false}
    ↓
[5] (1m closing 시) — TickToMinuteAggregator @Scheduled(cron = "0 * * * * ?") 매 분 0초
    ├─ Redis candle:current:{code}:1m → DB candle INSERT (interval='1m', source='KIS')
    ├─ Redis candle:current:{code}:1m DEL
    └─ Kafka market.candle.minute.closed.v1 publish
        payload: {code, interval: "1m", ts: closingTs, OHLCV...}
```

### 7-2. WS 끊김 시 (polling 활성)

```
WatchlistPollingWorker @Scheduled(fixedRate = 5000)
    ↓ for each polling 종목 (Watchlist 풀 — priority 상관 없이 모두)
    ↓ KisRateLimiter.acquire() — ADR 0037
    ↓ KisRestPriceAdapter.fetchPrice(code) — FHKST01010100 inquire-price
    ↓ parse — code, price, volume, ts
    ↓
[1] Redis quote:last:{code} SET (TTL 7일) — source: "polling"
    ↓
[2] Kafka market.tick.v1 publish
    payload: {code, price, volume, ts, source: "polling", stale: false}
```

**polling 시 candle:current 갱신은 안 함** (KIS WS만 분당 1m 합성 — polling은 5s 주기라 1m 합성 정확도 부족). polling 풀의 1m 분봉은 다음 영업일 cold-start 또는 daily backfill cron(post-MVP)에서 보강.

### 7-3. Closed session (영업일 외)

```
MarketSessionResolver.resolve() → Closed
    ↓
KisWebSocketBridge.unsubscribeAll() + close
WatchlistPollingWorker.stop()
    ↓
사용자 차트 진입 시 (T-S1 후속) → ADR 0014 T3 last-day-close fallback
```

**MarketSessionResolver의 1차 구현**: 정적 캘린더 시드 1주일분 (KRX 영업일 — 토·일·공휴일 폐장). T-MS1 진입 시 본격 KRX 캘린더 동기화 + 임시휴장.

**architect 결정**: 본 PR-9는 `MarketSessionResolver` stub OK — **간단한 KST 시간 기반 판단** (월~금 09:00~15:30 = Regular, 그 외 Closed). 정확한 영업일 캘린더는 T-MS1 진입 시. dispatch-spec 명시.

### 7-4. KIS REST 1wk·1mo·1y backfill (cold-start)

```
WatchlistChangedKafkaListener.onAdded() (per code)
    ↓
KisRateLimiter.acquire()
    ↓
KisRestPort.fetchCandles(code, ONE_WEEK, count=1000) → DB candle INSERT
    ↓ (반복 — ONE_MONTH·ONE_YEAR)
```

**KisRestPort는 PR-7에 미구현**. 본 PR-9에서 신규 — `consumer/.../infrastructure/kis/KisRestCandleAdapter.kt` (`KisRestPort` 구현). domain port `KisRestPort`는 PR-5a에 이미 존재.

### 7-5. Redis 키 매트릭스 (본 PR-9 추가·갱신)

| Redis 키 | 데이터 | TTL | source |
|---|---|---|---|
| `quote:last:{code}` | `{price, volume, ts, source}` JSON | 7일 (ADR 0014 T2) | KisWebSocketBridge OR WatchlistPollingWorker |
| `candle:current:{code}:1m` | OHLCV HASH | 1분 closing 시 evict | KisWebSocketBridge |
| `candle:today:{code}:1d` | OHLCV HASH | 장 마감 + 30분 (장 마감 후 16:00 DailyCloseFinalizer evict — T-MS1) | KisWebSocketBridge |
| `watchlist:source:{userId}` | `{code: "live"\|"polling"}` HASH | 24h | SubscriptionRouter |
| `kis:ws:approval_key` | KIS WS approval_key | 24h (ADR 0038 정합) | KisWebSocketBridge — 기존 `kis:token:*` 패턴 재사용 가능 또는 별도 |

### 7-6. Kafka 토픽 매트릭스 (본 PR-9 추가)

| topic | producer | consumer | retention | 추가 시점 |
|---|---|---|---|---|
| `market.tick.v1` | consumer (KisWebSocketBridge·WatchlistPollingWorker) | api (STOMP — T-S1 후속) | 1h | 본 PR-9 |
| `market.candle.minute.closed.v1` | consumer (TickToMinuteAggregator) | api (STOMP — T-S1 후속) | 1d | 본 PR-9 |
| `watchlist.changed.v1` | api (PR-7) | **consumer (본 PR-9 — 첫 consumer)** | 7d | PR-7 producer + PR-9 consumer |

본 PR-9에서 `kis.ws.disconnected.v1`·`market.session.v1` topic 추가 0건 — T-NOTIFY·T-MS1 진입 시 합류.

---

## §8. 장 상태 인지 (ADR 0013 Market Session FSM) — 1차 stub

### 8-1. 본 PR-9 구현 범위

ADR 0048 §2-5 정합:

| Session | 동작 |
|---|---|
| Regular (09:00~15:30 KST 평일) | 풀 가동 |
| PreMarket·PostMarket | KIS REST polling 30s (시간외) — 단 본 PR-9 1차는 단순화 — Regular와 동일 처리 (5s polling) |
| Closed (그 외) | WS unsubscribe + slot 회수 |

### 8-2. MarketSessionResolver 1차 구현

```kotlin
// consumer/.../infrastructure/session/SimpleMarketSessionResolver.kt (신규)
@Component
class SimpleMarketSessionResolver : MarketSessionResolver {
    override fun resolve(at: Instant): MarketSession {
        val kst = at.atZone(ZoneId.of("Asia/Seoul"))
        val day = kst.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return MarketSession.Closed("주말")
        }
        val time = kst.toLocalTime()
        return when {
            time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(15, 30)) -> MarketSession.Regular
            time.isAfter(LocalTime.of(8, 30)) && !time.isAfter(LocalTime.of(9, 0)) -> MarketSession.PreMarket
            time.isAfter(LocalTime.of(15, 30)) && time.isBefore(LocalTime.of(16, 0)) -> MarketSession.PostMarket
            else -> MarketSession.Closed("장외")
        }
    }
}
```

**공휴일 미인지** — T-MS1 진입 시 KRX 캘린더 합류. 본 PR-9 단순화.

### 8-3. session 변경 감지

`@Scheduled(fixedRate = 60_000)` 1분마다 resolve. 직전 session과 다르면 `KisWebSocketBridge.onSessionChanged(prev, current)` 호출.

| 전이 | 동작 |
|---|---|
| Regular → PostMarket | 5분 grace period 후 unsubscribe (closing 분봉 마지막 tick 보장) |
| PostMarket → Closed | (이미 unsubscribed) |
| Closed → Regular | reconnect + priority 상위 N 재구독 |
| 그 외 | no-op (단순화) |

---

## §9. 비범위 (T-S1·T-MS1·T-IM1·T-CA1·T-RECON·T-NOTIFY로 미루기)

본 PR-9에서 **명시적으로 미룬다**. backend-core·market-data sub-agent가 임의 구현 시 reject.

| 항목 | 미룰 트랙 | 사유 |
|---|---|---|
| KRX 영업일 캘린더 동기화 | T-MS1 | ADR 0013 정합. 본 PR-9는 stub MarketSessionResolver |
| ADR 0014 T3 last-day-close fallback | T-MS1 | 본 PR-9는 T1·T2까지 |
| `quote:lastbusinessday:{code}:{yyyyMMdd}` Redis 키 | T-MS1 | (동일) |
| 휴일·공휴일 시드 | T-MS1 | (동일) |
| KIS WS 호가 stream `H0STASP0` 구독 | T-S1 | SymbolDetail 호가창 — 본 PR-9는 `H0STCNT0` 체결가만 |
| KIS WS 체결통보 stream `H0STCNI0` 구독 | T-O 또는 T-RECON 진입 시 | 사용자 본인 주문 체결 — 본 PR-9는 slot 1개만 reserve |
| STOMP fanout (`/topic/market.tick.{code}`·`/topic/market.candle.minute.{code}`) | T-S1 | 사용자 차트 — Kafka publish는 본 PR-9에서, STOMP는 후속 |
| DailyCloseFinalizer (16:00 KST 1d 영속) | T-MS1 | ADR 0029 §2-3 |
| 분봉·일봉 backfill cron (post-MVP) | post-MVP | 운영 자동화 — 본 PR-9는 cold-start만 |
| 종목별 source UI 배지 ("live"/"polling") | T-S1 (frontend) | 본 PR-9는 backend Redis state까지 |
| `kis.ws.disconnected.v1` Kafka publish + Slack 알림 | T-NOTIFY (W3) | ADR 0044 정합 |
| `market.session.v1` Kafka publish | T-MS1 | 장 상태 변경 알림 |
| Yahoo `.KS` vs `.KQ` ticker 정확화 | T-IM1 | 본 PR-9는 `.KS` → `.KQ` 순차 시도 fallback |
| corp action adjusted 컬럼 | T-CA1 | 본 PR-9는 raw 컬럼만 (adj null) |
| `H0STOAA0` 시간외 단일가 stream | post-MVP | 1차 비범위 |
| Broker Reconciliation cron | T-RECON (W3) | ADR 0041 |
| Position Bootstrap STAGE 0-4 (KIS WS 구독 시작) | **본 PR-9 합류** | ADR 0042 STAGE 0-4 = `H0STCNI0` 1 slot reserve. PR-7은 0-1·0-2·0-3까지. 본 PR-9에서 0-4 시작점 합류 — 단 `H0STCNI0` 본격 listener는 T-O 진입 시 |

---

## §10. 트레이드오프 표 (architect 자율 결정 사유)

| 항목 | 결정 | 대안 | 사유 |
|---|---|---|---|
| **slot 분배 (1+30+10)** | `H0STCNI0` 1 + `H0STCNT0` 30 + `H0STASP0` 10 | (1+39+1)·(1+40+0)·(1+20+20) | 1차 MVP — Watchlist 풀 30 ≤ ADR 0028 §2-2 + 호가창 LRU 10 (사용자 빠른 종목 전환 패턴 정합). post-MVP는 ADR 0048 §2-1 표 (1+39+1) |
| **polling 주기** | 5s fixed | 1s · 30s · 동적 | 1s = Rate Limit 위반 risk. 30s = UX 부조화. 5s = 풀 30 × 5s = 6 req/s ≤ 10 한도. 동적은 post-MVP (ADR 0048 §3-4) |
| **WS 끊김 → polling 전환 timing** | 30s 재연결 실패 후 polling | 즉시 polling · 5분 후 polling | 즉시 = 잦은 끊김에서 polling-WS 토글 폭주. 5분 = 사용자 시세 갱신 latency 5분. 30s가 균형 |
| **eviction 시 사용자 알림** | UI 배지 (T-S1 후속) — 본 PR-9 backend 0 | toast·notification | 1차 MVP 사용자 풀 ≤ 30 — eviction 거의 0. T-S1 진입 시 배지 본격 |
| **Yahoo cold-start 직렬 vs 병렬** | 직렬 (1m → 5m → ... → 1d) | 7개 thread 병렬 | 병렬은 Yahoo HTTP 429 risk (외부 API 부하). 직렬 ≤ 30s 충분 |
| **Yahoo 어댑터 위치** | consumer 모듈 자체 + domain port | api 모듈 단일 + REST 호출 | api 호출 latency·complexity. 1차 단순화 — consumer 직접. T-S1 진입 시 api에도 같은 port 어댑터 추가 |
| **MarketSessionResolver 1차 구현** | 시간 기반 stub (공휴일 미인지) | 정적 캘린더 시드 1주일분 | T-MS1 진입 시 본격 KRX 캘린더. stub은 토·일만 인지 — 평일 공휴일은 Regular로 오인지 (KIS WS 0 데이터 / WS 자체 끊김 → polling 자동 전환으로 자연 회복) |
| **TickToMinuteAggregator @Scheduled vs event-driven** | `@Scheduled(cron = "0 * * * * ?")` 매 분 0초 | KIS WS 분 closing 신호 trigger | KIS WS 자체 분 closing 신호 미제공. 또는 client side roll over (ts 분 변경 감지). cron이 단순. 5초 latency 허용 |
| **KisRestPort 확장 (fetchPrice 추가) vs 신규 port** | `KisRestPort` 확장 (default 메서드 또는 별도 method) | `KisQuotePort` 신규 | 단일 port 유지가 단순. method 추가는 backward compat 0 (PR-5a domain만 import) |
| **default priority 100 vs 0** | (PR-7 코드는 0 — 본 PR-9에서 정정 1줄 commit OR 그대로 운영) | 0 그대로 | architect 권장 = backend-core가 1줄 정정 commit (PR-9a). 그러나 코드 변경 0건 룰 위반 위험 — orchestrator가 sub-agent에게 위임. dispatch-spec §2-5에 명시 |

---

## §11. 발견 함정 + 위험

### 11-1. KIS WS approval_key vs OAuth access_token 분리

| 항목 | 용도 | 발급 |
|---|---|---|
| `access_token` (ADR 0038) | KIS REST 호출 — Authorization header | `POST /oauth2/tokenP` |
| **`approval_key`** | **KIS WS 연결 — header `approval_key`** | `POST /oauth2/Approval` (별도 endpoint) |

PR-7 KisAuthAdapter는 `access_token`만 처리. 본 PR-9 `KisWebSocketBridge`는 `approval_key` 별도 발급 필요. ADR 0038 §2-1 `KisApprovalStorePort` 신규 — 단 본 PR-9 단순화: KisAuthAdapter에 `getApprovalKey()` 메서드 1개 추가 OR consumer에 `KisApprovalAdapter` 신규.

**architect 결정**: `KisAuthAdapter`에 `getApprovalKey(userId, scope)` 메서드 추가 (단일 어댑터 — Redis 키만 다름 `kis:approval:{userId}` TTL 23h). dispatch-spec §3-2 명시.

### 11-2. WS 메시지 인코딩 — KIS의 PIPE 구분자 텍스트 형식

KIS WS는 JSON이 아닌 **텍스트 + PIPE 구분자** 형식으로 응답:

```
0|H0STCNT0|001|005930^090012^60900^...
```

(0=실시간, H0STCNT0=TR_ID, 001=구독 N건째, body=PIPE 구분자 31개 필드)

KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_*.py` 참조. **사용자 메모리 `feedback_kis_first_policy.md` 정합** — 본 spec에서 31개 필드 매핑은 명시하지 않음 (sub-agent가 KIS open-trading-api 정확한 필드 순서 사용 의무). dispatch-spec §3-3 의무 참조.

핵심 필드 (sub-agent가 KIS open-trading-api에서 확인):
- index 0: 종목코드 (mksc_shrn_iscd)
- index 1: 체결시각 (stck_cntg_hour)
- index 2: 체결가 (stck_prpr)
- ...
- index 12: 체결량 (cntg_vol)

### 11-3. KIS Heartbeat (PINGPONG)

KIS WS는 PINGPONG 메시지로 heartbeat 확인. 미응답 시 KIS 측에서 close. consumer가 자동 응답 의무. KIS open-trading-api `examples_llm/.../websocket_*.py` 참조.

### 11-4. Yahoo timestamp epoch unit

Yahoo는 **second epoch** 반환 (millisecond 아님). Kotlin `Instant.fromEpochSeconds(it)` 변환 의무. kotlinx.datetime `Instant.fromEpochSeconds`.

### 11-5. PostgreSQL UPSERT 시 batch INSERT 성능

cold-start 1m fetch 결과 ~ 2,700 row × ON CONFLICT DO NOTHING — 단건 INSERT 시 ~ 27초 소요. **batch INSERT** 의무 (`INSERT INTO ... VALUES (?,?,...), (?,?,...), ...` 또는 JDBC `addBatch()`). 1000건 chunk.

### 11-6. consumer 모듈 Kafka producer 추가

PR-7까지 consumer는 Kafka **consumer**만 (PR-7도 Kafka 미사용 — Bootstrap만). 본 PR-9에서 consumer가 처음 Kafka **publisher** 합류 (`market.tick.v1`·`market.candle.minute.closed.v1`). `consumer/src/main/resources/application.yml` Kafka producer config 추가 의무.

### 11-7. 단위 테스트 — KIS WS mock

KIS WS 실 연결 테스트는 CI 환경 불가. 단위 테스트는 **WireMock 또는 자체 stub** — `KisWsPort` mock 구현체로 SubscriptionRouter 단위 테스트. 통합은 사용자 환경 검증.

### 11-8. Kafka topic `market.tick.v1` retention

Kafka 토픽 1h retention — 본 PR-9에서 처음 publish. `application.yml`에 topic 자동 생성 시 retention 명시 또는 운영자가 수동 생성. **architect 결정**: topic 자동 생성 — Spring Kafka `@Configuration` Bean으로 `NewTopic("market.tick.v1", 1, 1)` + retention 1h 설정. dispatch-spec §3-5 명시.

### 11-9. Yahoo `.KS` vs `.KQ` 판별 미흡

PR-7 옵션 C로 instrument stub `market='UNKNOWN'`. Yahoo cold-start 시 `.KS` 시도 → 빈 응답이면 `.KQ` 시도. 둘 다 빈 응답이면 잘못된 ticker. T-IM1 진입 시 정확화. **본 PR-9 동작**: 둘 다 시도 — `.KS` 우선 (KOSPI 종목 다수).

### 11-10. SubscriptionRouter race condition

Kafka listener concurrency=1 보장 (single-thread) — race 0. 단 부팅 reconcile + `WatchlistPollingWorker` 동시 시작 시 race 가능 — `@PostConstruct` 직렬 + `Executor.newSingleThreadExecutor()` 일괄.

### 11-11. polling 풀 수정 시 thread safety

`WatchlistPollingWorker` 내부 `Set<String>` 변경 시 `ConcurrentHashMap.newKeySet()` 사용 의무. dispatch-spec §3-3 명시.

### 11-12. priority default 100 vs 0 (PR-7 정합 함정)

PR-7 `WatchlistService.add` 코드: `request.priority ?: 0` — 미지정 시 **0(최상위)** 저장. 본 spec §6-2 정책: default **100(중간)**.

**충돌**: 사용자가 30 종목을 priority 미지정으로 등록 시 모두 priority 0 → 31번째도 priority 0 → tie-breaker created_at만으로 분배. 정책상 큰 문제 아님 (created_at으로 분배 정상).

**architect 권장 (선택)**: PR-9a에서 `WatchlistService.add` 1줄 정정 — `request.priority ?: 100`. dispatch-spec §2-5에 옵션으로 명시. 의무 아님 — created_at tie-breaker로 충분.

---

## §12. 테스트 시나리오 골격 (R3 일괄)

룰 4 정합 — sub-agent 본인 PR엔 smoke 1~2건만. R3 일괄은 T-S1 또는 별도 묶음.

### 12-1. PR-9a backend-core smoke 2건 (필수)

#### 12-1-1. WatchlistChangedKafkaListener — ADDED 처리

```kotlin
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@EmbeddedKafka(topics = ["watchlist.changed.v1"])
class WatchlistChangedKafkaListenerTest {
    @Test
    fun `ADDED 메시지 수신 시 SubscriptionRouter add + cold-start submit`() {
        // given
        val payload = """{"userId":"u1","code":"005930","action":"ADDED","occurredAt":"..."}"""
        // when
        kafkaTemplate.send("watchlist.changed.v1", "u1", payload).get()
        // then
        verify(subscriptionRouter, timeout(2000)).add(eq("u1"), eq("005930"), any())
        verify(coldStartExecutor, timeout(2000)).submit(any<Runnable>())
    }
}
```

#### 12-1-2. SubscriptionRouter — slot 가득 시 priority eviction

```kotlin
class SubscriptionRouterTest {
    @Test
    fun `slot 가득 + 새 종목 priority 더 낮을 때 polling 진입`() {
        // given — 30 종목 priority 100, 모두 WS 구독 중
        val mockWs = mock<KisWsPort> { on { subscribedCodes() } doReturn (1..30).map { "0%05d".format(it) }.toSet() }
        val mockPollWorker = mock<WatchlistPollingWorker>()
        val router = SubscriptionRouter(watchlistRepo, mockWs, mockPollWorker, ...)

        // when — 31번째 priority 100 등록
        router.add("u1", "035720", priority = 100)

        // then — WS 미호출, polling worker에 add
        verify(mockWs, never()).subscribe(any())
        verify(mockPollWorker).add("035720")
    }

    @Test
    fun `slot 가득 + 새 종목 priority 더 높을 때 eviction`() {
        // given — 30 종목 priority 100
        // when — 31번째 priority 50 등록
        // then — priority 100 중 1건 unsubscribe + 새 종목 subscribe + 기존 종목 polling 진입
    }
}
```

### 12-2. PR-9b market-data smoke 2건 (필수)

#### 12-2-1. KisWebSocketBridge — onMessage 파싱

```kotlin
class KisWebSocketBridgeTest {
    @Test
    fun `H0STCNT0 메시지 파싱 → Redis quote write + Kafka publish`() {
        // given
        val rawMsg = "0|H0STCNT0|001|005930^090012^60900^..."  // KIS open-trading-api fixture
        val mockRedis = mock<StringRedisTemplate>()
        val mockKafka = mock<KafkaTemplate<String, String>>()
        val bridge = KisWebSocketBridge(mockRedis, mockKafka, ...)

        // when
        bridge.onMessage(rawMsg)

        // then
        verify(mockRedis).opsForValue().set(eq("quote:last:005930"), any(), any())
        verify(mockKafka).send(eq("market.tick.v1"), eq("005930"), contains("60900"))
    }
}
```

#### 12-2-2. WatchlistPollingWorker — 5초 주기 fetch

```kotlin
class WatchlistPollingWorkerTest {
    @Test
    fun `polling 풀 3 종목 × 5초 주기 KisRestPriceAdapter 호출`() {
        // given
        val worker = WatchlistPollingWorker(...)
        worker.add("005930"); worker.add("035720"); worker.add("000660")

        // when — 시간 advance 10초
        Thread.sleep(10_500)

        // then — 각 종목 2회 호출 (5s × 2)
        verify(mockKisRest, atLeast(2)).fetchPrice("005930")
        verify(mockKisRest, atLeast(2)).fetchPrice("035720")
        verify(mockKisRest, atLeast(2)).fetchPrice("000660")
    }
}
```

### 12-3. R3 통합 시나리오 (T-S1 또는 별도 묶음)

| # | 시나리오 | 명령 |
|---|---|---|
| 1 | Watchlist 등록 → cold-start 30s 이내 완료 | UI 등록 + `psql -c "SELECT count(*) FROM candle WHERE code='..."` 30s 이내 ≥ 17000 |
| 2 | KIS WS tick 30분 수신 → 1m 분봉 30건 INSERT | 장중 30분 후 candle interval='1m' count |
| 3 | 31 종목 등록 → 1건 polling | `redis-cli HGETALL watchlist:source:{userId}` → 30 live + 1 polling |
| 4 | 사용자 priority 변경 → eviction 재계산 | priority 0 부여 → 즉시 promotion |
| 5 | infra/redis 재기동 후 자동 reconcile | docker stop redis + start → consumer 자동 회복 |
| 6 | SIGTERM graceful shutdown | docker stop consumer → KIS WS unsubscribe log + Kafka commit |

R3 일괄 본격 시점은 T-S1 진입 (사용자 차트 표시 시점) 또는 별도 묶음 PR.

---

## §A. 부록 — sub-agent 분배 + 충돌 행렬 요약

본 절은 spec 본문 외 메타 안내. 상세는 별도 문서:
- `_workspace/specs/pr-9-dispatch-spec.md` — sub-agent 분배 명세
- `_workspace/specs/pr-9-conflict-matrix.md` — 충돌 행렬

**요약**: PR-9a (backend-core) + PR-9b (market-data) **병렬 가능**. modify-shared = `consumer/src/main/resources/application.yml` (Kafka producer config 추가) + `CHANGELOG.md` 슬롯 row 2건 + (선택) `WatchlistService.kt` 1줄 정정. **CHANGELOG는 ADR 0050 슬롯 채번룰로 충돌 0**, application.yml은 격리 권고(PR-9a가 단독 변경) — 자세한 충돌 행렬 별도 문서 참조.

---

## §B. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-9 T-W2 설계 가이드 v1 발행 (architect, opus 4.7 1M). ADR 0048 본문 발행 정합. slot 분배 1+30+10 LOCK · WS+polling 자동 전환 30s threshold LOCK · Yahoo cold-start 7 interval × 1회 직렬 LOCK · priority eviction created_at DESC tie-breaker LOCK · MarketSessionResolver 1차 stub (시간 기반) LOCK · Kafka topic `market.tick.v1`·`market.candle.minute.closed.v1` 신규 LOCK · 비범위 14건 명시 미룸. |
