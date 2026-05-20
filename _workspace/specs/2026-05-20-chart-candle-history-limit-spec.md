# spec: Chart Candle History Limit + Lazy Backfill 실행 명세

- 작성일: 2026-05-20
- 발행자: chart-curator (보수적 시니어 백엔드 개발자 "안정빌드" — ADR 0085 §2-2-1 페르소나)
- 영역: chart-curator 단독 (ADR 0085 §2-1 매트릭스 정합)
- 워크트리: `agent/chart-curator/chart-candle-history-limit-spec`
- 관련 ADR: ADR 0085(chart-curator single owner) · ADR 0037(KIS Rate Limit) · ADR 0019(캔들 저장 정책) · ADR 0019 §2-2(인터벌 5종) · ADR 0029(당일 진행 캔들 Redis) · ADR 0062(합산 가공 5종) · ADR 0091(NXT/통합거래)
- architect 발행 의제 ADR: (별도 발행 — ADR 번호는 architect dispatch 결과 합류)

> **페르소나 lens 의무 (ADR 0085 §2-2-1)**: 본 spec은 "기존 동작 영향 / 리스크 포인트 / 롤백 가능성" 3 lens + "검증되지 않은 라이브러리·과도한 추상화·한 번에 많은 파일 변경·기존 API 계약 변경·복잡한 제네릭" 5 경계 패턴 lens 의무 적용. Phase 분리 (§9)로 한 번에 많은 파일 변경 risk 차단.

---

## §1. 의제 정의

### 1-1. 사용자 요구 (2026-05-20 KST 명시)

> "차트 봉 갯수가 모자라다. 일봉도 약 600개 정도밖에 안 받아온다. 사용자가 직접 봉 수 선택할 수 있게 + lazy backfill로 좌측 스크롤 끝 도달 시 자동 추가 fetch."

### 1-2. 현재 결함 evidence (코드 trace · 2026-05-20)

| 위치 | 상수 | 값 | 결과 |
|------|------|-----|------|
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/CandleRefreshService.kt:87` | `DAILY_MAX_PAGES` | `6` | 6 × 100 = **600봉**에서 hard cap (사용자 수동 갱신 path) |
| `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisRestCandleAdapter.kt:119` | `MAX_PAGES` | `6` | 6 × 100 = **600봉**에서 hard cap (consumer 정기 ingestion path) |
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/CandleQueryService.kt:37` | `maxLimit` | `500` | API 단일 호출 max 500 (page 단위 — 누적 cap 아님) |
| `frontend/src/api/symbol.ts:204` | `limit` default | `200` | frontend default page size 200 (cap 아님) |

**root cause**: DB 적재 자체가 일봉 600봉 한도 → lazy backfill UI는 정상 작동해도 600봉 이상은 KIS 미호출 → 사용자 좌측 스크롤 시 "더 이상 데이터 없음" 경험.

### 1-3. 본 spec 범위 (Phase 분리 — §9)

- **Phase 1** (단일 PR — safe): `MAX_PAGES` property 추출 + LWC lazy backfill 실작동 검증 + 즉시 운영 evidence 수집
- **Phase 2** (단일 PR): backend API limit·before 파라미터 명확화 + KIS adapter pagination 확장 (timeframe별 분기)
- **Phase 3** (단일 PR — frontend): Symbol 페이지 봉수 선택 dropdown
- **Phase 4** (단일 PR — 정책 확정): timeframe별 max 정책 LOCK + 운영 evidence 합류

본 spec 범위 외:
- 차트 zoom·crosshair·툴팁 변경 (LWC v4 컴포넌트 본체 미수정)
- 도메인 모델·Flyway 변경 (DB 스키마 변경 0 — `candle` 테이블 그대로 사용)
- KIS endpoint·tr_id 변경 (KIS-first raw 그대로 — `FHKST03010100`·`FHKST03010230`)
- backend-core·market-data·strategy-engine·frontend·ai-engineer·architect 영역 (chart-curator 단독)

---

