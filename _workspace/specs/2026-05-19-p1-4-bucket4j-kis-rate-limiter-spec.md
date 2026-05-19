# P1-4 — KIS API Rate Limit 강제 (Bucket4j 실 구현 + KisRateLimiterPort + EGW00201/HTTP 429 backoff) spec

- 발행일: 2026-05-19
- 작성자: architect (opus 4.7 1M)
- ADR 정합: ADR 0037 amend (본 spec과 동시 갱신) · ADR 0003 · ADR 0028 · ADR 0036 · ADR 0038
- 관련 PR: market-data PR #555 (system audit — Rate Limit 가드 DEGRADED 보고)
- 운영 코드 변경: **0 (본 spec PR은 docs only — Phase 1·2 실 구현은 별도 dispatch)**
- KIS endpoint 호출 0 / AI 호출 0 / mock 합성 0 / 자본 영향 0

---

## §1. 컨텍스트

### 1-1. PR #555 system audit 발견 (market-data)

market-data sub-agent 2026-05-19 시스템 점검 보고서(`_workspace/reports/2026-05-19-system-audit-market-data.md`) §Rate Limit 가드 — **ADR 0037 Bucket4j stub 상태 (DEGRADED)** 확정:

| 항목 | 현재 상태 | ADR 0037 LOCK |
|---|---|---|
| `KisRateLimiterPort` (domain) | **미구현** | §2-1 공통 진입점 의무 |
| `Bucket4j` dependency | `build.gradle.kts` 0 (api·batch·consumer) | §2-1 메커니즘 |
| 어댑터 호출 직전 `acquire()` | **부재** — 직접 `webClient.get()/post()` | §2-1 우회 금지 |
| EGW00201 backoff | `KisReconcileBalanceAdapter` 1곳만 partial 구현 | §2-2 1초 + 1회 재시도 |
| HTTP 429 backoff | `CandleRefreshService.kt:235` 1곳만 partial 구현 | §2-2 EGW00201과 동일 |
| Circuit Breaker 5분 차단 | **0건** | §2-2 연속 5분 50% 이상 fail |
| ArchUnit 빌드 게이트 | **0건** | §2-4 CI 강제 |

→ **사용자 트리거 burst** (16종목 × 5 timeframe = 80건/burst) 또는 **batch backfill 폭주** 시 KIS 한도(초당 10건) 침범 risk. 계정·앱키 차단 발생 시 본체 매매·시세·주문 흐름 전체 중단.

### 1-2. spec 표기 drift evidence

market-data PR #555 P2-1 의제 — ADR 0037 §1 LOCK **"REST 호출 초당 10건"** vs 코드 주석 **15+ 위치 "20 req/s"** 불일치 (KIS 공식 한도 절반 수준의 잘못된 mental model 운영 중):

| 파일 | line | 잘못된 주석 |
|---|---|---|
| `api/CandleRefreshService.kt` | 42 | "Rate Limit (ADR 0037): 20 req/s 한도. interval 간 50ms 마진." |
| `api/CandleRefreshService.kt` | 132 | "if (index > 0) Thread.sleep(50) // 20 req/s 한도 준수" |
| `consumer/KisRestCandleAdapter.kt` | 41·88·155·340·347·575 | 6 위치 모두 "20 req/s" 주석 |
| `consumer/LastCandleAtMonitor.kt` | 40·227 | "ADR 0037 — 20 req/s" |
| `domain/CandleGapDetectorPort.kt` | 36 | "KIS 호출 cost 보호 (ADR 0037 — 20 req/s)" |

→ ADR 0037 본문은 정상(초당 10건) but 코드 주석이 drift. **현재 운영 sleep(50ms) = 20 req/s 가정 마진** → 실제 KIS 한도 초당 10건 대비 2배 가속 → 동시 다중 어댑터 호출 시 burst 누적으로 EGW00201 risk.

### 1-3. 사용자 비전 정합

자본 흐름 차단 risk:
- KIS 계정 차단 = 본체 자동매매 전체 중단 = 자본 보호 catastrophic
- 사용자 path(현재가·차트·주문) 우선순위 보장 의무
- multi-JVM(api·batch·consumer) 분산 정합 필수

