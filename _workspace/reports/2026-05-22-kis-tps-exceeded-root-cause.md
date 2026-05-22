# KIS_TPS_EXCEEDED LIVE Root Cause 진단 보고서

- **작성일**: 2026-05-22 15:30 KST
- **작성**: market-data sub-agent (재디스패치 — 사실 정정 후 진단)
- **PR**: agent/market-data/kis-tps-live-hotfix
- **task**: T-20260522-1520-worktree-agent-ac3429fd80097cb7b
- **연관 ADR**: 0037 (Rate Limit 강제) · 0087 (cross-review 페르소나) · 0094 (운영 단계)
- **연관 spec**: `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md`

---

## §1. 배경 evidence

`magicjar-api` 컨테이너 **12:58~14:22 KST 2시간** 운영 LIVE evidence:

| 지표 | 값 | 분당 평균 |
|------|-----|-----------|
| TPS exceeded signal **누적 카운터** | **count=109** | ~1.3건/분 |
| 5분 window aggregator SystemEvent emit | **4건** | 5분당 1회 LOCK (spam 회피) |
| "캔들 갱신 Rate Limit status=500" 로그 | **104건** | ~1.3건/분 |
| MAX_PAGES 도달 | **16건** | — |
| `LiveDailyCandleScheduler` 6 종목 1d ok/fail | **ok=3 fail=3** | **fail rate 50%** |

**증상 인과**:
- `CandleRefreshService:294, 509` `kisRateLimiterPort.acquire(USER_PATH, 3000ms)` 정상 통과 (timeout exception 0건)
- KIS REST 호출 후 **HTTP 500 + EGW00201 wrap 응답** → `WebClientResponseException` catch (line 426~444)
- `classifyKisError()` → `KisErrorClass.RateLimit` 분기 (line 428) → `onTpsExceeded()` emit (line 430)
- IntervalRefreshResult status="RATE_LIMITED" → caller `LiveDailyCandleScheduler` fail++ 분기

**즉 Bucket4j는 acquire timeout 0건 — 통계상 capacity 통과 GREEN**인데도 **KIS server-side에서 차단**됨.

---

## §2. Bucket4j config 매트릭스 (옵션 A 검증)

### 2-1. ADR 0037 §1 LOCK vs 실제 config drift 매트릭스

| 항목 | ADR 0037 LOCK | api `KisRateLimitProperties.kt` | batch `BatchKisRateLimitProperties.kt` | consumer `ConsumerKisRateLimitProperties.kt` | drift? |
|------|---------------|--------------------------------|------------------------------|--------------------------------|--------|
| `bucketCapacity` | 10 | **10** | **10** | **10** | **GREEN** |
| `bucketRefillTokens` | 10 | **10** | **10** | **10** | **GREEN** |
| `bucketRefillPeriodSeconds` | 1 | **1** | **1** | **1** | **GREEN** |
| `bucketKey` (multi-JVM share LOCK) | 단일 LOCK | `kis-rate-limit:global` | `kis-rate-limit:global` | `kis-rate-limit:global` | **GREEN — 동일 LOCK** |
| `bucketTtlSeconds` | refill × 10 | 10 | 10 | 10 | **GREEN** |
| `acquirePollIntervalMillis` | (구현 detail) | 50 | 50 | 50 | **GREEN** |

### 2-2. application.yml override 매트릭스 (`api/src/main/resources/application.yml:145~156`)

```yaml
magicjar:
  kis:
    rate-limiter:
      enabled: ${MAGICJAR_KIS_RATE_LIMIT_ENABLED:true}
      bucket-capacity: ${MAGICJAR_KIS_RATE_LIMIT_BURST:10}        # rollback: 12·15 임시 ↑ 가능
      bucket-refill-tokens: 10
      bucket-refill-period-seconds: 1
      bucket-key: kis-rate-limit:global
```

- env override **`MAGICJAR_KIS_RATE_LIMIT_BURST`** 미설정 시 default=10 적용
- LIVE 환경에서 `MAGICJAR_KIS_RATE_LIMIT_BURST` env 미주입 가정 (rollback 임시 ↑ trigger 없음)

### 2-3. Bucket4j refillGreedy 알고리즘 인과