## §2. 현재 작동 매트릭스 — LWC lazy backfill trace 결과

### 2-1. 전체 경로 (code trace evidence)

```
사용자 좌측 스크롤
  ↓
LightweightChart.tsx:424  chart.timeScale().subscribeVisibleLogicalRangeChange(...)
  ↓ (가드 1·2 통과 — backlog §30 fix, ★ 정상 작동)
  ↓ range.from < 1 + 단조감소 검증
LightweightChart.tsx:446  onScrollLeftRef.current()
  ↓
SymbolChart.tsx:175  handleScrollLeft = useCallback(() => { fetchNextPage() })
  ↓
useSymbolCandles.ts:50  getNextPageParam = (lastPage) => lastPage.hasMore ? lastPage.nextBefore : undefined
  ↓
useSymbolCandles.ts:47  symbolApi.getCandles(code, interval, pageParam /* = before */)
  ↓
symbol.ts:199  GET /api/symbols/{code}/candles?interval=...&before=...&limit=200
  ↓
SymbolController.kt:117  candleQueryService.getCandles(code, interval, before, limit)
  ↓
CandleQueryService.kt:78  candleJpaRepository.findByCodeAndIntervalAndTsBefore(code, interval, beforeInstant, PageRequest.of(0, 200))
  ↓
DB candle 테이블 — before ts 이전 200개 row DESC 조회 → ASC 정렬 응답
  ↓
CandleQueryService.kt:148  hasMore = dbRows.size == effectiveLimit (즉 응답 row가 200 = 더 있을 가능성)
  ↓
응답 nextBefore = dbRows.last().ts (가장 오래된 row의 ts)
```

### 2-2. 작동 여부 결론 (chart-curator 페르소나 lens — "기존 동작 영향")

| 레이어 | 상태 | 비고 |
|--------|------|------|
| LWC `subscribeVisibleLogicalRangeChange` 감지 | ✅ **정상** | backlog §30 fix 후 가드 1(1초 cooldown)·가드 2(단조감소) 작동 |
| SymbolChart `handleScrollLeft` 호출 | ✅ **정상** | useInfiniteQuery `fetchNextPage` |
| TanStack `useInfiniteQuery` cursor 진행 | ✅ **정상** | `lastPage.nextBefore` → 다음 page param |
| `GET /api/symbols/{code}/candles?before=...` | ✅ **정상** | 200 row 응답 |
| `CandleQueryService.findByCodeAndIntervalAndTsBefore` | ✅ **정상** | DB row 200개씩 ASC 응답 |
| **DB candle 적재 한도** | ❌ **결함** | 일봉 600봉 hard cap (Phase 1·2 fix 대상) |
| **frontend default page size** | ⚠️ **개선 여지** | `limit=200` 고정 (사용자 선택 0 — Phase 3 fix 대상) |

★ **결론: LWC lazy backfill 자체는 정상**. 사용자 경험 결함의 root cause는 **DB 적재 한도** (600봉) — Phase 1·2 fix 필수.

---

## §3. timeframe별 default·max 정책 매트릭스 (1차 권장)

> **chart-curator 페르소나 lens — "검증된 패턴 우선"**: KIS 공식 한도(초당 10건) + ADR 0037 우선순위 큐(USER_PATH·INGESTION·BACKFILL) 정합. timeframe별 max는 "충분한 사용자 만족 + KIS 호출 분산" 균형으로 설정.

### 3-1. 권장 매트릭스 (Phase 4에서 운영 evidence 합류 후 LOCK)