---

## §2. 결정 매트릭스 (architect 권장 옵션 채택)

### 2-1. 메커니즘 선택 — Bucket4j-Redis 단일 bucket (옵션 A 채택)

| 옵션 | 메커니즘 | 장점 | 단점 | 채택 |
|---|---|---|---|---|
| **A** | **Bucket4j 8.x + Redis backend** (단일 bucket, 모든 JVM share) | 단일 bucket = KIS 한도 정확 정합 / share 분할 불요 / proven 라이브러리 | Redis 의존 (이미 운영 중 — 추가 부담 0) | **★ 채택** |
| B | Resilience4j RateLimiter (분산 미지원) | Spring 친화 / 가벼움 | multi-JVM share 0 — share 분할 필요 (5/10 + 3/10 + 2/10) | 거부 (분산 부재) |
| C | 자체 토큰 버킷 + Redis Lua | full 제어 | 운영 부담 ↑ / proven 0 | 거부 |
| D | Spring Cloud Gateway RateLimiter | Spring 친화 | Gateway 의존 (현재 무사용) | 거부 |

**채택 사유 (옵션 A — Bucket4j-Redis)**:
1. **단일 bucket 모델** = KIS 한도 정확 강제 (10 token/sec 단일 share). share 분할(api 5/batch 3/consumer 2) 시 사용자 burst 흡수 0 risk
2. **proven**: Bucket4j 8.x는 200+ production user · Redis backend 공식 지원 (`bucket4j-redis` artifact)
3. **Redis 재사용**: ADR 0038(KIS OAuth 토큰 store) + ADR 0028(Watchlist queue) 이미 Redis 운영 — 신규 인프라 0
4. **분산 정합**: api·batch·consumer 3 JVM 동시 acquire 시 단일 bucket race-free (Bucket4j Lua script atomicity)
5. **우선순위 큐**: Bucket4j는 native priority 미지원 but Redis-side LPUSH/RPOP 기반 caller queue로 보강 가능 (Phase 2 옵션)

### 2-2. KisRateLimiterPort domain port spec

domain 순수 port (ADR 0004 §모듈 경계 — Spring·JPA 의존 0):

```kotlin
// domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/ratelimit/KisRateLimiterPort.kt
package kr.spdaewoon.magicjar.domain.market.ratelimit

/**
 * KIS OpenAPI REST 호출 Rate Limit 강제 port (ADR 0037).
 *
 * - KIS 공식 한도: 초당 10건 (계정·앱키당, EGW00201/HTTP 429 위반 시)
 * - multi-JVM(api·batch·consumer) 단일 bucket share 의무
 * - 모든 KIS REST adapter 호출 직전 `acquire()` 통과 의무
 * - 우회 금지 (ArchUnit/ktlint 빌드 게이트 — 별도 사이클)
 *
 * raw 출처 (헌법 §3 KIS-first):
 * - KIS 공식 한도 문서: https://apiportal.koreainvestment.com/intro
 *   ("REST API 한도: 초당 10건")
 * - EGW00201 응답 raw: open-trading-api/examples_llm/* 다수 위치
 */
interface KisRateLimiterPort {
    /**
     * KIS REST 호출 직전 token 1개 acquire.
     *
     * @param priority 우선순위 (USER_PATH > INGESTION > BACKFILL). Phase 1은 모두 동일 LOCK.
     * @param timeoutMillis acquire 최대 대기 (default 1000ms). 초과 시 throw [KisRateLimitTimeoutException].
     * @throws KisRateLimitTimeoutException timeout 초과 시
     */
    fun acquire(priority: KisCallPriority = KisCallPriority.INGESTION, timeoutMillis: Long = 1000L)

    /**
     * EGW00201 / HTTP 429 응답 발생 알림. Circuit Breaker 통계 누적.
     * adapter 응답 처리 path에서 호출 의무.
     */
    fun onTpsExceeded()

    /**
     * 정상 응답 알림. Circuit Breaker fail rate 분모 누적.
     */
    fun onSuccess()
}

enum class KisCallPriority {
    /** 사용자 직접 path (현재가·차트·주문). Phase 2에서 우선 처리 */
    USER_PATH,

    /** Watchlist Ingestion (1~5초 polling) */
    INGESTION,

    /** backfill·corp action·reconcile (백그라운드) */
    BACKFILL,
}

class KisRateLimitTimeoutException(
    val priority: KisCallPriority,
    val timeoutMillis: Long,
) : RuntimeException("KIS rate limit acquire timeout: priority=$priority timeoutMillis=$timeoutMillis")
```

