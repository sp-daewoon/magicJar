# ADR 0037 — KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷)

- 상태: **Accepted (2026-05-19 amend — Bucket4j-Redis 단일 bucket + KisRateLimiterPort domain port + EGW00201/HTTP 429 backoff 매트릭스 LOCK · Phase 1 backend-core 후 운영 코드 통합) · §2-1 단일 bucket LOCK + §2-3 share 분할(api 5/batch 3/consumer 2)은 2026-06-08 ADR 0116으로 supersede (priority별 별도 버킷 3-tier — USER 4/SHARED 3/BG 3 합 10). 본 ADR §1·§2-2 backoff·§2-4 빌드 게이트는 현행 권위 유지.**

> **⚠️ supersede 마커 (2026-06-08 — ADR 0116)**: 본 ADR §2-1 "단일 bucket LOCK" + §2-3 "멀티 JVM share 분할(5/3/2)"은 운영 진화로 **ADR 0116 (priority별 별도 버킷 3-tier)** 가 supersede 한다. commit `2837b2cf`(2026-06-05)가 단일→2-bucket reserve로 진화시켰고, ADR 0116이 이를 정식화 + 3-tier(USER/SHARED/BG)로 확장한다. 본 §2-1·§2-3 본문은 historical evidence로 보존(읽기 전용). **현행 rate limit 권위는 ADR 0116 + external-api-policy.md §2-2.** 본 ADR §1(KIS 한도 컨텍스트)·§2-2(EGW00201 backoff)·§2-4(빌드 게이트)·`KisCallPriority` enum(0100 amend INGESTION_LOW 포함)은 현행 유지.
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0003(KIS OpenAPI), ADR 0028(Watchlist Ingestion), ADR 0036(KIS OAuth 분산락), ADR 0038(KIS Token store), ADR 0044(Notification), ADR 0083(indicator-impl 모듈 패턴), ADR 0085·0087(cross-review 페르소나), ADR 0088(persistence-shared 패턴 — 본 ADR 어댑터 모듈 위치 옵션 b 1:1 재사용)
- 참조 spec: `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md`

## 1. 컨텍스트

KIS OpenAPI 공식 한도:
- **REST 호출 초당 10건** (계정·앱키당) — 위반 시 `EGW00201` 또는 HTTP 429
- 토큰 발급 1분당 1회 (별도, ADR 0036)
- WebSocket 동시 구독 41건

Rate Limit 위반은 단발 에러가 아니라 **계정·앱키 차단**으로 이어질 수 있다. 본체 매매·시세·주문 흐름 전체 중단의 risk.

magicJar는 multi-JVM(api·batch·consumer)에서 KIS REST를 동시 호출하므로 단순 어댑터별 제어로는 부족. **공통 RateLimiter 통과 의무**가 강제되어야 한다.

### 1-1. spec drift evidence (2026-05-19 amend · PR #555 market-data system audit)

market-data sub-agent 2026-05-19 시스템 점검 결과:
- ADR 0037 §1 LOCK "**REST 호출 초당 10건**" vs 코드 주석 **15+ 위치 "20 req/s"** 불일치 (실제 KIS 한도 절반 수준의 잘못된 mental model)
- `Thread.sleep(50ms)` 마진 = 20 req/s 가정 → KIS 한도(10 req/s) 대비 2배 가속 → 동시 다중 어댑터 burst 시 EGW00201 risk
- Bucket4j stub 상태 — `KisRateLimiterPort` 도메인 포트 0 + Bucket4j dependency 0 + adapter 직접 webClient 호출 + EGW00201 명시 backoff path 1곳 (`KisReconcileBalanceAdapter`) + HTTP 429 명시 path 1곳 (`CandleRefreshService:235`) + Circuit Breaker 0건 + ArchUnit 게이트 0건

→ 본 amend로 LOCK 재확정 + 메커니즘·port spec·backoff 매트릭스 정밀화 + Phase 분리.