| interval | default page size | total max (DB 적재 한도) | KIS page 수 (×100) | 사용자 경험 cover | 비고 |
|----------|-------------------|---------------------------|---------------------|-------------------|------|
| 1m | 1000 | 20000 | 200 pages × 120건 = 24000봉 | 약 60 거래일 | KIS 분봉 1 call=120건, 일자당 페이지네이션 (현재 직전 30 거래일 → 60 거래일로 확장) |
| 5m | 1000 | 10000 | 합산 (ADR 0062) — 1m 기반 derive | 약 200 거래일 | 합산 인터벌, AggregateBackfillRunner 처리 |
| 15m | 1000 | 10000 | 합산 | 약 600 거래일 | 동일 |
| 30m | 1000 | 10000 | 합산 | 약 1250 거래일 | 동일 |
| 1h | 1000 | 5000 | 합산 | 약 1250 거래일 | 합산 |
| 1d | 600 | **5000** | 50 pages × 100 = 5000봉 | 약 **20년** | 현재 600봉 → 5000봉 확대 (1차 권장) |
| 1wk | 600 | 3000 | 30 pages × 100 = 3000봉 | 약 **57년** | 사실상 무제한 |
| 1mo | 600 | 1000 | 10 pages × 100 = 1000봉 | 약 **83년** | 사실상 무제한 |
| 1y | 600 | 600 | 6 pages × 100 = 600봉 | 약 **600년** | 현재 유지 (사실상 무제한) |

### 3-2. 매트릭스 선정 근거

1. **사용자 만족**: 일봉 20년 cover는 한국 주요 종목 IPO 후 전체 cover (한국석유 1996 IPO, 삼성전자 1975 IPO 기준 일부 절단).
1mo / 1wk는 사실상 무제한.
2. **KIS 호출 분산**: 일봉 50 pages × 100ms = 5초. ADR 0037 BACKFILL priority + USER_PATH 즉시 진입 정합 (사용자 path 차단 risk 0).
3. **DB 적재 비용**: 일봉 5000봉 × 11 종목 (현재 Watchlist 평균) × 50바이트 ≈ 2.75MB (무시 가능).
4. **점진적 변경 (페르소나 lens)**: 600 → 5000 한 번에 변경하지 않고 Phase 1(600 → 1000) → Phase 4(1000 → 5000 운영 검증 후) 분할. 사이드 이펙트 step-wise 검증.

### 3-3. Phase 1 시작 값 (가장 보수적)

| interval | Phase 1 (즉시 적용) | Phase 4 (검증 후) |
|----------|----------------------|--------------------|
| 1d | `MAX_PAGES=10` (1000봉, 약 4년) | `MAX_PAGES=50` (5000봉, 약 20년) |
| 1wk | `MAX_PAGES=6` 유지 (600봉, 약 11년) | `MAX_PAGES=10` (1000봉 약 19년) |
| 1mo·1y | `MAX_PAGES=6` 유지 | 변경 0 |
| 1m | 직전 30 거래일 유지 | 직전 60 거래일로 확장 |

★ Phase 1 = 일봉 600 → **1000봉** (66% 증가). 4 page 추가로 dogfooding 1 cycle 검증 → Phase 4에서 5000봉으로 격상 결정.

### 3-4. ADR 0037 Rate Limit 정합 검증

- 일봉 backfill 10 pages × ~100ms = 1초. ADR 0037 BACKFILL priority — USER_PATH 우선 흡수.
- 한국석유 PR #587 evidence: 1년 backfill 243봉 = 3 pages × ~100ms = 300ms. 본 spec Phase 1 (1000봉 = 10 pages) 1초 — Rate Limit 한도(초당 10건) 정확히 1 사이클.
- 11 종목 전체 Watchlist 갱신 시 11초 — `kisRateLimiterPort.acquire(BACKFILL)` 우선순위 큐가 사용자 path 보호 (ADR 0037 §2-1).

---

## §4. LWC lazy backfill 실작동 검증 (Read only — 2026-05-20)

### 4-1. 검증 대상 파일

| 경로 | line | 책임 |
|------|------|------|
| `frontend/src/pages/Symbol/LightweightChart.tsx:424-448` | `subscribeVisibleLogicalRangeChange` 콜백 (좌측 스크롤 감지) |
| `frontend/src/pages/Symbol/SymbolChart.tsx:175-179` | `handleScrollLeft` (fetchNextPage dispatch) |
| `frontend/src/api/hooks/useSymbolCandles.ts:44-56` | `useInfiniteQueryForCandles` (cursor 기반 fetch) |
| `frontend/src/api/symbol.ts:199-211` | `symbolApi.getCandles(code, interval, before, limit)` |