### 2-3. Bean wiring 매트릭스 (Phase 1 backend-core)

| 모듈 | adapter 클래스 | 책임 |
|---|---|---|
| `api` (또는 별도 `persistence-shared` 패턴 — ADR 0088 정합) | `RedisKisRateLimiterAdapter` | Bucket4j-Redis backend + Bean 발행 + Circuit Breaker 통계 |
| `batch` | (의존만, adapter 0) | api adapter Bean lookup |
| `consumer` | (의존만, adapter 0) | api adapter Bean lookup |

**의존 방향 (ADR 0004 §모듈 경계)**: `api → domain` LOCK / `batch·consumer → domain` LOCK / 어댑터 발행 위치는 **persistence-shared 패턴 1:1 재사용 권장** (ADR 0088 패턴 정합 — Phase 1 backend-core dispatch 시 옵션 명시).

대안 옵션:
- 옵션 a: api 단독 adapter — Spring `@Bean`을 api ApplicationContext만 발행 (batch·consumer는 별도 Bean 발행 필요)
- 옵션 b: **persistence-shared 모듈 (ADR 0088) 또는 신규 `kis-shared` 모듈로 이관** — 3 JVM 모두 동일 Bean 공유 ★ 권장

→ **권장 옵션 b** (ADR 0088 패턴 본질 — multi-JVM 공유 어댑터의 모듈 경계 정합). Phase 1 backend-core가 결정·실행 (architect는 spec까지).

### 2-4. EGW00201 / HTTP 429 backoff 매트릭스

| 응답 시그널 | 1차 처리 | 2차 처리 | 3차 처리 |
|---|---|---|---|
| `rt_cd != "0"` + `msg_cd == "EGW00201"` | 1초 backoff + 1회 재시도 | 재시도 후도 EGW00201 → `onTpsExceeded()` + 사용자 에러 throw (mock 합성 0, ADR 0014) | — |
| HTTP 429 | 1초 backoff + 1회 재시도 | 재시도 후도 429 → `onTpsExceeded()` + 사용자 에러 throw | — |
| 연속 5분 내 50% 이상 EGW00201/429 | **Circuit Breaker OPEN 5분** | Slack alert P1 (ADR 0044 notification_outbox) | backfill 큐 자동 pause |
| Circuit Breaker OPEN 상태 | `acquire()` 즉시 throw `KisCircuitOpenException` | 사용자 path도 차단 — 명시 에러 응답 | 5분 후 HALF_OPEN 자동 전환 |

**backoff 알고리즘 (architect 권장 — exponential 거부, fixed 1초 채택)**:
- exponential backoff (1s·2s·4s 3회) 옵션은 KIS TPS 한도가 hard limit이므로 과다 — 1초 + 1회 재시도로 충분
- 5분 통계 윈도는 Bucket4j Listener 또는 Resilience4j Circuit Breaker 합류 (Phase 1 결정)

### 2-5. 우회 금지 빌드 게이트 (Phase 2 별도 사이클)

| 게이트 | 메커니즘 | 시점 |
|---|---|---|
| ArchUnit | `@ArchTest` — `RestClient`/`WebClient` 직접 호출이 KIS adapter 외에서 발견 시 fail | Phase 2 (별도 사이클) |
| ktlint custom rule | KIS endpoint URL 직접 사용 detection | Phase 2 (별도 사이클) |
| 단위 테스트 fixture | 초당 10건 초과 호출 시 throw 검증 | Phase 1 (Phase 1 본 PR에 포함) |

**Phase 1 LOCK**: 빌드 게이트는 Phase 2 후속 사이클. Phase 1은 Port + adapter + EGW00201 backoff만.

---

## §3. spec drift 정정 매트릭스

### 3-1. 코드 주석 정정 대상 (Phase 1 dispatch backlog — market-data 영역)