```kotlin
// RedisKisRateLimiterAdapter.kt:90~96 + BatchRedis + ConsumerRedis 동일
.capacity(props.bucketCapacity)          // 10
.refillGreedy(
    props.bucketRefillTokens,            // 10
    Duration.ofSeconds(props.bucketRefillPeriodSeconds),  // 1s
)
```

- **`refillGreedy(10, 1s)`** = "1초당 10 token 회복을 0.1초 단위 1 token 작게 step 회복" (균등 분배)
- capacity=10 = idle 후 burst **10 token 즉시 소비 가능**
- **이론적으로 1초 sliding window 안에서 10건 정확 LOCK**, 그러나 burst 패턴은 *0.0s에 10건 즉시* → KIS server-side가 *sub-second 분해* 한도라면 위반 가능

### 2-4. 결론 (옵션 A 판정)

**옵션 A (Bucket4j config drift hotfix) — REJECTED.**

- api·batch·consumer 3 Properties + application.yml 모두 ADR 0037 LOCK 정합 GREEN
- multi-JVM 단일 bucketKey share LOCK + Redis Lua atomic acquire = config 차원 race condition 0
- 즉 결함은 config가 아닌 **caller 패턴 또는 KIS server-side 한도 mental model mismatch**

---

## §3. multi-instance race 검증 (옵션 C 검증)

### 3-1. Bucket4j-Redis 분산 acquire 인과

- `LettuceBasedProxyManager` (api·batch·consumer 동일 패턴) — Bucket4j 8.x Lua script로 Redis-side atomic CAS 보장
- 각 모듈 자체 `RedisClient` Bean 별도 발행 (Spring Data Redis와 별도 — Bucket4j codec 요구사항)
- 그러나 **bucket Redis key는 동일**: `kis-rate-limit:global` → 3 모듈이 동일 ZSET 키 share
- Lettuce backend는 Redis 단일 키 atomic acquire 보장 — multi-JVM race condition 0

### 3-2. 결론 (옵션 C 판정)

**옵션 C (multi-instance race fix) — REJECTED.**

- Bucket4j-Redis Lua atomic GREEN
- 운영 LIVE evidence(count=109 누적)은 multi-JVM 단일 bucket에서 정확히 집계되는 결과 — race condition으로 인한 "Bucket4j 자체는 통과 OK 표시인데 KIS 차단" 패턴 아님

---

## §4. caller 매트릭스 분석 — burst 패턴 진단 (옵션 B 핵심)

### 4-1. 19 KIS REST 어댑터 acquire 합류 현황 (Phase 2 결과 + 본 디스패치 추가 audit)

| 모듈 | adapter | acquire site | priority | timeout |
|------|---------|-------------|----------|---------|
| api | `KisRestPriceMetricsAdapter:77` | 사용자 현재가 | USER_PATH | 3000ms |
| api | `ApiKisBalanceQueryAdapter:138` | 사용자 잔고 | USER_PATH | 3000ms |
| api | `ApiKisOrderCancelAdapter:73` | 사용자 주문 취소 | USER_PATH | 3000ms |
| api | `ApiKisHashKeyAdapter:50` | 주문 해시키 | USER_PATH | 3000ms |
| api | `CandleRefreshService:294` (refreshDailyCandles 페이지마다) | **사용자/scheduler 캔들 갱신 1d·1wk·1mo·1y** | USER_PATH | 3000ms |
| api | `CandleRefreshService:509` (refreshOneMinCandles 페이지마다) | **사용자/scheduler 1m 캔들 갱신** | USER_PATH | 3000ms |
| batch | `KisInstrumentMasterAdapter:352` | 종목 마스터 sync (13시각 cron) | BACKFILL | 5000ms |
| batch | `KisChkHolidayAdapter:132` | 영업일 정보 | BACKFILL | 5000ms |
| batch | `KisKsdInfoAdapter:292` | 6 ksdinfo endpoint | BACKFILL | 5000ms |
| consumer | `KisOrderHttpAdapter:152, 330` (cash·credit 2 path) | 실시간 주문 | USER_PATH | 3000ms |
| consumer | `KisReconcileBalanceAdapter:187` | 5분 cron 잔고 재조정 | BACKFILL | 5000ms |
| consumer | `KisRestPriceAdapter:78, 182` (price·inquire-price 2 path) | WS fallback 시세 | USER_PATH | 3000ms |
| consumer | `KisRestCandleAdapter:321, 476` (daily·1m 2 path) | Ingestion 캔들 백필 | BACKFILL | 5000ms |
| consumer | `KisRestCandleAdapter:913` | tick 합산 검증 | INGESTION | 3000ms |
| consumer | `KisHashKeyAdapter:105, 174` (2 path) | 주문 해시키 | USER_PATH | 3000ms |
| consumer | `KisOrderInquireDailyCcldHttpAdapter:149` | 일별 체결 | BACKFILL | 5000ms |
| consumer | `KisOrderInquireCcldHttpAdapter:212` | 당일 체결 | BACKFILL | 5000ms |
| consumer | `KisBalanceAdapter:72` | reconcile 잔고 | USER_PATH | 3000ms |