### 4-2. 작동 확인 — 가드 1·2 작동 검증 (backlog §30 fix LOCK)

```typescript
// LightweightChart.tsx:424
chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
  if (!range || !onScrollLeftRef.current) return

  // 가드 1: setData 직후 1초간은 자동 콜백(fitContent)이므로 무시 ← ★ 정상 작동
  const elapsedMs = Date.now() - setDataReceivedAtRef.current
  if (setDataReceivedAtRef.current === 0 || elapsedMs < 1000) {
    lastVisibleRangeFromRef.current = range.from
    return
  }

  // 가드 2: 이전 from 대비 단조감소(좌측 pan)일 때만 트리거 ← ★ 정상 작동
  const prevFrom = lastVisibleRangeFromRef.current
  lastVisibleRangeFromRef.current = range.from
  if (prevFrom === null || range.from >= prevFrom) return

  // 좌측 끝 도달 (range.from < 1: 차트 좌측 경계 근접) + 단조감소
  if (range.from < 1) {
    onScrollLeftRef.current()  // ← ★ fetchNextPage 호출
  }
})
```

### 4-3. 검증 결론 (chart-curator 페르소나 출력 형식)

#### 안정성 관점
- **기존 동작 영향**: 영향 없음 (lazy backfill UI는 이미 정상 작동 — 변경 0)
- **리스크 포인트**: 600봉 → 1000봉 확장 시 DB row 증가 (~67% 증가, 단일 종목당 +400 row × 50바이트 = +20KB)
- **롤백 가능성**: `MAX_PAGES` 상수만 변경 — git revert 1줄로 즉시 롤백 가능

#### 코드 리뷰 lens
1. 🔴 **[필수 확인]** Phase 1·2 시 `DAILY_MAX_PAGES` (api) + `MAX_PAGES` (consumer) **두 곳 모두 동시 수정 의무** — drift risk 차단
2. 🟡 **[권장]** `MAX_PAGES`를 hardcoded 상수에서 `@Value("\${magicjar.candle.daily-max-pages:10}")` property로 추출 — 운영 중 application.yml로 조정 가능
3. 🔵 **[참고]** `KisRestCandleAdapter.MAX_PAGES`는 interval 분기 없음 (D/W/M/Y 공통 6). timeframe별 분기는 Phase 2에서 신설

#### 우려 사항
- KIS 응답 page < `KIS_PAGE_LIMIT(100)` 시 break 가드 작동 (`KisRestCandleAdapter:771`) — 종목 IPO 이전 cursor 도달 시 안전 종료. 변경 0.
- `nextCursor = oldestDate.minusDays(1)` (`KisRestCandleAdapter:145`) — 4 timeframe 공통. Phase 2 timeframe 분기 시 cursor advance algorithm 재검증 의무.

---

## §5. backend API 변경 — `SymbolController.getCandles` 파라미터 명확화

### 5-1. 현재 시그니처 (변경 0 — 이미 정합)

```kotlin
// api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SymbolController.kt:116
@GetMapping("/{code}/candles")
fun getCandles(
    @PathVariable code: String,
    @RequestParam(defaultValue = "1d") interval: String,
    @RequestParam(required = false) before: String?,  // ← 이미 존재
    @RequestParam(defaultValue = "200") limit: Int,    // ← 이미 존재 (max 500)
): CandlesResponseDto
```

★ **API 계약 변경 0** (페르소나 lens — 5 경계 패턴 "기존 API 계약 변경" 위반 0). 본 endpoint는 이미 lazy backfill cursor를 지원함.

### 5-2. Phase 2 보강 항목 (단일 PR)