market-data sub-agent 별도 dispatch (architect 본 PR은 spec만, 운영 코드 0 touch):

| 파일 | line | before | after |
|---|---|---|---|
| `api/CandleRefreshService.kt` | 42 | `Rate Limit (ADR 0037): 20 req/s 한도. interval 간 50ms 마진.` | `Rate Limit (ADR 0037): 초당 10건 한도. interval 간 100ms 마진 (KisRateLimiterPort.acquire() Phase 1 합류 시 sleep 제거).` |
| `api/CandleRefreshService.kt` | 132 | `if (index > 0) Thread.sleep(50) // 20 req/s 한도 준수` | `if (index > 0) Thread.sleep(100) // 초당 10건 한도 준수 (Phase 1 KisRateLimiterPort.acquire() 합류 시 제거)` |
| `consumer/KisRestCandleAdapter.kt` | 41·88·155·340·347·575 | 6 위치 `20 req/s` | `초당 10건` 일괄 replace_all |
| `consumer/LastCandleAtMonitor.kt` | 40·227 | `ADR 0037 — 20 req/s` | `ADR 0037 — 초당 10건` |
| `domain/CandleGapDetectorPort.kt` | 36 | `KIS 호출 cost 보호 (ADR 0037 — 20 req/s)` | `KIS 호출 cost 보호 (ADR 0037 — 초당 10건)` |
| `api/CandleRefreshService.kt` | 298 | `if (pageCount > 0) Thread.sleep(50) // Rate Limit ADR 0037` | `if (pageCount > 0) Thread.sleep(100) // Rate Limit ADR 0037 초당 10건` |
| `consumer/KisRestCandleAdapter.kt` | 341 | `Thread.sleep(50)` | `Thread.sleep(100)` (주석 동반 정정) |

**중요**: 본 spec PR(architect)는 **운영 코드 0 touch LOCK**. 위 정정은 market-data sub-agent 별도 dispatch backlog 의제 (Phase 1 합류 시 일괄 또는 즉시 정정 선택).

### 3-2. sleep margin 정합

현재 `Thread.sleep(50ms)` = 20 req/s 가정. 실제 KIS 한도 10 req/s LOCK → **sleep(100ms) 마진이 정합**. Phase 1 `KisRateLimiterPort.acquire()` 합류 시 sleep 일괄 제거 + adapter 내부 `kisRateLimiterPort.acquire()` 호출 의무.

---

## §4. Phase 분리 매트릭스 (사용자 결재 후 sub-agent dispatch)

### Phase 1 — backend-core 단일 PR (LOCK)

**범위**:
1. domain 신규 port — `KisRateLimiterPort` + `KisCallPriority` enum + `KisRateLimitTimeoutException` + `KisCircuitOpenException`
2. 어댑터 모듈 선택 (Phase 1 옵션 b 권장 — persistence-shared 또는 신규 kis-shared) — backend-core 1차 결정
3. Bucket4j-Redis adapter 신설 — `RedisKisRateLimiterAdapter` (10 token/sec, refill 1s) + Bean 발행
4. Gradle dependency — `com.bucket4j:bucket4j-core:8.10.1` + `com.bucket4j:bucket4j-redis:8.10.1` (또는 Lettuce backend)
5. `application.yml` — Bucket4j capacity·refill 설정 + Redis 좌표 reuse
6. 단위 테스트 — 초당 10건 정확 강제 + 11번째 throw + EGW00201 backoff 1초 + Circuit Breaker OPEN 5분 (4건)

**예상 LOC**: ~300 (domain port 80 + adapter 150 + Gradle 4 + yml 10 + test 4건 ~50)

**cross-review**: 자본 path 직결 → chart-curator 1차 lens (보수 🔴/🟡/🔵) + 인프라 모듈 경계 → backend-core 1차 lens (진보 💡/🔄/✨) (ADR 0085·0087 정합)

### Phase 2 — market-data 어댑터 통합 dispatch (Phase 1 머지 후)