**본 §1·§2 "초당 10건" LOCK** — 코드 주석 "20 req/s" 표기는 spec drift로 분류. spec/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md §3 정정 매트릭스 정합 의무.

## 2. 결정

### 2-1. 코드 레벨 강제 (2026-05-19 amend — Bucket4j-Redis 단일 bucket LOCK)

| 항목 | 정책 |
|---|---|
| **한도** | **초당 10건** (KIS 공식 LOCK). 마진 없이 정확히 10건 — 우선순위 큐로 사용자 path 보장 |
| **메커니즘 (옵션 A 채택)** | **Bucket4j 8.x + Redis backend 단일 bucket** — 모든 JVM share. share 분할(api 5/batch 3/consumer 2) 거부 (사용자 burst 흡수 0 risk) |
| **공통 진입점** | `domain/.../market/ratelimit/KisRateLimiterPort` — 모든 KIS REST adapter 호출 직전 `acquire(priority)` 통과 의무 |
| **adapter 모듈 위치** | **persistence-shared 패턴 1:1 재사용 권장 (ADR 0088 패턴)** 또는 신규 `kis-shared` 모듈 — multi-JVM 공유 Bean. 옵션 a (api 단독 발행) vs 옵션 b (shared 모듈 신설) Phase 1 backend-core 1차 결정 |
| **우회 금지** | 어댑터별 직접 RestClient/WebClient KIS endpoint 호출 금지 — ArchUnit + ktlint custom rule (Phase 3 별도) |
| **큐 우선순위** | `KisCallPriority` enum: `USER_PATH`(현재가·차트·주문) > `INGESTION`(Watchlist) > `BACKFILL`(corp action·reconcile) > **`INGESTION_LOW` (2026-05-24 amend — T-MARKET 지수·섹터 cron 정합 · ADR 0100 §2-8)**. Phase 1은 모두 동일 LOCK + Phase 2 합류 시 priority queue 활성화 (priority queue 활성 후 INGESTION_LOW가 burst 시 가장 먼저 drop 대상 — BACKFILL 보다 우선순위 낮음 사유: 정기 시장 추세 갱신은 user-facing latency 허용폭이 BACKFILL 보다 크고 1분 cron 재시도 자연스러움) |
| **leaky bucket** | 큐 가득 시 oldest BACKFILL 요청 drop (사용자 path는 drop 0). Phase 2 별도 |

**Resilience4j 거부 사유**: 분산 모드 미지원 — multi-JVM share 0 → share 분할(5/10·3/10·2/10) 필요. 사용자 burst 흡수 시 api 5 token 부족 risk. Bucket4j-Redis 단일 bucket이 본질.

### 2-2. EGW00201 / HTTP 429 backoff 매트릭스 (2026-05-19 amend — 정밀화)

| 응답 시그널 | 1차 처리 | 2차 처리 | 3차 처리 |
|---|---|---|---|
| `rt_cd != "0"` + `msg_cd == "EGW00201"` (TPS 초과) | 1초 fixed backoff + 1회 재시도 | 재시도 후도 EGW00201 → `KisRateLimiterPort.onTpsExceeded()` 호출 + 명시 에러 throw (mock 합성 0, ADR 0014 정합) | — |
| HTTP 429 | EGW00201과 동일 path | 동일 | — |
| 연속 5분 50% 이상 EGW00201/429 | `KisRateLimiterPort` Circuit Breaker **OPEN 5분** | Slack alert P1 (ADR 0044 `notification_outbox` reuse) | backfill 큐 자동 pause |
| Circuit Breaker OPEN 상태 | 모든 `acquire()` 즉시 throw `KisCircuitOpenException` | 사용자 path도 차단 — 명시 에러 응답 (mock 합성 0) | 5분 후 HALF_OPEN 자동 전환 (Bucket4j Listener 또는 Resilience4j Circuit Breaker 합류) |
| `acquire(timeoutMillis=1000)` timeout | throw `KisRateLimitTimeoutException` | caller가 priority 기반 retry 또는 사용자 에러 — adapter 별 결정 | — |