| 항목 | 변경 | 사유 |
|------|------|------|
| Swagger description | "최대 500봉" → "page 단위 최대 500봉, 누적은 frontend lazy backfill 로 무제한" | 사용자 혼동 차단 |
| `CandleQueryService.maxLimit` | `500` 유지 | page 단위 cap (단일 호출 KIS 응답과 동일) |
| `defaultLimit` | `200` 유지 | LWC v4 setData 권장 — 1000+ 단일 setData는 렌더링 부담 |
| `intervalCodeMap` | 11종 → 11종 유지 | ADR 0062 5종 합산 + KIS 5종 정합 |

### 5-3. Phase 2 추가 endpoint 검토 (chart-curator 페르소나 — "과도한 추상화 경계")

| 옵션 | 채택 여부 | 사유 |
|------|-----------|------|
| 신규 `/api/symbols/{code}/candles/range?from=...&to=...` | ❌ **거부** | YAGNI — lazy backfill `before` cursor로 동일 기능 cover |
| 신규 `/api/symbols/{code}/candles/page-info` | ❌ **거부** | `hasMore` boolean 이미 응답 포함 — 추가 endpoint 불요 |
| 신규 `/api/symbols/{code}/candles/total-count` | ❌ **거부** | DB COUNT 비용 + 사용자 가치 0 (lazy backfill UX와 무관) |

### 5-4. Phase 2 trigger 매트릭스 ★ Phase 1 머지 + 운영 evidence 1 사이클 후 진입

- 일봉 600 → 1000 적재 evidence 확보 후 Phase 2 진입
- Phase 2 PR 본문에 dogfooding 종목 (한국석유 004090) 1000봉 적재 evidence (DB row count + 가장 오래된 ts) 인용 의무

---

## §6. KIS adapter pagination 확장 — `MAX_PAGES` property화 + timeframe별 분기

### 6-1. Phase 1 변경 (즉시 — 두 파일 동시)

**파일 1**: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisRestCandleAdapter.kt`

```kotlin
companion object {
    // 기존: const val MAX_PAGES: Int = 6
    // Phase 1: 600봉 → 1000봉 (4 page 추가)
    // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
    // ADR 0037 Rate Limit 정합: 10 pages × ~100ms = 1초 — BACKFILL priority 우선
    const val MAX_PAGES: Int = 10  // ★ Phase 1 변경
}
```

**파일 2**: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/CandleRefreshService.kt`

```kotlin
companion object {
    // 기존: private const val DAILY_MAX_PAGES = 6
    // Phase 1: 600봉 → 1000봉 (consumer KisRestCandleAdapter.MAX_PAGES와 동일 LOCK — drift 차단)
    private const val DAILY_MAX_PAGES = 10  // ★ Phase 1 변경
}
```

★ **두 파일 동일 PR 의무** — drift risk 차단 (chart-curator 페르소나 — "기존 동작 영향" lens). 

### 6-2. Phase 2 변경 (timeframe별 분기 + property 추출 — 단일 PR)

```kotlin
// KisRestCandleAdapter.kt
@Component
@Primary
class KisRestCandleAdapter(
    // ... 기존 의존성 ...
    @Value("\${magicjar.candle.max-pages.daily:50}")
    private val maxPagesDaily: Int,       // 일봉 5000봉 (Phase 4)
    @Value("\${magicjar.candle.max-pages.weekly:30}")
    private val maxPagesWeekly: Int,      // 주봉 3000봉
    @Value("\${magicjar.candle.max-pages.monthly:10}")
    private val maxPagesMonthly: Int,     // 월봉 1000봉
    @Value("\${magicjar.candle.max-pages.yearly:6}")
    private val maxPagesYearly: Int,      // 연봉 600봉
    @Value("\${magicjar.candle.max-pages.one-min-days:60}")
    private val oneMinDays: Int,          // 1분봉 직전 60 거래일
) : KisRestPort {
    
    private fun maxPagesFor(interval: CandleInterval): Int =
        when (interval) {
            CandleInterval.ONE_DAY -> maxPagesDaily
            CandleInterval.ONE_WEEK -> maxPagesWeekly
            CandleInterval.ONE_MONTH -> maxPagesMonthly
            CandleInterval.ONE_YEAR -> maxPagesYearly
            else -> throw IllegalArgumentException("KIS daily endpoint 미지원: ${interval.code}")
        }
    
    // fetchDailyCandles 내부 — for (page in 0 until MAX_PAGES) → for (page in 0 until maxPagesFor(interval))
}
```