**매트릭스 검증 결과**:
- 19 어댑터 모두 acquire 합류 GREEN (Phase 2 PR #571 완료 정합)
- priority 분기 정합: USER_PATH=15 / BACKFILL=7 / INGESTION=1 (단일 bucket이므로 Phase 1 라우팅 효과 0 — Phase 2 후속에서 priority queue 활성화 예정)

### 4-2. @Scheduled cron 매트릭스 (burst caller 자체)

| Scheduler | 모듈 | 주기 | KIS 호출 수 (대략) | burst risk |
|-----------|------|------|--------------------|-----------|
| **`LiveDailyCandleScheduler` (api)** | api | **5분 fixedRate** | **6 종목 × 1d (50 페이지 max) = 최대 300 호출/cycle** | **★ HIGH (본 dispatch 핵심 burst caller)** |
| `WatchlistPollingWorker` (consumer) | consumer | 5초 fixedRate | 풀(최대 30) × 1 호출 / 5s = 6 req/s | LOW (5초마다 분산) |
| `BalanceReconcileJob` (consumer) | consumer | `0 */5 9-15 * * MON-FRI` (5분 cron) | 잔고 1건 + 체결 N건 | MEDIUM (USER_PATH 합류 시 LiveDailyCandle과 동시 trigger) |
| `OrderReconciliationWorker` (consumer) | consumer | 1분 fixedDelay | 미체결 주문 N건 | LOW |
| `OrderOutboxPublisherWorker` (consumer) | consumer | 5초 fixedDelay | 0~N (outbox depth 의존) | LOW |
| `TickFallbackEmitter` (consumer) | consumer | 10초 fixedDelay | 구독 종목 순회 (stub 단계) | LOW |
| `WatchlistIngestionWorker` (consumer) | consumer | 1분 cron | 세션 변경 감지 + N | LOW |
| `OvertimeSinglePriceSubscriber` (consumer) | consumer | 16:00·18:00 KST | WS 구독 (REST 0건) | LOW (LIVE 12:58~14:22 window 외) |
| `LastCandleAtMonitor` (consumer) | consumer | 매일 18:00 KST | 0건 (monitoring only) | 0 |
| `DailyCloseFinalizer` (consumer) | consumer | 매일 20:00 KST | 0건 (DB only) | 0 |
| `TickToMinuteAggregator` (consumer) | consumer | 매 분 0초 | 0건 (Redis only) | 0 |
| `DartDisclosurePollingScheduler` (api) | api | 1분 cron | DART 호출 (KIS 별개) | 0 |
| `WatchlistNewsScheduler` (api) | api | 1분 fixedRate | 네이버 호출 (KIS 별개) | 0 |

### 4-3. LiveDailyCandleScheduler 핵심 burst 분석

**`api/src/main/.../service/LiveDailyCandleScheduler.kt:42~88`**:

```kotlin
@Scheduled(fixedRate = 300_000L, initialDelay = 30_000L)  // 5분 cron
fun tick() {
    // ... 가드 + watchlist 조회 ...
    for (code in codes) {  // ← 종목 간 직렬 호출 + interval 0ms (sleep 마진 0)
        candleRefreshService.refresh(code, listOf("1d"))
        // ↑ refresh() 안에서 다시 페이지마다 acquire (max 50 페이지)
    }
}
```

**호출 패턴 burst 매트릭스**:
- 6 종목 × 1d × 평균 N 페이지 (5개월 갭 있으면 1~5 페이지 / 30년 갭이면 50 페이지 max)
- 종목 간 sleep 마진 **0ms** → 종목 1 완료 즉시 종목 2 진입
- 페이지 간 acquire는 Bucket4j가 알아서 100ms 마진 (refillGreedy 10/sec)
- 결과: **30~300 호출이 1~30초 burst** → Bucket4j 통과 OK라도 KIS server-side window 마진 0

### 4-4. CandleRefreshService 자체 burst 패턴

```kotlin
// CandleRefreshService.kt:288~396 — refreshDailyCandles loop
while (pageCount < maxPages) {  // 1d=50 페이지 max
    kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L)
    // ↑ Bucket4j 통과 (idle 후 10 burst 즉시 가능)
    val body = webClient.get()...block()  // KIS 호출
    // ↑ 페이지 간 sleep 마진 0 — acquire가 곧 마진
}
```

- 페이지 1 acquire 즉시 통과 (bucket=10) → KIS 호출 → 응답 ~200ms 받음 → 페이지 2 acquire (bucket=9) ... → 페이지 10 acquire (bucket=0) → 페이지 11 acquire 100ms 대기 후 통과
- 즉 첫 10 페이지는 **1초 이내에 모두 KIS로 발사**됨
- KIS server-side는 **sliding window 또는 다른 rate limiting 알고리즘** 사용 시 burst 거부 가능

### 4-5. KIS 실제 한도 mental model — sliding window vs token bucket 추정

- KIS 공식 문서 "REST 호출 초당 10건" — **fixed window vs sliding window 미명시**
- evidence: Bucket4j refillGreedy(10, 1s) 통과 + KIS는 EGW00201 ~13건/분 차단 → KIS는 더 보수적 알고리즘 사용 추정
- 가능한 KIS 알고리즘:
  - **(A) sliding window 1s**: 0.5~1.5s window에 20건 호출 시 위반 (Bucket4j refillGreedy 0~1s 10건 + 1~2s 10건 = 1s window 안에 11건 가능)
  - **(B) "초당 10건" 의 sub-second 분해 강제**: 100ms마다 1건 강제 (Bucket4j refillGreedy도 100ms당 1 token이지만 capacity=10 idle 후 burst 가능)
  - **(C) account-wide tighter limit** (계정·앱키별로 마진 적용 가능)

### 4-6. 결론 (옵션 B 판정)

**옵션 B (burst caller 완화 hotfix) — ACCEPT.**

- LiveDailyCandleScheduler가 6 종목 × 50 페이지 burst의 주범
- 종목 간 100~200ms sleep 마진 추가 → KIS sliding window 마진 확보
- 추가 효과: fail rate 50% → <10% 권장 (사용자 evidence 기준)
- 자본 영향 0 (read-only 캔들 갱신 path)

---

## §5. 진단 결론 매트릭스

### 5-1. 옵션 채택 매트릭스

| 옵션 | 판정 | 사유 |
|------|------|------|
| **A. Bucket4j config drift hotfix** | **REJECTED** | api·batch·consumer 3 Properties 모두 ADR 0037 LOCK 정합 GREEN. application.yml override 매트릭스 정합. drift 0건. |
| **B. burst caller 완화 hotfix** | **★ ACCEPT ★** | LiveDailyCandleScheduler 6 종목 × 50 페이지 burst가 KIS sliding window 마진 0 → 종목 간 100~200ms sleep 마진 추가로 영향 차단. |
| **C. multi-instance race fix** | **REJECTED** | Bucket4j-Redis Lua atomic GREEN. bucketKey="kis-rate-limit:global" 단일 LOCK + 3 모듈 share 정합. race condition 0. |
| **D. Phase 3 backlog 흡수** | **PARTIAL ACCEPT** | Circuit Breaker Phase 3 (5분 윈도 50% 임계 → OPEN + Slack alert) 미구현 잔류. 본 사이클 backlog §0-A Phase 3 잔류 명시. EGW00201 backoff + 1회 재시도(ADR 0037 §2-2 1차/2차)는 Phase 3 backlog 합류. |

### 5-2. 본 PR 변경 매트릭스

| 변경 | 파일 | LOC | 영역 침범? |
|------|------|-----|-----------|
| LiveDailyCandleScheduler 종목 간 100ms sleep 마진 추가 | `api/src/main/.../service/LiveDailyCandleScheduler.kt` | +6 | api/market-data 단독 (정합) |
| 보고서 발행 | `_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` | +본 파일 | docs (정합) |
| CHANGELOG row | `CHANGELOG.md` | +1 | docs (정합) |
| task 파일 | `_workspace/tasks/T-20260522-1520-*.md` | +자동 | task 의무 |

**영역 침범 검증**: backend-core·strategy·frontend·architect·persistence-shared·indicator-impl·strategy-engine·domain·consumer·batch 모듈 touch 0 — ★ market-data + api 단독 LOCK 정합.

### 5-3. Phase 3 backlog 잔류 의제 (architect amend 권장)

본 PR 범위 외 — 다음 사이클 architect spec 합류 권장:

1. **Circuit Breaker** — 5분 윈도 50% EGW00201/429 → OPEN 5분 + Slack alert (`ADR 0037 §2-2` + ADR 0044 reuse)
2. **EGW00201 catch 후 1초 backoff + 1회 재시도** — `KisHashKeyAdapter` 패턴 인용 (ADR 0037 §2-2 1차/2차 매트릭스)
3. **caller priority queue 활성화** — Phase 1 단일 bucket → Phase 2 USER_PATH 우선 (BACKFILL 5분 cron 자제)
4. **KIS sliding window 가설 검증** — KIS 공식 응답 raw fetch로 한도 알고리즘 정확화 (mental model GREEN)

---

## §6. 검증 plan

### 6-1. 컨테이너 재배포 후 evidence 수집 (사용자 측 의무)

```bash
# 1. 본 PR 머지 후
scripts/build-and-deploy.sh api-app   # api 모듈만 재배포 (LiveDailyCandleScheduler 변경)

# 2. 5분 cron 1 cycle 후 (5분 + 30s initialDelay)
docker compose -f docker/docker-compose.yml --env-file ../.env logs magicjar-api --tail 200 | grep -E "LiveDailyCandle|KIS_TPS_EXCEEDED|Rate Limit"

# 3. 권장 검증 매트릭스
# - 5분 cron 1 cycle 동안 "Rate Limit status=500" 로그 0~1건 (이전 base 50% fail rate)
# - LiveDailyCandle ok=6 fail=0 또는 fail≤1 (사용자 evidence ok=3 fail=3 기준)
# - 5분 window aggregator emit 빈도 5분당 0건 (이전 2시간 4건)
```

### 6-2. 빌드 검증 (PR 머지 전)

```bash
./gradlew :api:check         # api 모듈 단위 테스트 GREEN
./gradlew ktlintCheck        # ktlint pass
```

### 6-3. Phase 3 후속 사이클 backlog 흡수 — architect 결재 의무

본 PR 머지 후 backlog §0-A에 Phase 3 4 의제(§5-3 매트릭스) 명시 add-only insert.

---

## §7. 출처 + 권위 cross-link

- **헌법 §3 KIS-first raw fetch** — `_workspace/arch/agent-charter.md`
- **ADR 0037** — `_workspace/arch/adr/0037-kis-rate-limit-enforcement.md` (Rate Limit 강제 + Phase 1·2·3 매트릭스 LOCK)
- **ADR 0094** — 운영 단계 진입 SLO 5건 (KIS 차단 risk = 자본 흐름 catastrophic)
- **spec PR #564** — `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md`
- **이전 PR 매트릭스** — Phase 1: PR #565~#571 / Phase 2: PR #571 (19 어댑터 합류) / 진단 PR #639 (classifyKisError + SystemEvent emit hotfix)
- **KIS 공식 문서** — https://apiportal.koreainvestment.com/intro (REST 한도 "초당 10건" 표기)
- **Bucket4j 8.10.1 refillGreedy 알고리즘** — https://github.com/bucket4j/bucket4j (refillGreedy = "1초당 10 token 균등 회복" — 100ms당 1 token)

---

## §8. LIVE 검증 evidence — 옵션 B (PR #658 종목 간 200ms sleep) REJECT

> **amend 2026-05-22 17:13 KST — 사용자 직접 결재 후 add-only 추가** (이전 §1~§7는 immutable).

### 8-1. PR #658 머지 후 컨테이너 재배포 + 7 cycle 검증 매트릭스

| Cycle | 시각 | ok | fail | 비고 |
|-------|------|----|----- |------|
| 1st (재배포 직후) | 16:34:04 | 0 | 6 | 첫 cycle — 효과 0 |
| 2nd | 16:38:59 | 0 | 6 | 회귀 |
| 3rd | 16:44:08 | 1 | 5 | fluke (다음 cycle 즉시 회귀) |
| 4th | 16:48:58 | 0 | 6 | 회귀 — fluke 확정 |
| 5th | 16:54:05 | 0 | 6 | |
| 6th | 16:59:03 | 0 | 6 | |
| 7th | 17:03:56 | 0 | 6 | |

**누적 매트릭스**:
- TPS exceeded signal count: 17 → 41 (24건 추가 — 약 1.2건/분 + 변화 없음)
- status=500 누적: 24건
- ok=1 한 cycle만 일시 fluke + 6 cycle 100% fail = **§5-1 옵션 B ACCEPT 판정 reject**

### 8-2. 옵션 B REJECT 사유 — H2(cooldown) 가설 reject

§5-1 매트릭스에서 옵션 B의 인과 핵심은 *"종목 간 sleep 마진이 KIS sliding window 마진 확보"* 였으나, LIVE evidence는 정반대:

- 종목 1 → 200ms sleep → 종목 2 진입해도 fail 발생
- 즉 KIS 차단은 **종목 간 cooldown 부족이 아닌**, **종목 내 페이지 burst** 또는 **EGW00201 후 backoff 부재** 가설로 이동

따라서 §5-1 옵션 B 판정 ACCEPT는 LIVE 검증 결과 **부정확** — 본 §8·§9 amend로 정합 회복.

### 8-3. PR #658 코드 유지 결정 (revert 안 함)

- 효과는 미미하나 코드 자체는 무해 (read-only path, 5분 cron 윈도 0.4% 영향)
- revert 시 commit history 복잡화 + 추후 priority queue Phase 2 활성화 시 cooldown 효과 일부 발현 가능성 0이 아님
- 따라서 PR #658 코드 유지 + 본 PR §A~§C 합류로 종합 fix 회복

---

## §9. H1+H4 재진단 + 종합 fix 채택 옵션

### 9-1. H1 (refreshDailyCandles 페이지 burst) — 채택

**증상 인과 재진단**:
- `CandleRefreshService.refreshDailyCandles:289~396` while loop는 페이지 간 sleep 0ms
- Bucket4j refillGreedy(10, 1s) capacity=10 idle 후 burst 즉시 10건 통과 가능
- 첫 10 페이지가 ~1초 이내에 모두 KIS로 발사 → KIS server-side sliding window 가설 위반

**채택 fix** (`CandleRefreshService.kt` 신규 `PAGE_INTERVAL_MS = 150L`):
```kotlin
// 페이지 2+ 부터 150ms sleep — Bucket4j 100ms refill의 50% 마진
if (pageCount > 0) {
    Thread.sleep(PAGE_INTERVAL_MS)
}
kisRateLimiterPort.acquire(priority, timeoutMillis = 3000L)
```

**적용 범위**: `refreshDailyCandles` (1d·1wk·1mo·1y) + `refreshOneMinCandles` (1m). 첫 페이지(index 0)는 sleep 없음 — 사용자 USER_PATH 첫 응답 latency 보존.

**자본 영향**: 0 (read-only). 50 페이지 max × 150ms = 7.5초 추가 elapsed (5분 cron 2.5%).

### 9-2. H4 (EGW00201 catch 후 backoff·재시도 0) — 채택

**증상 인과 재진단**:
- `CandleRefreshService.kt:426~433` `WebClientResponseException` catch 분기에서 `KisErrorClass.RateLimit` 시 즉시 `onTpsExceeded()` + `return IntervalRefreshResult("RATE_LIMITED")` 종결
- 1초 backoff + 1회 재시도 path 0 → 1회 차단 = scheduler ok=0 fail=6 100%
- `KisHashKeyAdapter:126~133` 패턴 (★ 사용자 확인된 reference)은 이미 1초 backoff + 1회 retry 구현되어 있음

**채택 fix** (메서드 단위 `retryAttempt: Int = 0` parameter LOCK + `RATE_LIMIT_BACKOFF_MS = 1000L`):
```kotlin
if (canRetry) {
    Thread.sleep(RATE_LIMIT_BACKOFF_MS)
    return refreshDailyCandles(..., retryAttempt = 1)  // 재귀 1회 LOCK
}
// 두 번째 frame canRetry=false → RATE_LIMITED 종결
```

**적용 범위**: `refreshDailyCandles` (재귀 호출 패턴) + `refreshOneMinCandles` (메서드 단위 flag `oneMinRateLimitRetried`로 동일 효과).

**무한루프 차단**: `retryAttempt` parameter로 frame 단위 LOCK (재귀 호출은 최대 1단계 깊이).

### 9-3. C (priority 재배치) — 채택

**증상 인과 재진단**:
- `CandleRefreshService:294, 509` 모든 acquire가 `KisCallPriority.USER_PATH` hardcode → `LiveDailyCandleScheduler` cron이 사용자 path와 동일 priority로 acquire
- Phase 1은 단일 bucket이므로 효과 0이나 Phase 2 priority queue 활성화 시 사용자 path 막힘 risk

**채택 fix** (`CandleRefreshService.refresh()` 시그니처에 `priority: KisCallPriority = USER_PATH` parameter 추가):
```kotlin
fun refresh(code: String, intervals: List<String>, priority: KisCallPriority = USER_PATH)
```
- `SymbolController` (사용자 path): default USER_PATH 그대로
- `LiveDailyCandleScheduler` (scheduler path): `KisCallPriority.BACKFILL` 명시

**Phase 2 활성화 시 효과**: scheduler가 사용자 path 양보 — fail rate <10% 권장.

### 9-4. 본 PR 변경 매트릭스

| 변경 | 파일 | LOC | 영역 침범? |
|------|------|-----|-----------|
| H1 페이지 sleep + H4 retry + priority parameter — refreshDailyCandles·refreshOneMinCandles | `api/.../service/CandleRefreshService.kt` | +~80 | api/market-data (정합) |
| priority=BACKFILL 전달 (1 line + import) | `api/.../service/LiveDailyCandleScheduler.kt` | +~10 | api/market-data (정합) |
| 보고서 §8·§9 amend (add-only) | `_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` | +본 §8·§9 | docs (정합) |

**영역 침범 검증**: backend-core·strategy·frontend·architect·persistence-shared·indicator-impl·strategy-engine·domain·consumer·batch 모듈 touch 0 — ★ market-data + api 단독 LOCK 정합. SymbolController는 default priority 보존 — 변경 0.

### 9-5. 검증 plan (PR 머지 후)

```bash
# 1. 본 PR 머지 후 재배포
scripts/build-and-deploy.sh api-app

# 2. 5분 cron 3 cycle (15분) evidence 수집
docker compose -f docker/docker-compose.yml --env-file ../.env logs magicjar-api \
  --tail 500 --since 15m | grep -E "LiveDailyCandle|KIS_TPS_EXCEEDED|Rate Limit"

# 3. 권장 검증 매트릭스 (3 cycle 평균)
#   - 6 종목 갱신 ok ≥ 5 / fail ≤ 1 (이전 7 cycle 평균 ok=0 fail=6)
#   - 5분 SystemEvent emit 0건 권장 (이전 cycle당 1건)
#   - TPS signal cycle당 +2 미만 (이전 +6 → +2 권장)
```

### 9-6. Phase 3 backlog 잔류 의제 (재확인)

§5-3 매트릭스 4건 그대로 잔존 + 본 PR에서 §2(EGW00201 backoff+retry)은 **이미 흡수** → 잔여 3건:

1. **Circuit Breaker** — 5분 윈도 50% EGW00201/429 → OPEN 5분 + Slack alert
2. **caller priority queue 활성화** — Phase 2 USER_PATH 우선 (본 PR에서 priority parameter 합류는 Phase 2 사전 준비)
3. **KIS sliding window 가설 검증** — KIS 공식 응답 raw fetch로 한도 알고리즘 정확화

---

## §10. amend changelog

| 시각 | 변경 | PR | 트리거 |
|------|------|----|--------|
| 2026-05-22 15:30 KST | 초안 (§1~§7) | (PR 이전 — 진단 보고서) | 사용자 LIVE evidence 2시간 |
| 2026-05-22 17:13 KST | §8 LIVE 검증 evidence + §9 H1+H4 재진단 (add-only) | 본 PR | PR #658 머지 후 7 cycle 재검증 — 옵션 B reject 확정 |