**backoff 알고리즘 채택 사유**: exponential backoff(1s·2s·4s) 옵션은 KIS TPS 한도가 hard limit이므로 과다 — fixed 1초 + 1회 재시도로 충분. Circuit Breaker가 누적 fail 대응.

### 2-3. 멀티 JVM 정합

| 모듈 | RateLimiter 인스턴스 |
|---|---|
| api (사용자 REST) | 5/10 share (사용자 path 우선) |
| batch (backfill·corp action) | 3/10 share |
| consumer (Ingestion·룰 엔진) | 2/10 share |

share 합계 = 10 (KIS 한도 정확). 동적 조정은 Redis pub/sub으로 가능 (post-MVP).

또는 **Bucket4j-Redis 분산 모드** — 단일 token bucket을 Redis에 두고 모든 JVM이 동시 acquire. share 분할 불요. **권장**.

### 2-4. 빌드 게이트 (CI 강제)

- ArchUnit test — `RestClient.get()` 직접 호출이 KIS 도메인 어댑터 외에서 발견 시 실패
- ktlint custom rule — `KisRateLimiterPort` 미경유 KIS endpoint 호출 검출
- 단위 테스트 fixture — 초당 10건 초과 호출 시 에러 검증 + CI 실패

## 3. 결과

- **KIS 차단 위험 0**
- **사용자 path 우선순위 보장** — backfill 폭주 시도 매매 흐름 중단 차단
- **multi-JVM 정합** — 풀 한도가 코드 레벨에서 강제

## 4. 참고