### 6-3. application.yml 추가 (Phase 2)

```yaml
# api/src/main/resources/application.yml + consumer/src/main/resources/application.yml
magicjar:
  candle:
    max-pages:
      daily: 50       # 5000봉, 약 20년 cover (Phase 4)
      weekly: 30      # 3000봉, 약 57년 cover
      monthly: 10     # 1000봉, 약 83년 cover
      yearly: 6       # 600봉, 약 600년 cover (사실상 무제한)
      one-min-days: 60  # 1분봉 직전 60 거래일
```

### 6-4. 보수적 페르소나 lens — Phase 2 진입 전 의무 검증

1. 🔴 **[필수]** Phase 1 머지 + 운영 evidence 1 사이클(48시간) 후 진입 — drift risk 차단
2. 🔴 **[필수]** application.yml property 추가 시 default 값 = Phase 1 값(=10) — 운영 중 application.yml 미배포 시 regression risk 0
3. 🟡 **[권장]** ArchUnit test 추가 — `KisRestCandleAdapter`·`CandleRefreshService` `MAX_PAGES` hardcoded 직접 사용 금지 (property 통과 의무)

---

## §7. frontend UI — Symbol 페이지 봉수 선택 dropdown (Phase 3)

### 7-1. 옵션 매트릭스

| 옵션 | 값 | UX |
|------|-----|-----|
| 1 | 300봉 | 빠른 로딩 (단기 트레이딩) |
| 2 | 600봉 (default) | 현재 한도 |
| 3 | 1200봉 | 중기 검토 |
| 4 | 3000봉 | 장기 분석 |
| 5 | 사용자 직접 입력 | 1~5000 범위 |

### 7-2. 위치 — `IntervalToggle` 옆 (Phase 3)

`frontend/src/pages/Symbol/IntervalToggle.tsx`와 같은 컨테이너 내부에 `CandleCountSelector` 컴포넌트 신설 (chart-curator 영역).

```tsx
// frontend/src/components/chart/CandleCountSelector.tsx (★ chart-curator 영역 — ADR 0085 §2-1-extended)
export interface CandleCountSelectorProps {
  value: number
  onChange: (count: number) => void
}

export function CandleCountSelector({ value, onChange }: CandleCountSelectorProps) {
  // dropdown — 300/600/1200/3000/직접 입력
  // 선택 시 useSymbolCandles의 useInfiniteQuery initialPageParam에 영향
  // (Phase 3 본 구현 시 SymbolChart 통합)
}
```

### 7-3. useSymbolCandles 변경 — 봉수 prop 합류 (Phase 3)

```typescript
// frontend/src/api/hooks/useSymbolCandles.ts
export function useSymbolCandles(
  code: string,
  interval: CandleInterval,
  pageSize: number = 200,  // ★ Phase 3 추가
): UseSymbolCandlesResult {
  return useInfiniteQuery({
    queryKey: ['symbolCandles', code, interval, pageSize] as const,  // ★ pageSize 포함
    queryFn: ({ pageParam }) =>
      symbolApi.getCandles(code, interval, pageParam, pageSize),
    // ...
  })
}
```

### 7-4. 페르소나 lens — Phase 3 진입 조건

1. Phase 2 완료 + 운영 evidence 합류 (DB 적재 1000+ 봉 검증)
2. LWC v4 `setData` 1200+ 봉 렌더링 성능 검증 (FPS·메모리)
3. `useInfiniteQuery` pageSize 변경 시 cache invalidation 정합 검증

---

## §8. 실시간 당일 캔들 merge 정합 (변경 0 — 현재 LOCK 보존)

### 8-1. 현재 작동 (변경 대상 0)