**범위**:
1. 모든 KIS REST 어댑터 호출 직전 `kisRateLimiterPort.acquire(priority=...)` 통과 의무 합류
   - `KisRestCandleAdapter` (consumer · 6 호출 site)
   - `CandleRefreshService` (api · 2 호출 site)
   - `KisReconcileBalanceAdapter` (consumer · 1 호출 site — 기존 EGW00201 backoff 제거 + port에 위임)
   - `KisOrderHttpAdapter` (consumer · 주문 path — USER_PATH priority)
   - `KisInquireBalanceHttpAdapter` (api · 계좌 — USER_PATH priority)
   - `KisChartCandleAdapter` (api · 차트 — USER_PATH priority)
   - 외 ~15 어댑터 (PR #555 system audit §매핑 매트릭스)
2. EGW00201 / HTTP 429 응답 처리 — `onTpsExceeded()` 호출 + 1초 backoff + 1회 재시도
3. spec drift 정정 — `20 req/s` → `초당 10건` replace_all + sleep(50ms) → sleep(100ms) (또는 acquire 도입으로 sleep 제거)

**예상 LOC**: ~400 (어댑터 ~20개 × 호출 site 1~2건 acquire 합류 + 주석 정정)

**cross-review**: 자본 path 직결 + 모듈 경계 동시 → chart-curator + backend-core 동시 1차 lens

### Phase 3 — Circuit Breaker + 빌드 게이트 (별도 사이클)

**범위**:
1. Circuit Breaker 통계 윈도 5분 50% 임계 구현 (Resilience4j 또는 Bucket4j Listener)
2. Slack alert P1 발행 — `notification_outbox` reuse (ADR 0044 정합)
3. ArchUnit 빌드 게이트
4. ktlint custom rule

**예상 LOC**: ~300

**dispatch agent**: backend-core (Circuit Breaker) + qa (ArchUnit + ktlint)

---

## §5. 의존성·정합성 매트릭스

### 5-1. ADR 정합

| ADR | 정합 |
|---|---|
| ADR 0003 | KIS OpenAPI 사용 — 본 spec rate limit이 호출 baseline |
| ADR 0028 | Watchlist Ingestion — Phase 2 priority=INGESTION 적용 |
| ADR 0036 | KIS OAuth 분산락 — 본 rate limit과 짝 (token + bucket 분리) |
| ADR 0037 | **본 spec amend 대상 LOCK** |
| ADR 0038 | KIS Token store Redis — Bucket4j Redis backend reuse |
| ADR 0044 | Notification System — Circuit Breaker alert path |
| ADR 0057 | KIS 단일 출처 — 본 rate limit 적용 대상 권위 |
| ADR 0083 | indicator-impl 패턴 — 본 spec 어댑터 모듈 위치 결정 시 1:1 재사용 옵션 |
| ADR 0085 | chart-curator 자본 path 보수 페르소나 cross-review |
| ADR 0087 | backend-core 진보 페르소나 cross-review |
| ADR 0088 | persistence-shared 패턴 — 본 spec 어댑터 위치 옵션 b 1:1 재사용 권장 |

### 5-2. 사용자 메모리 정합

| 메모리 | 정합 |
|---|---|
| `feedback_kis_first_policy` | KIS-only 데이터 정책 — 본 rate limit 강제 = 차단 risk 영구 차단 |
| `feedback_v3_skepticism_no_regression` | 자동매매 fitness — Rate Limit 위반 = 자본 흐름 catastrophic |
| `feedback_no_mock_data` | EGW00201 발생 시 mock 합성 0 — 명시 에러 throw 채택 (ADR 0014 정합) |
| `feedback_dev_priority_qa_batch` | Phase 1 단일 PR + Phase 2·3 후속 — 묶음 dispatch 의제 잔류 |
| `feedback_release_policy_patch_first` | v3.6.x patch 후보 (W4 마감 시 minor) |
| `feedback_verification_strict` | Phase 1 dogfooding evidence 의무 — 11번째 호출 throw 검증 + EGW00201 mock 응답 backoff 검증 |
| `feedback_qa_on_demand` | QA 게이트 강제 0 — Phase 1 단위 테스트 4건 baseline |

### 5-3. 운영 코드 0 touch LOCK

본 architect spec PR은 **Kotlin/TS/SQL 0 변경**:
- ADR 0037 amend (docs)
- spec 본문 신설 (docs)
- ADR README 갱신 (docs)
- master §10·§13 갱신 (docs)
- backlog §추가 (docs)
- CHANGELOG row (docs)

**자본 영향 0** + **KIS endpoint 호출 0** + **AI 호출 0** + **mock 합성 0**.

---

## §6. 검증 절차 (Phase 1 dogfooding evidence 의무)

### 6-1. 단위 테스트 4건 (Phase 1 본 PR 의무)

1. **rate limit 정확 강제**: 1초 내 10건 acquire GREEN + 11번째 acquire 1초 timeout throw `KisRateLimitTimeoutException`
2. **EGW00201 backoff**: KIS mock 응답 `rt_cd="1" msg_cd="EGW00201"` → 1초 backoff + 1회 재시도 + 재시도도 fail 시 `onTpsExceeded()` 호출 + 명시 에러 throw
3. **HTTP 429 backoff**: HTTP 429 응답 → EGW00201과 동일 path
4. **Circuit Breaker OPEN**: 5분 내 50% 이상 fail → `KisCircuitOpenException` throw + Slack outbox row insert

### 6-2. dogfooding evidence (Phase 1 머지 후)

- docker logs `consumer-app` — KisRateLimiterPort acquire log INFO 1건 이상
- Redis CLI — `bucket4j:kis:rest:tokens` key 존재 + TTL 1s 갱신
- KIS 모의 burst (11건 동시 호출) — 11번째 timeout 또는 backoff log
- audit_trail (선택) — `KIS_RATE_LIMIT_EXCEEDED` action 1건 이상

### 6-3. 회귀 방지 (Phase 1 머지 후)

- `Thread.sleep(50)` grep 0건 LOCK (Phase 2 정정 후)
- `20 req/s` 주석 grep 0건 LOCK (Phase 2 정정 후)
- KIS endpoint 직접 호출 (`webClient.get("https://openapi.koreainvestment.com")` 등) — KIS adapter 외 0건 LOCK (Phase 3 ArchUnit 합류 후)

---

## §7. Rollback 시나리오

| 시나리오 | 처리 |
|---|---|
| Phase 1 머지 후 KIS 호출 timeout 폭증 | Bucket4j capacity 임시 12·15로 ↑ (env `MAGICJAR_KIS_RATE_LIMIT_BURST=15` toggle) — but 사용자 알림 필수 (KIS 차단 risk 동반) |
| Redis 다운 | adapter `@ConditionalOnBean(RedissonClient)` 미충족 → fallback adapter (in-memory Caffeine bucket per JVM) — share 분할 unavoidable. 사용자 path만 ALLOW + backfill SUSPEND |
| Circuit Breaker false positive | env `MAGICJAR_KIS_CIRCUIT_BREAKER_THRESHOLD` 75%로 완화 + 윈도 10분 확장 (기본 50%/5분) |
| Bucket4j 라이브러리 결함 | 옵션 c 자체 토큰 버킷(Redis Lua) fallback — backlog §X 의제 |

본 spec/ADR 0037 amend rollback은 ADR 0037 §1 LOCK 복원 + spec 본문 revert + Phase 1 PR revert. Phase 2 어댑터 정정은 add-only이므로 revert는 acquire 호출 제거(원복 sleep 100ms 단독 path 보존).

---

## §8. 비범위 (out of scope)

- WebSocket 41건 구독 한도 강제 — ADR 0048 별도 (본 spec은 REST 한정)
- KIS OAuth 토큰 발급 한도 (1분 1회) — ADR 0036·0038 별도
- DART API 한도 — `external-api-policy.md` §4 별도
- Naver Open API 한도 (분당 17건) — `WatchlistNewsScheduler` 별도 path
- AI provider rate limit — W4 별도

---

## §9. 변경 이력

| 일자 | 변경 | 작성 |
|---|---|---|
| 2026-05-19 | 신설 — P1-4 ADR 0037 Bucket4j 실 구현 spec/plan + KisRateLimiterPort port + Bucket4j-Redis 옵션 A 채택 + EGW00201/HTTP 429 backoff 매트릭스 + spec drift 정정 매트릭스 + Phase 1/2/3 분리 LOCK | architect (opus 4.7 1M) |