- KIS 공식 문서 — REST TPS 한도 (https://apiportal.koreainvestment.com/intro)
- ADR 0028 — Watchlist Ingestion (rate limit 큐 통과)
- ADR 0036 — KIS OAuth 분산락 (rate limit과 짝)
- ADR 0038 — KIS Token store Redis (Bucket4j Redis backend reuse)
- ADR 0044 — Notification System (Circuit Breaker alert path)
- ADR 0088 — persistence-shared 패턴 (adapter 모듈 위치 1:1 재사용 권장)
- `external-api-policy.md` §2 — 운영 매트릭스
- `domain/.../market/ratelimit/KisRateLimiterPort.kt` — 본 ADR 구현 진입점 (Phase 1 backend-core dispatch 후 신설)
- spec 본문: `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md`

## 5. 잔여 의제 (2026-05-19 amend — Phase 분리 LOCK)

### Phase 1 — backend-core 단일 PR (사용자 결재 후 dispatch)

| 의제 | 범위 |
|---|---|
| domain port 신설 | `KisRateLimiterPort` + `KisCallPriority` enum + `KisRateLimitTimeoutException` + `KisCircuitOpenException` |
| adapter 모듈 위치 1차 결정 | 옵션 a (api 단독) vs 옵션 b (persistence-shared 패턴 1:1 — ADR 0088 정합 / 신규 `kis-shared`) — backend-core 결정 |
| Bucket4j-Redis adapter 신설 | `RedisKisRateLimiterAdapter` — 10 token/sec capacity + refill 1s + Bean 발행 |
| Gradle dependency | `com.bucket4j:bucket4j-core:8.10.1` + `com.bucket4j:bucket4j-redis:8.10.1` |
| application.yml | Bucket4j capacity·refill 설정 + Redis 좌표 reuse (ADR 0038) |
| 단위 테스트 4건 | 초당 10건 정확 강제 + 11번째 throw + EGW00201 backoff + Circuit Breaker OPEN |
| 예상 LOC | ~300 |
| cross-review | 자본 path 직결 → chart-curator 1차(보수) + 모듈 경계 → backend-core 1차(진보) (ADR 0085·0087 정합) |

### Phase 2 — market-data 어댑터 통합 (Phase 1 머지 후 dispatch)

| 의제 | 범위 |
|---|---|
| 모든 KIS REST 어댑터에 `kisRateLimiterPort.acquire(priority)` 합류 | `KisRestCandleAdapter`·`CandleRefreshService`·`KisReconcileBalanceAdapter`·`KisOrderHttpAdapter`·`KisInquireBalanceHttpAdapter`·`KisChartCandleAdapter` 외 ~15 어댑터 |
| EGW00201 / HTTP 429 응답 처리 | `onTpsExceeded()` 호출 + 1초 backoff + 1회 재시도 + `onSuccess()` 호출 |
| spec drift 정정 | `20 req/s` → `초당 10건` replace_all + `Thread.sleep(50)` → acquire 도입으로 제거 (또는 sleep(100ms) 정합) |
| 예상 LOC | ~400 |

### Phase 3 — Circuit Breaker + 빌드 게이트 (별도 사이클)

| 의제 | 범위 |
|---|---|
| Circuit Breaker 통계 5분/50% 임계 구현 | Resilience4j 또는 Bucket4j Listener |
| Slack alert P1 | `notification_outbox` reuse (ADR 0044) |
| ArchUnit 빌드 게이트 | KIS adapter 외 RestClient/WebClient 직접 호출 detection |
| ktlint custom rule | KIS endpoint URL 직접 사용 detection |
| 예상 LOC | ~300 |
| dispatch agent | backend-core + qa |

## 6. 변경 이력

| 일자 | 변경 | 작성 |
|---|---|---|
| TBD (베이스라인) | ADR 0037 신설 — KIS API Rate Limit 강제 (초당 10건 + Bucket4j 또는 Resilience4j) | architect |
| 2026-05-19 | **amend — Bucket4j-Redis 단일 bucket LOCK (옵션 A 채택) + KisRateLimiterPort domain port spec + EGW00201/HTTP 429 backoff 매트릭스 정밀화 + Circuit Breaker OPEN 5분/50% 정합 + Phase 1·2·3 분리 LOCK + spec drift evidence(코드 주석 "20 req/s" 15+ 위치 vs ADR §1 "초당 10건" 권위 정합 의무) + adapter 모듈 위치 옵션 b(persistence-shared 패턴 1:1 재사용 ADR 0088 정합) 권장 + spec 본문 `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md` 동시 발행** — market-data PR #555 system audit P1-1·P1-2·P2-1 통합. Resilience4j 거부 사유 명시(분산 미지원 — share 분할 risk). cross-review chart-curator(보수) + backend-core(진보) 페르소나 매트릭스 정합. 운영 코드 0 touch (Kotlin/TS/SQL 0 변경 — ADR amend + spec + ADR README + master + backlog + CHANGELOG only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. Phase 1 backend-core dispatch 위임 (architect는 spec 발행까지). | architect (opus 4.7 1M) |
| 2026-05-24 | **amend — KisCallPriority enum 4단계 확장 (USER_PATH > INGESTION > BACKFILL > **`INGESTION_LOW` 신규**) — ADR 0100 T-MARKET cron 26 req/min (지수·섹터) 우선순위 분리 정합** + INGESTION_LOW 사유 본문(정기 시장 추세 갱신 user-facing latency 허용폭 BACKFILL 보다 크고 1분 cron 재시도 자연스러움 → burst 시 가장 먼저 drop 대상) + 한도 4.3% 점유 안전 검증 + Phase 2 priority queue 활성 시 drop 우선순위 LOCK. 사용자 brainstorming 결재 cec6a4d6 정합. orchestrator → architect R0 dispatch. ADR 0004·0026·0027·0094·0095·0100·0101 동반. 운영 코드 0 touch (Kotlin/TS/SQL 0 변경 — ADR amend + spec + ADR README + master + CHANGELOG only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. | architect (opus 4.7 1M) |