```typescript
// LightweightChart.tsx:607
useEffect(() => {
  if (!realtimeCandle) return
  // STOMP /topic/market.candle.{interval}.{code} 수신 → series.update()
  candleSeries.update(cd)  // ← 마지막 bar 덮어쓰기
})

// LightweightChart.tsx:640
useEffect(() => {
  if (!liveTick) return
  // STOMP /topic/market.tick.{code} → 마지막 bar close·high·low 실시간 update
  const barTime = barBoundaryTimestamp(liveTick.ts, intervalRef.current)
  candleSeries.update({ time: barTime, open, high, low, close: price })
})
```

### 8-2. Phase 1·2·3·4 변경 0 — 자본 보호 critical sync 보존

★ **chart-curator 페르소나 lens — "동작하는 코드를 함부로 건드리지 마라"**: STOMP 실시간 merge는 자본 보호 critical sync (사용자 매매 결정 직결). 본 spec Phase 1~4 모두 변경 0. 봉수 확장과 무관.

### 8-3. Phase 2 옵션 검토 (chart-curator 페르소나 — "과도한 추상화 경계")

| 옵션 | 채택 여부 | 사유 |
|------|-----------|------|
| `latestBoundaryCandle` state를 array로 확장 (다중 boundary 누적) | ❌ **거부** | 단일 latest만으로 충분 — LWC series.update가 idempotent |
| STOMP topic 변경 (예: `/topic/market.candle.batch.{code}`) | ❌ **거부** | 기존 topic 변경은 consumer + STOMP forward + frontend 동시 수정 → 한 PR에 너무 많은 변경 |

---

## §9. Phase 분리 (chart-curator 페르소나 lens — "한 번에 너무 많은 파일 변경" 경계)

### 9-1. Phase 1 — 즉시 적용 (단일 PR · 2 파일 동시 수정)

**범위**: `DAILY_MAX_PAGES`·`MAX_PAGES` 상수 변경 (6 → 10) + 본 spec 발행

**파일 변경 매트릭스**:
| 파일 | 변경 |
|------|------|
| `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/CandleRefreshService.kt:87` | `DAILY_MAX_PAGES = 10` |
| `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisRestCandleAdapter.kt:119` | `MAX_PAGES = 10` |
| KDoc 갱신 (두 파일) | "600봉 ≈ 2.5년" → "1000봉 ≈ 약 4년" |

**검증**: 
- `./gradlew :api:check :consumer:check` GREEN
- dogfooding: 한국석유(004090) `POST /api/symbols/004090/candles/refresh?intervals=1d` 호출 → DB candle row count > 600 검증

**롤백**: `git revert <commit>` 1회로 즉시 600봉 복원

### 9-2. Phase 2 — backend API 명확화 + KIS adapter timeframe 분기 (단일 PR)

**전제**: Phase 1 머지 + 운영 evidence 1 사이클 (48시간)

**범위**:
- `MAX_PAGES` property 추출 (application.yml `magicjar.candle.max-pages.*`)
- `KisRestCandleAdapter.fetchDailyCandles` timeframe별 분기 (`maxPagesFor(interval)`)
- `CandleRefreshService.refreshDailyCandles` 동일 분기
- Swagger description 명확화 (page vs 누적 cap 구분)
- ArchUnit test 추가 (hardcoded MAX_PAGES 사용 금지)

**파일 변경 매트릭스** (예상 7~9 파일):
- api/src/.../CandleRefreshService.kt
- consumer/src/.../KisRestCandleAdapter.kt
- api/src/main/resources/application.yml
- consumer/src/main/resources/application.yml
- api/src/.../SymbolController.kt (Swagger description)
- ArchUnit test 1 파일
- 단위 테스트 갱신 (1~2 파일)

### 9-3. Phase 3 — frontend UI 봉수 선택 (단일 PR · chart-curator 영역)

**전제**: Phase 2 완료 + 운영 evidence

