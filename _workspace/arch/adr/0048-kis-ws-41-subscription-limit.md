# ADR 0048 — KIS WebSocket 41건 구독 한도 + 우선순위 정책

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (opus 4.7 1M)
- 관계: ADR 0028(Watchlist Ingestion Worker), ADR 0014(Fallback 3-tier), ADR 0029(당일 진행 중 캔들 Redis 캐시), ADR 0037(Rate Limit), ADR 0038(OAuth 분산락), ADR 0040(관심종목 우선 UI 흐름), ADR 0042(Position Bootstrap & Recovery), ADR 0044(Notification System), ADR 0045(Graceful Shutdown)
- 진입 트랙: T-W2(Watchlist Ingestion Worker, W1 ★) — 본 ADR이 T-W2 구독 정책의 1급 권위
- 후속 트랙: T-MS1(Market Session FSM)·T-S1(Symbol Detail)·T-RECON(Broker Reconciliation, W3)

---

## 1. 컨텍스트

KIS OpenAPI WebSocket의 공식 한도(권위 출처 — `docs/architecture/external-api-policy.md` §2-1 + KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_*.py`):

> **WebSocket 동시 구독 41건** — 계정·`approval_key`당 동시에 41개의 실시간 stream을 구독할 수 있다. 초과 요청은 KIS가 거부 응답한다.

**1 종목 = 1 stream 아님.** KIS는 stream을 TR_ID별로 분리한다:

| TR_ID | 데이터 | 용도 |
|---|---|---|
| `H0STCNT0` | 실시간 체결가 (틱) | 차트·시그널·DSL 평가 |
| `H0STASP0` | 실시간 호가 (10단계) | 종목 상세 호가창 |
| `H0STCNI0` | 실시간 체결통보 | 사용자 본인 주문 체결 (사용자 키 1 stream) |

따라서 **1 종목을 풀 모니터링하려면 최대 2 stream(`H0STCNT0` + `H0STASP0`)** 차지 + 사용자 체결통보 1 stream. **41건은 stream 수 단위 한도 (sum across TR_IDs)**.

magicJar는 다음 운영 트레이드오프에 직면한다:

1. **사용자가 41 종목 이상의 watchlist 등록 시 어느 종목을 KIS WS로 모니터링할지** — 모든 종목 동시 모니터링 불가
2. **체결가(`H0STCNT0`)와 호가(`H0STASP0`) 동시 구독 시 모니터링 가능 종목 수 절반(20)** — 호가창은 사용자가 종목 상세 페이지를 보고 있을 때만 필요
3. **체결통보(`H0STCNI0`) 1 slot 의무** — 사용자 자기 주문 체결 알림. 41건 한도에서 1을 빼야 실효 40
4. **WS 끊김 시 (재접속 동안)** — 모든 stream을 재구독해야 하고, 그 사이 시세 누락 발생 → REST polling fallback 의무 (ADR 0014)
5. **장 마감 후 (Closed)** — KIS WS 구독 의미 없음 (틱 0). 41건 slot을 모두 점유한 채로 두면 자원 낭비 + reconnect 시 실패 risk

ADR 0028은 "WS 한도 안에서 자연 제약. 풀 size 30 초과 시 우선순위(최근 진입·시그널 활성)로 자동 sub 토글"이라 명시했으나 **구체 정책이 부재**. 본 ADR이 그 정책을 LOCK한다.

---

## 2. 결정

### 2-1. Stream slot 예산 (41건 분배)

```
KIS WS 한도: 41 stream
─────────────────────────────────────────────────
[A] H0STCNI0 체결통보       1 slot  (사용자 본인 체결 — 의무 reserve)
[B] H0STCNT0 체결가 (틱)    N slot  (Watchlist 종목 — 1 종목당 1)
[C] H0STASP0 호가 (10단계)  M slot  (현재 사용자가 보고 있는 SymbolDetail 종목)
─────────────────────────────────────────────────
총합: 1 + N + M ≤ 41
```

**1차 MVP 정책 (Watchlist 풀 ≤ 30 — ADR 0028 §2-2 정합)**:

| 항목 | slot | 사유 |
|---|---|---|
| `H0STCNI0` 체결통보 | **1** | 사용자 본인 주문 체결 — KillSwitch 트리거의 prerequisite (ADR 0042 STAGE 0-4) |
| `H0STCNT0` 체결가 | **최대 30** | Watchlist 풀 size MVP 한도 정합 |
| `H0STASP0` 호가 | **최대 10 (동시)** | 사용자가 SymbolDetail 페이지에서 보고 있는 종목만. **풀 1 + 최근 진입 9** (LRU 토글) |
| 예비 | 0 | 41 - 1 - 30 - 10 = **0** (정확히 41) |

**호가 stream LRU 토글**: 사용자가 SymbolDetail 진입 → `H0STASP0` 구독 시작 → 다른 종목 진입 시 가장 오래된 stream unsubscribe (LRU 10건 한도). 사용자가 SymbolDetail 페이지 떠나면 즉시 unsubscribe (해당 페이지 unmount/blur 이벤트).

**post-MVP (풀 41+ 종목)**:

| 항목 | slot |
|---|---|
| `H0STCNI0` | 1 |
| `H0STCNT0` | 최대 39 (Watchlist 풀 priority 상위 39) |
| `H0STASP0` | 최대 1 (사용자 1 종목만) |

호가 stream은 1로 축소 (사용자가 동시에 1 종목 호가창만 보는 패턴 정합).

### 2-2. 41+1번째 종목 등록 시 동작 — Watchlist는 등록 허용, KIS WS는 priority eviction

**핵심 결정**: **사용자의 Watchlist 등록 자체는 거부하지 않는다.** Watchlist DB row는 무제한 등록 가능. 단 **KIS WS stream slot을 받지 못하는 종목은 polling fallback으로 운영**.

| 동작 | 정책 |
|---|---|
| **DB watchlist INSERT** | 무제한 허용 (ADR 0028 §2-2 — post-MVP 풀 size 100·무제한 단계 정합) |
| **`H0STCNT0` 구독 slot 부족 시** | **priority eviction** — 새 종목이 priority 더 높으면 priority 낮은 기존 종목을 unsub + 새 종목 sub. 같으면 LRU(최근 등록일 순). |
| **eviction된 종목** | **REST polling fallback** (`FHKST01010100` `inquire-price`) — 본 ADR §2-4의 polling worker가 5초 주기 fetch |
| **사용자 알림** | UI WatchlistRow에 **"실시간 구독 OFF (polling 5초)"** 배지 표시. eviction 시 toast 1회 (ADR 0044 NotificationSystem 미사용 — UI 단독) |
| **자동 promotion** | priority 변경·기존 종목 unsubscribe·시장 마감(휴장 중 slot 모두 회수) 시 priority 상위 N으로 자동 재 구독 |

**왜 등록 자체 거부 안 함**: 사용자 메모리 `feedback_no_mock_data.md` 정합 (사용자 흐름에서 "거부" 표시는 mock 합성 차단 정책의 자연 연장 — 데이터 경로가 polling으로 바뀐다 명시 + UI 배지로 투명성). 또한 사용자가 "이 종목 신호 받아볼 종목"으로 등록한 의도를 시스템이 거부할 권한 없음 (ADR 0001 비전 — 사용자가 최고 관리자).

### 2-3. 우선순위 정책 (priority eviction)

priority 결정 매트릭스 (낮은 숫자 = 상위):

| 우선 | 기준 | 데이터 source |
|---|---|---|
| **0 (최상위)** | 사용자가 명시적으로 priority=0 부여 (`POST /api/watchlists -d '{"code":"...", "priority":0}'`) | `watchlist.priority` 컬럼 (V1 baseline) |
| 1~99 | 사용자 명시 priority | `watchlist.priority` 컬럼 |
| **100 (default)** | priority 미지정 등록 (대다수) | default value |
| **200 (자동 강등 — eviction 후보 1차)** | post-MVP — 시그널 활성 0건 + 7일 거래 0회 ★ | 후속 트랙 (T-J·T-PERF에서 자동 강등 보강) |

**tie-breaker** (같은 priority 사이):

1. **최근 등록일 우선** (`watchlist.created_at DESC`) — 사용자가 방금 등록한 종목이 더 큰 관심
2. **종목 코드 사전순 ASC** (deterministic)

**MVP 1차 동작 (post-MVP eviction 점수는 미사용)**:
- `WatchlistRepository.findByUserIdOrderByPriorityAscCreatedAtDesc(userId)` — V1 baseline 인덱스 (V3 `idx_watchlist_user_priority`) 그대로 사용
- 상위 N개(N = WS slot 한도 = MVP 30)만 KIS WS 구독, 나머지는 polling

### 2-4. WS 끊김 시 polling 자동 전환·복구 정책

**3-tier fallback (ADR 0014 정합) + KIS WS 자체 끊김 회복**:

```
T1 LIVE (KIS WS H0STCNT0)
   │ stream 끊김 (10초 무신호 또는 onClose) — 본 ADR §2-4-A
   ▼
T1.5 RECONNECTING (지수 백오프 재연결)
   │ 30초 안에 재연결 못함 — 본 ADR §2-4-B
   ▼
T2 POLLING (KIS REST FHKST01010100 5초 주기) — 본 ADR §2-4-C
   │ 영업일 종료 또는 사용자 워치리스트 제거
   ▼
T3 IDLE
```

#### §2-4-A. WS 끊김 감지

| 트리거 | 조건 |
|---|---|
| `onClose` 콜백 | KIS WS 서버가 명시적 close 보낸 경우 (TPS 위반·세션 한도 등) |
| **10초 heartbeat 무신호** | 마지막 메시지 수신 시각 + 10초 경과 (구독 종목 없을 때는 무관) |
| **5분 데이터 0** (장중) | session=Regular인데 5분간 메시지 0건 → stale (KIS open-trading-api 권장) |

#### §2-4-B. 재연결 정책 (지수 백오프)

| 시도 | 대기 |
|---|---|
| 1차 | 즉시 |
| 2차 | 2s |
| 3차 | 5s |
| 4차 | 10s |
| 5차+ | 30s 고정 (max) |

**6회 연속 실패 시** Notification (`kis.ws.disconnected.v1` Kafka publish — ADR 0044 NotificationSystem 정합. **단 본 ADR은 topic 추가 0건 — T-NOTIFY 진입 시 합류**).

#### §2-4-C. polling fallback (T2)

WS 재연결 실패 30초 또는 §2-4-A 트리거 후 즉시:

- 모든 Watchlist 종목 → KIS REST `inquire-price` (FHKST01010100) **5초 주기 polling**
- KIS Rate Limiter 통과 의무 (ADR 0037 — 초당 10건 한도 안에서 자연 제약 — 풀 30 종목 × 5s = 6 req/s ≤ 10)
- 풀 size 30 초과 시 polling 주기 자동 연장 (`max(5s, ceil(N/8))`) — Rate Limit 마진 확보
- Redis `quote:last:{code}` write (ADR 0014 T2 cache 정합)
- Kafka `market.tick.v1` publish (payload `source: "polling"` + `stale: true` 1차에는 `stale: false`로 — 신선도는 ADR 0014 정합)

**WS 복구 시점**: 재연결 성공 + 첫 메시지 수신 → polling 즉시 중단 + 모든 stream 재구독 + Redis cache 정합 검증 (`getLatestPrice` last update timestamp ≥ 10s ago면 polling 1회 보강 후 stop).

#### §2-4-D. graceful shutdown (ADR 0045 정합)

SIGTERM 수신 시:
1. KIS WS `unsubscribe(allCodes)` 시도 (timeout 5s)
2. WS close
3. polling worker shutdown
4. ApplicationContext close

타임아웃 시에도 forced close — KIS slot은 timeout 후 자동 회수 (1분 이내).

### 2-5. 장 상태별 동작 (ADR 0013 Market Session FSM 정합)

| Session | KIS WS 동작 | polling 동작 |
|---|---|---|
| **Regular (09:00~15:30)** | 풀 가동 — 1 + 30 + 10 = 41 slot 모두 사용 | WS 정상 시 0. WS 끊김 시 §2-4-C 활성 |
| **PreMarket (08:30~09:00)** | 시간외 단일가 KIS WS 미지원 (KIS 공식 — 시간외는 별도 TR_ID `H0STOAA0` — 1차 비범위) | KIS REST polling 30초 주기 (시간외 가격 표시) |
| **PostMarket (15:40~16:00)** | 동일 — `H0STOAA0` 비범위 | KIS REST polling 30초 주기 |
| **Closed (휴장·주말)** | **모든 stream unsubscribe + WS close** — slot 회수 (자원 낭비 차단) | 0 (last-day-close 반환 — ADR 0014 T3) |

**Closed 진입 시점**: `MarketSessionResolver.resolve()`가 Closed 반환 + 5분 grace period 후 → KIS WS close. grace period는 장 마감 직후 closing 분봉 마지막 tick 수신 보장용.

**Closed → Regular 전이**: 다음 영업일 09:00 즉시 KIS WS reconnect + 모든 priority 상위 N 종목 재구독.

### 2-6. watchlist.changed.v1 → 구독 변경 흐름 (T-W1 → T-W2 인입)

PR-7(T-W1)가 발행한 Kafka topic `watchlist.changed.v1`를 본 PR-9(T-W2)에서 처음 소비. payload (PR-7 spec §4-3 정합):

```json
{ "userId": "...", "code": "005930", "action": "ADDED" | "REMOVED", "occurredAt": "..." }
```

**consumer 동작 매트릭스**:

| action | 현재 WS slot | 동작 |
|---|---|---|
| ADDED | slot 여유 (현재 < 30) | 즉시 `H0STCNT0` subscribe + Yahoo cold-start fetch (§2-7) + Redis `quote:last` warm |
| ADDED | slot 가득 (현재 = 30) | priority 비교 → eviction 후보 unsubscribe + 새 종목 subscribe (priority 더 낮으면) / OR 새 종목은 polling 진입 (priority 더 높으면) + Yahoo cold-start fetch (§2-7) |
| REMOVED | 구독 중 | unsubscribe + Redis cache evict + DB position 행은 보존 (사용자가 보유 중일 수 있음) |
| REMOVED | polling 중 | polling worker에서 제외 |

**핵심**: Yahoo cold-start는 priority·slot 무관하게 **항상 실행** (DB 영속이므로 슬롯 한도 무관 — ADR 0019).

### 2-7. Yahoo cold-start 트리거 + 범위

ADR 0019 정합 매트릭스를 본 ADR에서 LOCK:

| Interval | 한도 | 호출 | 위치 |
|---|---|---|---|
| 1m | 7일 max | 1회 | `YahooFinanceClient.fetchCandles(code, "1m", "7d")` |
| 5m | 60일 max | 1회 | `YahooFinanceClient.fetchCandles(code, "5m", "60d")` |
| 15m | 60일 max | 1회 | (동일) |
| 30m | 60일 max | 1회 | (동일) |
| 90m | 60일 max | 1회 | (동일) |
| 1h | 730일 max | 1회 | (동일) |
| 1d | Max (전체) | 1회 | (동일) |
| 2m | (선택) | (1차 비범위 — 사용자 차트에 2m 노출 0) | — |
| 1wk·1mo·1y | KIS REST FHKST03010100 | 1회 | `KisRestPort.fetchCandles(code, ONE_WEEK/MONTH/YEAR, count=1000)` |

**총 호출 = 7회 Yahoo + 3회 KIS REST = 10회 (per 종목)**.

**트리거**: `watchlist.changed.v1` action=ADDED 수신 즉시 — 기존 DB candle row 존재 검증 후 **부재 시에만 호출** (idempotent).

**실패 fallback**:
- Yahoo 1회 실패 → 60s 대기 + 1회 재시도 (ADR external-api-policy.md §3-2 정합)
- 또 실패 → DB INSERT 0건 (해당 interval만) — 다음 사용자 진입 또는 daily backfill cron(post-MVP)에서 retry
- 사용자 차트 진입 시 데이터 부재면 "데이터 수집 중" 명시 표시 (사용자 메모리 `feedback_no_mock_data.md` 정합 — mock 합성 0)

**polling 전환 timing**: cold-start 실행 중에도 KIS WS 구독은 즉시 시작 (병렬). cold-start 완료를 차트 표시 전제로 보지 않음 — 당일 분봉은 KIS REST·WS source라 영업일 첫 진입 시 즉시 응답 가능.

---

## 3. 결과

### 3-1. 즉시 (T-W2 PR-9 진입)

- consumer 모듈 **`WatchlistIngestionWorker`** 신규 (`consumer/.../ingestion/`)
- consumer 모듈 **`KisWebSocketBridge`** 신규 (`consumer/.../ws/`) — `domain/.../market/port/KisWsPort` 구현
- consumer 모듈 **`SubscriptionRouter`** 신규 (`consumer/.../ingestion/`) — slot 예산 + priority eviction + LRU 호가 토글
- consumer 모듈 **`WatchlistPollingWorker`** 신규 (`consumer/.../ingestion/`) — eviction 종목 + WS 끊김 시 fallback
- consumer 모듈 **`WatchlistChangedKafkaListener`** 신규 (`consumer/.../kafka/`) — PR-7 topic 첫 consumer
- consumer 모듈 **`KisRestPriceAdapter`** 신규 (`consumer/.../infrastructure/kis/`) — `FHKST01010100` polling
- api 모듈 **`YahooFinanceClient`** 신규 (`api/.../adapter/yahoo/`) — Yahoo cold-start 7회 fetch
- api 모듈 **`CandleJpaEntity`·`CandleJpaRepository`·`CandleRepositoryAdapter`** 신규 (`api/.../persistence/`) — V1 baseline `candle` 테이블 영속
- domain 모듈 **`KisRestPort` 확장** — `fetchPrice(code): PriceTick` 추가 (polling 용)
- domain 모듈 **`SubscriptionPolicy`** 신규 (`domain/.../market/port/`) — slot 예산 결정 함수 (pure)
- 본 ADR 0048 README 인덱스 갱신 (§12 후속 예약 → §5 시장 데이터)
- master-architecture §10 ADR 인덱스 갱신 (0048 발행 완료 마킹)
- candle-data-policy-v2.md §6 갱신 — polling fallback 흐름 추가 (1줄)

### 3-2. 데이터 흐름 (전체)

```
Watchlist 등록 (T-W1 PR-7)
   ↓ Kafka watchlist.changed.v1 (action=ADDED)
   ↓
[consumer] WatchlistChangedKafkaListener
   ├─ DB watchlist row 검증 (정합)
   ├─ Yahoo cold-start 7 interval × 1회 → DB candle INSERT  (§2-7)
   ├─ KIS REST 1d·1wk·1mo·1y → DB candle INSERT
   ├─ SubscriptionRouter.add(code, priority)
   │    ├─ slot 여유 → KisWsPort.subscribe([code])
   │    └─ slot 부족 → priority eviction 또는 polling 진입
   └─ Redis quote:last:{code} warm (KIS REST 1회)
   ↓
[consumer] KisWebSocketBridge
   ├─ tick 수신 → Redis candle:current:{code}:1m 갱신 (ADR 0029)
   ├─ tick 수신 → Kafka market.tick.v1 publish
   └─ 1m closing → DB candle INSERT (interval='1m') + Kafka market.candle.minute.closed.v1
   ↓
[consumer] WatchlistPollingWorker (eviction 종목 또는 WS 끊김)
   ├─ 5초 주기 KisRestPriceAdapter.fetchPrice(code) — Rate Limiter 통과 (ADR 0037)
   ├─ Redis quote:last:{code} write
   └─ Kafka market.tick.v1 publish (payload source="polling")
   ↓
[api] STOMP /topic/market.tick.{code}  (T-S1 진입 시 fanout — 본 PR-9 비범위)
```

### 3-3. 운영 효과

| 측면 | 이전 (ADR 0048 부재) | 이후 |
|---|---|---|
| 41 종목 한도 | 코드 부재 — 41+1번째 등록 시 동작 미정의 | 등록 허용 + polling fallback + UI 배지 |
| WS 끊김 | KIS WS 단독 의존 → 시세 끊김 | 5초 polling 자동 전환 + 30초 안 재연결 회복 |
| 호가창 | 모든 종목 호가 구독 (slot 폭주) | LRU 10건 (사용자가 보는 종목만) |
| 휴장 중 | WS slot 점유 유지 | 자동 unsubscribe (자원 회수) |
| watchlist.changed.v1 | producer만 (PR-7) | consumer 합류 (본 PR-9) |
| Yahoo cold-start | 정책만 (ADR 0019) | 코드 진입점 (`YahooFinanceClient`) |

### 3-4. trade-off

- **41 종목 hard limit 폐기 (post-MVP polling 풀 무제한)** — KIS Rate Limiter가 새로운 자연 한도. 풀 100 종목 × 5s polling = 20 req/s → ADR 0037 share 분할(consumer 2/10)에 위반 — 이 시점에서 polling 주기 자동 연장 (`max(5s, ceil(N/2))`) — 풀 100이면 50초 주기. **사용자 인지 필요 항목**: 풀 size가 매우 클 때(post-MVP) 시세 갱신 latency 증가.
- **호가창 LRU 토글의 추가 KIS WS 메시지 비용** — 사용자가 SymbolDetail 페이지 빠르게 전환 시 sub/unsub 메시지 폭증. throttle 200ms (debounce) 적용.
- **Yahoo cold-start 7회 호출의 latency** — 첫 등록 시 약 5~10초. 백그라운드 비동기로 진행해 사용자 등록 응답을 차단하지 않음 (Kafka listener thread).
- **eviction 종목의 polling 갱신 주기 5s vs WS 즉시 전달의 시세 latency 차이** — UI 배지로 투명성 확보. 시그널·DSL 평가는 polling 주기에 맞춰 5s 단위로만 수행 (시그널 정확도 영향 미미 — 분봉·일봉 base 평가).

---

## 4. 대안 (rejected)

| 대안 | rejected 사유 |
|---|---|
| **41 종목 hard limit (Watchlist DB INSERT 거부)** | 사용자 의도 거부 — 사용자 메모리 `feedback_no_mock_data.md`·ADR 0001 비전 위반 (사용자=최고 관리자). 또한 ADR 0028 §2-2 post-MVP 풀 size 100·무제한 정합 위반 |
| **WS 다중 connection (계정 1개 × 다중 approval_key)** | KIS 공식 정책 위반 risk — `approval_key`는 계정·앱키당 1개 발급 권장. 다중 connection 시 KIS 차단 risk |
| **호가창 stream 폐기 (`H0STASP0` 0 slot)** | 사용자 SymbolDetail에서 호가창 표시 비전 (ADR 0040 §2-2 §2-4 정합 — 종목상세 화면 호가). 후속 T-S1에서 즉시 필요 |
| **eviction 폐기 — polling만 사용** | KIS WS 실시간성(ms 단위) vs polling(5s)의 사용자 경험 차이 큼. priority 상위 종목은 WS 우선 |
| **polling 주기 1초** | KIS Rate Limit 초과 risk (풀 30 × 1s = 30 req/s ≫ 10 한도). 5s가 풀 30 기준 한도 안 + UX 충분 |
| **eviction 시 사용자 거부 응답** | 사용자 의도 거부 (위와 동일). UI 배지로 투명성 확보 후 등록 허용이 정합 |

---

## 5. 검증

본 ADR이 T-W2 spec/구현에 정확히 반영되었는지:

- [ ] consumer `KisWebSocketBridge`가 `KisWsPort` 구현 + 41 stream slot 분배 (1+30+10) 코드 검증
- [ ] consumer `SubscriptionRouter`가 priority 기반 eviction + LRU 호가 토글 단위 테스트
- [ ] `watchlist.changed.v1` Kafka listener가 ADDED·REMOVED 양쪽에서 §2-6 매트릭스 동작 검증
- [ ] WS 끊김 시 polling 자동 전환 — 통합 테스트 또는 사용자 검증 시나리오 (DoD)
- [ ] Yahoo cold-start 7회 호출 + DB candle INSERT 검증 (DoD)
- [ ] Closed session 진입 시 WS unsubscribe + slot 회수 — 사용자 검증 (영업일 마감 후 로그 확인)
- [ ] LLM provider import 0건 (룰 5)

---

## 6. 참고

- ADR 0028 — Watchlist 기반 Ingestion Worker (1~5초 polling — 본 ADR이 polling 주기 5s 결정)
- ADR 0014 — 시세 데이터 Fallback 3-tier (T1·T2·T3 — 본 ADR이 T1.5 RECONNECTING 추가)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (KIS WS tick → Redis candle:current 갱신)
- ADR 0037 — KIS Rate Limit 강제 (polling 풀 size 한도)
- ADR 0038 — KIS OAuth 24h 토큰 + Redis 분산락 (KIS REST polling이 의존)
- ADR 0040 — 관심종목 우선 UI 흐름 (Watchlist 진입점)
- ADR 0042 — Position Bootstrap & Recovery (`H0STCNI0` 체결통보 stream 정합)
- ADR 0044 — Notification System (`kis.ws.disconnected.v1` 후속 합류)
- ADR 0045 — Graceful Shutdown (SIGTERM 시 WS unsubscribe + close)
- `external-api-policy.md` §2-1 — KIS WS 41 한도 권위
- `candle-data-policy-v2.md` §6 — cold-start 흐름
- KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_*.py` — `H0STCNT0`·`H0STASP0`·`H0STCNI0` TR_ID 출처

## 7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0048 본문 발행 v1 (architect, opus 4.7 1M). README §12 후속 예약 → §5 시장 데이터 카테고리로 이동. T-W2 진입(PR-9) 시점에 정책 LOCK — slot 예산(1+30+10), priority eviction + polling fallback, WS 끊김 시 5초 polling 자동 전환, Closed session 시 unsubscribe + slot 회수, watchlist.changed.v1 ADDED/REMOVED 매트릭스, Yahoo cold-start 7 interval × 1회 트리거. |
