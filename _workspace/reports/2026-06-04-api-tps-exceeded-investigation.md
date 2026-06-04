# api 모듈 KIS_TPS_EXCEEDED 증가 추세 원인 규명 + fix 보고서

- **작성일**: 2026-06-04 KST
- **작성**: backend-core (리팩토링맨 페르소나 — ADR 0087)
- **task**: T-20260604-1706-api-tps-exceeded-rootfix
- **브랜치**: agent/backend-core/api-tps-exceeded-rootfix
- **점검 사이클**: #2 (P1) — api 모듈 단독 (consumer 폭주는 병렬 에이전트 처리 중)
- **연관 ADR**: 0037 (Rate Limit 강제) · 0087 (진보 페르소나) · 0094 (운영 단계 SLO S-4)
- **선행 보고서**: `_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` (H1·H4·priority fix — PR #6b9353e5)

---

## §1. 운영 evidence (사용자 신고 — Slack 알림)

api 모듈 `RedisKisRateLimiterAdapter` 발신 `KIS_RATELIMIT/KIS_TPS_EXCEEDED` (bucketKey=kis-rate-limit:global):

| 시각 (KST) | tpsExceededCount |
|-----------|------------------|
| 11:41 | 57 |
| 12:11 | 67 |
| 12:51 | 76 |
| 15:41 | 113 |
| 16:46 | 126 |

**관찰**: 장중 내내 발생 + "증가 추세".

---

## §2. "증가 추세" 의 정확한 해석 (할루시네이션 차단)

`RedisKisRateLimiterAdapter.onTpsExceeded()` 의 `tpsExceededCount` 는 `AtomicLong.incrementAndGet()` — **부팅 후 단조 누적 카운터, 절대 reset 안 됨** (`RedisKisRateLimiterAdapter.kt:58, 174`).

따라서 57→67→76→113→126 은 **가속 leak 이 아니라 단조 누적값**. 분당 발생률을 역산하면:

| 구간 | 경과 | Δcount | 분당 |
|------|------|--------|------|
| 11:41→12:11 | 30분 | +10 | 0.33 |
| 12:11→12:51 | 40분 | +9 | 0.23 |
| 12:51→15:41 | 170분 | +37 | 0.22 |
| 15:41→16:46 | 65분 | +13 | 0.20 |

→ 발생률은 **대략 일정 (~0.2~0.3건/분)**. 즉 **가속하는 leak 이 아니라, 장중 내내 일정 비율로 EGW00201 차단이 지속**되는 패턴. 선행 보고서(2026-05-22) evidence(~1.3건/분)보다는 개선됐으나 0 이 아님.

**결론**: 구독 leak·TTL 만료 실패 같은 "누적형 leak" 은 아니다. **주기적 burst caller 가 매 cycle 일정량의 KIS 차단을 유발**하는 패턴.

---

## §3. api 모듈 KIS REST 주기 caller 전수 audit

### 3-1. @Scheduled (api 모듈)

| Scheduler | 주기 | KIS REST? | burst risk |
|-----------|------|-----------|-----------|
| **`LiveDailyCandleScheduler`** | **5분 fixedRate** | **★ YES (watchlist 종목 × 1d 갱신)** | **★ HIGH (본 root cause)** |
| `WatchlistNewsScheduler` | 1분 fixedRate | 네이버 (KIS 별개) | 0 |
| `DartDisclosurePollingScheduler` | 1분 cron | DART (KIS 별개) | 0 |
| `MarketTickStompForwarder` | 5분 fixedDelay | 0 (Redis cleanup only) | 0 |
| `MarketOrderbookStompForwarder` | 5분 fixedDelay | 0 (Redis cleanup only) | 0 |

### 3-2. demand-driven KIS REST 어댑터 (사용자 path)

| 컴포넌트 | trigger | 빈도 상한 | leak risk |
|---------|---------|-----------|-----------|
| `KisRestPriceMetricsAdapter` (SymbolMetaService) | 종목 상세 진입 | **meta:{code} 5분 TTL cache** → 종목당 최대 1/5분 | 0 (cache 상한) |
| `ApiKisBalanceQueryAdapter` | 사용자 잔고 조회 | 사용자 클릭 | 0 |
| `ApiKisOrderCancelAdapter` | 주문 취소 | 사용자 액션 | 0 |
| `ApiKisHashKeyAdapter` | 주문 해시키 | 주문 시 | 0 |
| `CandleRefreshService` (SymbolController) | 사용자 캔들 갱신 버튼 | 사용자 액션 | 0 |

→ demand-driven path 는 모두 사용자 액션·5분 TTL cache 로 상한이 잡혀 있어 "일정 비율 지속 차단" 의 원천이 될 수 없음 (사용자가 종일 일정 빈도로 클릭하지 않는 한). **유일한 주기적·자동 KIS burst 원천 = `LiveDailyCandleScheduler`**.

---

## §4. root cause — LiveDailyCandleScheduler 의 30년 전수 재fetch burst

### 4-1. 호출 사슬

```
LiveDailyCandleScheduler.tick()  (@Scheduled 5분, 08:00~20:00 KST)
  → for (code in watchlist) candleRefreshService.refresh(code, ["1d"], BACKFILL)
      → refreshDailyCandlesWithFallback → refreshDailyCandles(code, "1d", ...)
```

### 4-2. `refreshDailyCandles` 의 페이지네이션 (변경 전)

`CandleRefreshService.kt:486~` — cursor 를 **today 부터 30년 전(`LocalDate.now().minusYears(30)`)까지** 역방향 페이지네이션, `maxPages = getMaxPagesFor("1d") = 50`:

```kotlin
var cursor = LocalDate.now(KST_ZONE)        // today
while (pageCount < maxPages) {              // 1d = 50
    if (cursor < fromDate) break            // fromDate = today - 30년
    ... acquire + KIS 호출 (100봉/페이지) ...
    cursor = oldestDate.minusDays(1)        // 과거로 cursor advance
}
```

**즉 매 5분 cycle 마다 종목당 최대 50 페이지(30년 전수) 를 KIS 에 재요청.** 그러나:

- 과거 일봉은 **immutable** (확정 종가) — 한 번 DB 에 들어가면 변하지 않음
- upsert 는 `ON CONFLICT DO NOTHING` — 재fetch 한 과거 봉은 INSERT 0 (순수 낭비)
- 5분 cron 의 **실제 목적은 "오늘 진행 중인 일봉 close 갱신"** — 첫 페이지(최근 100 일봉) 면 충분

### 4-3. burst 수치

| 항목 | 값 |
|------|-----|
| watchlist 종목 (evidence 기준) | ~6 |
| 종목당 maxPages (1d) | 50 |
| cycle 당 KIS 호출 상한 | **6 × 50 = 300** |
| 종목 간 sleep (선행 fix #658) | 200ms |
| 페이지 간 sleep (선행 fix H1) | 150ms |

선행 보고서(2026-05-22) 의 H1(페이지 150ms sleep) + H4(1초 backoff + 1회 재시도) fix 는 **burst 의 timing 을 완화**했을 뿐, **호출 총량(300/cycle) 자체는 그대로** 였음. KIS server-side sliding window 가설 하에서 300 호출/cycle 은 여전히 일정 비율의 EGW00201 을 유발 → 단조 카운터가 종일 천천히 증가.

추가로 H4 재시도는 페이지 N 에서 차단 시 **interval 전체를 page 0 부터 재시작**(`refreshDailyCandles(..., retryAttempt=1)`) — 이미 성공한 페이지까지 재호출하므로 차단 발생 시 호출량이 오히려 증폭됨.

---

## §5. 채택 fix — incremental(delta-only) 페이지네이션 경계

### 5-1. 설계 (보수적 최소 변경)

`refresh()` · `refreshDailyCandles()` 에 `incremental: Boolean` 파라미터 추가:

- `incremental=true` 시 loop 진입 전 **DB 최신 봉 epochSec** 를 경계로 lookup (`latestEpochSec(code, intervalCode)`)
- 한 페이지의 oldest 봉 epochSec 가 경계 **이하**(이미 DB 에 존재)면 페이지네이션 **즉시 중단**
- → 종목당 **~1 페이지**(최근 100봉) 만 fetch → cron burst 제거

### 5-2. 회귀 안전 (방향성 보존)

| caller | incremental | 효과 |
|--------|-------------|------|
| `LiveDailyCandleScheduler` (5분 cron) | **true** | 종목당 ~1 페이지 — burst 제거 |
| `SymbolController.refresh` (사용자 버튼) | false (default) | 전수 backfill 보존 — gap 복구 회귀 0 |
| `CandleRefreshService.recollect` (재수집) | false (명시) | 삭제 후 전수 재fetch 본질 보존 |
| cold-start 종목 (DB 봉 0건) | `latestEpochSec`=null | incremental 이라도 전수 backfill 진행 (경계 미적용) |

- **과거 봉 immutable + ON CONFLICT DO NOTHING** 전제이므로 delta-only 가 데이터 정합성 훼손 0
- 오늘 진행 중 일봉(미확정 close)은 첫 페이지에 항상 포함 → 5분 cron 의 본래 목적(오늘 close 갱신) 정확히 달성
- DB 조회 예외·0건은 null graceful → 전수 fetch fallback (안전)

### 5-3. burst 감소 추정

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| cycle 당 KIS 호출 (6 종목, 정상 갱신 시) | 최대 300 | **~6 (종목당 1 페이지)** |
| EGW00201 발생률 추정 | ~0.2~0.3/분 | **0 에 수렴 (단일 페이지는 acquire 통과 + KIS window 여유)** |

---

## §6. 변경 매트릭스

| 변경 | 파일 | 성격 |
|------|------|------|
| `refresh()`·`refreshDailyCandlesWithFallback()`·`refreshDailyCandles()` 에 `incremental` 파라미터 + 경계 중단 로직 + `latestEpochSec()` helper | `api/.../service/CandleRefreshService.kt` | api 단독 |
| scheduler 가 `incremental=true` 전달 | `api/.../service/LiveDailyCandleScheduler.kt` | api 단독 |
| `recollect` 가 `incremental=false` 명시 (named arg) | `api/.../service/CandleRefreshService.kt` | api 단독 |
| 회귀 테스트 3건 (latestEpochSec 경계·cold-start null·예외 graceful) | `api/.../CandleRefreshServiceAscNormalizeTest.kt` | test |
| 컨트롤러 mock 5-matcher 정합 (시그니처 변경 흡수) | `api/.../CandleRefreshControllerTest.kt` | test |
| CHANGELOG row | `CHANGELOG.md` | docs |

**영역 침범 검증**: consumer·batch·domain·persistence-shared·indicator-impl·strategy-engine·frontend touch 0 — ★ api 단독 LOCK. consumer 폭주(16:31·16:50)는 본 task 무관(병렬 에이전트 처리).

---

## §7. 검증

- `./gradlew :api:test` — **590 tests GREEN** (회귀 테스트 3건 신규 + 컨트롤러 테스트 5건 정합)
- `./gradlew :api:ktlintCheck` — GREEN
- `./gradlew :api:compileKotlin` — GREEN

### 7-1. 라이브 검증 plan (사용자 측 — 배포 후)

```bash
scripts/build-and-deploy.sh api-app   # api 재배포 (LiveDailyCandleScheduler + CandleRefreshService 변경)

# 5분 cron 3 cycle (15분) 후
docker logs magicjar-api --since 15m | grep -E "LiveDailyCandle|KIS_TPS_EXCEEDED|incremental 경계"

# 권장 검증 매트릭스
# - "incremental 경계 도달 — 페이지네이션 중단" debug 로그 종목당 1회 (정상 갱신 종목)
# - LiveDailyCandle ok=6 fail=0
# - tpsExceededCount 증가 멈춤 (cycle 간 Δ=0)
```

---

## §8. 운영 영향 매트릭스 (PR 본문 정합)

- **SLO**: S-4 (KIS rate limit) — EGW00201 발생률 ~0.2/분 → 0 수렴
- **rollback**: `incremental` default=false 이므로 scheduler 의 `incremental=true` 1줄 revert 시 즉시 종전 동작 복귀. DB 스키마·Flyway·Redis·Kafka·STOMP·ENV 변경 0.
- **관측**: `KIS_TPS_EXCEEDED` 5분 윈도 SystemEvent emit 추이 + `incremental 경계 도달` debug 로그
- **자본 영향**: 0 (read-only 캔들 갱신 path)

---

## §9. backlog 권고 (architect — 본 PR 범위 외)

선행 보고서 §5-3 Phase 3 잔여 의제 그대로 유효:

1. **Circuit Breaker** — 5분 윈도 50% EGW00201/429 → OPEN 5분 + Slack alert (ADR 0037 §2-2)
2. **tpsExceededCount 누적 → rolling window 전환** — Slack 알림이 "증가 추세" 로 오인되지 않도록 5분 윈도 rate 기반 metric 노출 권고 (관측 정확성)
3. **caller priority queue 활성화** — Phase 1 단일 bucket → Phase 2 USER_PATH 우선