**범위**:
- `frontend/src/components/chart/CandleCountSelector.tsx` 신설 (chart-curator 영역 — ADR 0085 §2-1-extended)
- `frontend/src/api/hooks/useSymbolCandles.ts` `pageSize` prop 추가
- `frontend/src/pages/Symbol/SymbolChart.tsx` `CandleCountSelector` 통합
- `frontend/src/pages/Symbol/__tests__/SymbolDetailPage.test.tsx` 갱신

**파일 변경 매트릭스** (예상 4 파일)

### 9-4. Phase 4 — timeframe별 max 정책 LOCK + ADR amend (단일 PR)

**전제**: Phase 1·2·3 완료 + 운영 evidence 7일

**범위**:
- application.yml `max-pages.daily: 10 → 50` (5000봉)
- ADR 0085 §추가 row — Phase 4 정책 LOCK
- 본 spec §3 "1차 권장" → "LOCK"으로 격상
- dogfooding evidence 인용 (한국석유 일봉 5000봉 적재 verify)

### 9-5. Phase 진입 trigger 매트릭스 ★

| Phase | trigger | 검증 evidence |
|-------|---------|--------------|
| Phase 1 → 2 | 48시간 운영 후 | 11 종목 일봉 1000봉 적재 + 0 결함 |
| Phase 2 → 3 | 48시간 운영 후 | timeframe별 분기 LOCK + ArchUnit GREEN |
| Phase 3 → 4 | 7일 운영 후 | 사용자 선택 UI dogfooding + LWC v4 1200봉 렌더링 검증 |

---

## §10. ADR 정합 cross-ref

### 10-1. ADR 0085 chart-curator single owner 정합

본 spec은 **chart-curator 단독 dispatch** (ADR 0085 §2-1 LOCK):
- 캔들 적재 워커 (`KisRestCandleAdapter`·`CandleRefreshService`) → chart-curator 책임 (market-data 이관)
- TradingView LWC v4 (`LightweightChart`·`SymbolChart`) → chart-curator 책임 (frontend 이관 — ADR 0085 §2-1-extended)
- frontend UI dropdown (`CandleCountSelector.tsx`) → chart-curator 영역 (LWC v4 instance 영향)

### 10-2. ADR 0037 Rate Limit 정합

본 spec Phase 1~4 모두 ADR 0037 §2-1 정합:
- `kisRateLimiterPort.acquire(KisCallPriority.BACKFILL)` 통과 의무
- 일봉 50 pages × ~100ms = 5초 < 60초 — Rate Limit 한도 영향 미미
- USER_PATH 우선 — backfill 폭주 시도 매매 흐름 차단 risk 0

### 10-3. ADR 0019 §2-2 캔들 저장 정책 정합

본 spec은 ADR 0019 §2-2 인터벌 5종 (1m·5m·30m·1h·1d) + ADR 0062 합산 5종 (5m·15m·30m·90m·1h) + ADR 0091 NXT/통합거래 종목 단위 분기 정합. DB 스키마·CHECK constraint 변경 0.

### 10-4. ADR 0091 NXT/통합거래 정합

본 spec은 `resolveMrktDivCodeCandidates` (KOSPI200/KOSDAQ150 → UN allow-list + J fallback) 변경 0. Phase 1~4 모두 기존 시장구분 코드 분기 그대로 사용.

---

## §11. 사용자 명시 인용

> "차트 봉 갯수가 모자라다. 일봉도 약 600개 정도밖에 안 받아온다. 사용자가 직접 봉 수 선택할 수 있게 + lazy backfill로 좌측 스크롤 끝 도달 시 자동 추가 fetch." (2026-05-20 KST orchestrator dispatch 의제 — 사용자 옵션 2 결재: architect ADR + chart-curator spec 협업)

---

## §12. 변경 이력

| 일자 | 변경 | 사유 |
|------|------|------|
| 2026-05-20 | spec 신설 (chart-curator) | 사용자 옵션 2 결재 — 일봉 600봉 cap 결함 + lazy backfill UI 미작동 의제 + Phase 1~4 매트릭스 발행 + LWC lazy backfill 실작동 검증 (★ root cause = DB 적재 한도, frontend UI는 정상) |
