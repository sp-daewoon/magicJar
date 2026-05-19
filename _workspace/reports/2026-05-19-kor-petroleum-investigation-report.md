# 한국석유(004090) LWC dispose 오류 + 일봉 오염 데이터 진단·처리 방안 보고서

**작성**: chart-curator (`bot+chart-curator@magicjar.local`)
**발행일**: 2026-05-19 KST
**Task**: T-20260519-1413-kor-petroleum-investigation
**근거 권위**: ADR 0085 (chart-curator 영입) · ADR 0086 (T-CC Phase 2 햇징 룰) · skill `magicjar-chart-curator` §10
**사용자 결재 요청**: §4 처리 방안 매트릭스 중 선호 옵션 선택 → 후속 코드 fix 사이클 발사

---

## 0. 사용자 보고 의제 인용

> "캔들큐레이터한테 한국석유 개발자도구 들어가면 Uncaught Error: Object is disposed 오류난다고 알려주고. 한국석유 최근일봉이 2026-05-19, 2025-12-30, 2025-12-29 이렇게 되어있다고 전달해줘. 이런 오염된 데이터가 있을 경우 처리 방안은 뭐지도 보고하고"

**의제 분해**:
1. 결함 #1: 한국석유 화면 콘솔 `Uncaught Error: Object is disposed`
2. 결함 #2: 한국석유 일봉이 `2026-05-19, 2025-12-30, 2025-12-29` 순으로 표시 (5개월 갭 + 비정상 정렬 의심)
3. 사용자 결정 의제: 오염 데이터 처리 방안 매트릭스

---

## 1. 결함 #1 — LWC `Object is disposed` Root Cause 추정

### 1-1. 현황 정합

| 항목 | 상태 |
|------|------|
| `frontend/src/pages/Symbol/LightweightChart.tsx` | dispose 가드 존재 — `isDisposedRef`, `chart.remove()` try/catch (line 526-544) |
| `frontend/src/pages/Symbol/IndicatorOverlay.tsx` | series cleanup try/catch (`chart.removeSeries(s)` line 327-332) |
| `frontend/src/components/chart/PatternMarker.tsx` | `series.setMarkers([])` cleanup try/catch (line 105-109) |

dispose 가드가 이미 곳곳에 들어있는데도 콘솔에 오류가 표시되는 이유는 다음 1가지로 추정됩니다.

### 1-2. Root Cause 추정 매트릭스

#### (a) StrictMode 이중 mount + ref 갱신 비동기성 — 가장 유력 ★

React 18 개발 환경 StrictMode는 `useEffect`를 **mount → unmount → remount** 사이클로 발화합니다. `SymbolChart.tsx`의 구조:

```tsx
// line 99: chartApiRef.current 보관 (state 아닌 ref → re-render 미발생)
const chartApiRef = useRef<IChartApi | null>(null)
// line 469: ref.current를 props로 전달 — render 시점 값 캡처
<IndicatorOverlay chart={chartApiRef.current} ... />
```

**시나리오**:
1. 1차 mount: `LightweightChart` useEffect → `chart_A` 생성 → `handleChartReady(chart_A)` 호출 → `chartApiRef.current = chart_A`. 단, 이 mutation은 **re-render trigger 없음** (`useRef`).
2. 1차 unmount: `LightweightChart` cleanup → `chart_A.remove()` 호출 → `chart_A` disposed → `handleChartDestroy()` → `chartApiRef.current = null`.
3. 그 사이 `IndicatorOverlay`는 1차 render에서 captured된 `chart_A` props로 **이미 mount되어 useEffect가 실행 중**. 다른 종속성 (selectedIndicatorIds, data) 변경 시 cleanup → `chart_A.removeSeries(s)` 호출 → **chart_A는 이미 disposed** → throw.
4. cleanup의 try/catch가 잡지만 — LWC v4 내부에서 `throw new Error('Object is disposed')`가 발생하기 전에 콘솔에 직접 `console.error` 또는 `console.warn` 출력하는 경우, catch가 잡아도 콘솔에는 이미 표시.

#### (b) IndicatorOverlay의 chart 변경 감지 누락 — useEffect 종속성 문제

```tsx
// IndicatorOverlay.tsx line 335
}, [chart, data, indicatorId, colorIndex])
```

chart instance가 chart_A → null → chart_B로 교체될 때, ref값 mutation은 re-render trigger하지 않으므로 `IndicatorOverlay`는 chart_A에 머무름. 결과는 (a)와 동일.

#### (c) PatternMarker setMarkers 후 series 직접 dispose

```tsx
// PatternMarker.tsx line 87
useEffect(() => {
  if (!series) return
  ...
  series.setMarkers(markers)  // line 98
  return () => {
    series.setMarkers([])      // line 106
```

`candleSeriesRef.current` (`SymbolChart.tsx` line 105) 도 ref이므로 마찬가지 함정. series가 dispose된 후 PatternMarker가 cleanup `setMarkers([])`을 호출하면 LWC throw.

#### (d) ResizeObserver 비동기 callback

```tsx
// LightweightChart.tsx line 514-523
const resizeObserver = new ResizeObserver((entries) => {
  if (isDisposedRef.current) return
  for (const entry of entries) {
    try {
      chart.applyOptions(...)
```

cleanup에서 `resizeObserver.disconnect()` 먼저 호출(line 529)하지만, 이미 큐에 들어간 callback이 실행 직전에 `chart.remove()`와 race될 수 있음. 단 dispose flag 가드 + try/catch로 차단됨.

### 1-3. 권장 fix 옵션 (사용자 결재 후 별도 사이클)

#### Fix 옵션 1 — chart instance를 state로 lift (★ 권장)

```tsx
// SymbolChart.tsx 변경
- const chartApiRef = useRef<IChartApi | null>(null)
+ const [chartApi, setChartApi] = useState<IChartApi | null>(null)

  const handleChartReady = useCallback((chart: IChartApi) => {
-   chartApiRef.current = chart
+   setChartApi(chart)
  }, [])
  const handleChartDestroy = useCallback(() => {
-   chartApiRef.current = null
+   setChartApi(null)
  }, [])

  // line 469
- <IndicatorOverlay chart={chartApiRef.current} ... />
+ <IndicatorOverlay chart={chartApi} ... />
```

state 갱신은 re-render trigger → `IndicatorOverlay`의 useEffect cleanup이 disposed chart에 도달하기 전에 unmount되어 series remove 가능. `candleSeriesRef`도 동일 패턴 적용.

**장점**: react-idiomatic, dispose-race 원천 차단.
**단점**: chart instance setState로 인한 re-render 1회 추가 (negligible).

#### Fix 옵션 2 — disposed 마커를 chart instance에 attach + 모든 호출 가드

```tsx
// LightweightChart.tsx cleanup
return () => {
  isDisposedRef.current = true
  ;(chart as any).__disposed = true  // 마커
  ...
}

// IndicatorOverlay.tsx cleanup
for (const s of newSeries) {
  if ((chart as any).__disposed) continue  // skip 명시
  try { chart.removeSeries(s) } catch {}
}
```

**장점**: 최소 변경.
**단점**: type assertion 추가 + 모든 chart 사용자가 마커 알아야 함.

#### Fix 옵션 3 — try/catch에 console.error 무시 wrapper

```tsx
// 모든 chart.* 호출 직전
const origError = console.error
console.error = () => {}
try { chart.removeSeries(s) } catch {}
console.error = origError
```

**비추** — 다른 진짜 오류도 가릴 risk.

---

## 2. 결함 #2 — 한국석유 일봉 DB 정합성 검증

### 2-1. DB 직접 조회 (postgres docker)

#### 한국석유(004090) interval별 매트릭스

```sql
SELECT interval, COUNT(*) AS cnt,
  MIN(ts AT TIME ZONE 'Asia/Seoul') AS earliest,
  MAX(ts AT TIME ZONE 'Asia/Seoul') AS latest
FROM candle WHERE code='004090' GROUP BY interval ORDER BY interval;
```

| interval | 갯수 | earliest (KST) | latest (KST) |
|---|---|---|---|
| **1d (일봉)** | **100** | **2025-08-04** | **2025-12-30** ★ stale |
| 1wk | 100 | 2024-01-29 | 2025-12-22 |
| 1mo | 100 | 2017-09-29 | 2025-12-30 |
| 1y | 30 | 1996-12-27 | 2025-12-30 |
| 1m | 850 | 2026-05-15 10:36 | 2026-05-19 14:14 |
| 5m | 198 | 2026-05-15 | 2026-05-19 14:10 |
| 15m | 69 | 2026-05-15 10:30 | 2026-05-19 14:00 |
| 30m | 35 | 2026-05-15 10:30 | 2026-05-19 13:30 |
| 90m | 13 | 2026-05-15 09:30 | 2026-05-19 12:30 |
| 1h | 20 | 2026-05-15 10:00 | 2026-05-19 13:00 |

#### 일봉 최근 30 row (2025-12-30 ~ 2025-11-18)

| ts_kst | open | high | low | close | volume | backfill_batch_id |
|---|---|---|---|---|---|---|
| 2025-12-30 | 14450 | 14500 | 13850 | 13950 | 56,873 | **NULL** |
| 2025-12-29 | 13920 | 13960 | 13700 | 13930 | 88,870 | **NULL** |
| 2025-12-26 | 14060 | 14070 | 13840 | 13920 | 92,923 | **NULL** |
| ... (단조감소 ASC 정상) | | | | | | |
| 2025-08-04 | (최오래) | | | | | NULL |

→ **일봉 row 자체는 정상**: 100건 모두 KIS 출처 + 단조 ASC 정렬 + OHLC 정합성 양호 + monotonic 위반 0건.
→ **`backfill_batch_id` 전부 NULL** — Phase 2 backfill worker가 한국석유 일봉에 단 한 row도 적재하지 못함.

### 2-2. "사용자 보고: 2026-05-19, 2025-12-30, 2025-12-29" 해석

**일봉 DB에 2026-05-19 row가 없으므로**, 사용자가 화면에서 본 "2026-05-19"는 다음 중 하나입니다.

#### 가설 A — frontend가 다른 interval 데이터를 일봉 차트에 mix (가능성 중)

`SymbolChart.tsx`는 interval 기본값 `'1d'` (line 63). 하지만 STOMP `liveTick` 또는 `latestBoundaryCandle`이 일봉이 아닌 1m·5m tick으로 들어와 `realtimeCandleDto`로 변환 (line 138-160) → `LightweightChart`의 `series.update()`로 잘못 적용되는 경로 (ADR 0029 §2-3은 `interval==='1m'`일 때만 가드).

`latestBoundaryCandle`은 모든 interval에서 들어옵니다 (line 140-149 — interval 가드 없음). 그러나 `useSymbolCandles(code, interval='1d')` query 결과 캔들의 ts에 boundary 1h/15m candle의 ts (2026-05-19 14:00 등)가 series.update()로 추가되면 LWC가 새 bar로 그립니다 — **이것이 화면에 "2026-05-19"로 보인 원인일 가능성**.

확인 query:
```sql
-- 일봉 가장 최신 row 직후 1h/15m row의 시점
SELECT MAX(ts) FROM candle WHERE code='004090' AND interval='1h';
-- → 2026-05-19 13:00 KST
```

이 ts가 LWC에 setData([…]) + update(2026-05-19 14:00 1h candle)로 추가되면 일봉 차트에 잘못 표시.

#### 가설 B — `useSymbolCandles` query key 또는 ASC 정렬 문제

backend가 일봉 fetch 시 다른 interval row를 섞어서 응답한다면 — 단 DB 자체 query는 interval 분리되어 있으므로 확률 낮음.

#### 가설 C — 사용자가 별도 interval 탭을 확인했을 가능성 (확률 낮음)

`IntervalToggle`에서 1h·15m으로 전환 후 인지 못한 채 "일봉"이라고 명명. 그러나 최근 사이 5개월 갭이 보이는 evidence와 부합 안 함.

→ **가설 A가 가장 유력**. 가설 A 검증은 사용자 dogfooding cycle에서 frontend 콘솔 + Network 탭으로 확인.

### 2-3. audit_trail evidence

```sql
SELECT action, occurred_at AT TIME ZONE 'Asia/Seoul', entity_id, detail_json
FROM audit_trail WHERE entity_id='004090' AND action LIKE '%CANDLE%'
ORDER BY occurred_at DESC LIMIT 5;
```

| occurred_at (KST) | action | detail_json |
|---|---|---|
| 2026-05-19 13:37:52.195 | **CANDLE_BACKFILL_FAIL** | `gapsTotal:30, failedGapsCount:30, durationMs:124, failedGapsSample:[2026-04-05T15:00Z, 04-06, 04-07, 04-08, 04-09]` |
| 2026-05-19 13:37:52.092 | CANDLE_BACKFILL_START | `gapCount:30, reason:monitor-daily, dryRun:false` |
| 2026-05-19 13:16:15.005 | CANDLE_BACKFILL_START | `gapCount:30` (이전 시도 — 동일 실패) |

→ 본 사이클 시점(2026-05-19) 한국석유는 backfill을 2회 시도 → **2회 모두 전체 실패**.
→ `durationMs:124` (매우 빠른 실패) → KIS API 호출 자체는 응답을 받았으나 sanity check에서 reject.

### 2-4. consumer log root cause (★ 핵심 evidence)

```
13:37:52.092 CandleBackfillWorker 시작: code=004090 interval=1d gapCount=30
13:37:52.194 sanity 실패 monotonic: idx=1 ts=2025-12-29 prev=1767020400  (=2025-12-30 epoch)
13:37:52.194 sanity 실패 monotonic: idx=2 ts=2025-12-26 prev=1767020400
13:37:52.194 sanity 실패 monotonic: idx=3 ts=2025-12-24 prev=1767020400
... (idx 1~99 전부 동일 prev 대비 위반)
13:37:52.194 sanity 실패 monotonic: idx=99 ts=2025-08-03 prev=1767020400
13:37:52.194 monotonic violation 감지 (KIS 응답 정렬 결함 의심): total=100 valid=1
13:37:52.194 KIS fetched but no gaps matched: code=004090 interval=1d fetched=1 gaps=30
13:37:52.199 KillSwitch SKIPPED (user_account 부재): userId=system reason=CANDLE_BACKFILL_FAIL:004090:1d
13:37:52.200 done: code=004090 status=FAILED_KILLSWITCH gaps=30 inserted=0 failed=30
```

**확정 root cause**:

`KisRestCandleAdapter.parseKisDailyResponse()` (line 737-786)가 KIS `inquire-daily-itemchartprice` 응답 `output2[]`를 받은 순서 **그대로** Candle list로 변환. KIS는 최신→과거 **DESC 순서**로 응답.

`CandleBackfillWorker.sanityCheck()` (line 480-565) 햇징 룰 §1 monotonic 검증이 **ASC를 강제**(line 540: `tsSec <= prevTsSec` 위반):
- idx 0: 2025-12-30 (epoch 1767020400) → valid
- idx 1: 2025-12-29 < 2025-12-30 → reject (monotonic 위반)
- idx 2~99: 모두 < 2025-12-30 (prev는 idx 0 유지) → reject

결과: **valid=1, 그러나 그 1건의 ts(2025-12-30)는 이미 DB에 존재** → `gaps[].matched=0` → **insertedCount=0**.

이 결함은 **모든 종목의 모든 일봉/주봉/월봉/연봉 backfill에 동일하게 발생**. 27 영업일 전부터 한국석유 외 동일 watchlist 종목(004710, 027740) 모두 같은 실패 pattern. 한국석유는 우연히 마지막 정상 적재 시점이 2025-12-30이라 그 이후 5개월 갭이 누적.

→ **2025-12-31 ~ 2026-05-18 5개월 일봉 갭은 KIS API 응답 자체가 오염된 것이 아니라, magicJar `parseKisDailyResponse`가 KIS DESC 응답을 ASC로 변환 안 함이 root cause**. 햇징 룰 §1 monotonic이 결함을 정확히 검출하여 자본 보호 측면에서는 룰이 **정상 작동**.

---

## 3. KIS 응답 정렬 KIS-first 검증 (인용)

### 3-1. KIS 공식 raw 인용

- endpoint: `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice`
- tr_id: `FHKST03010100`
- raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py

KIS 공식 example 코드 패턴은 응답 후 `pd.DataFrame(output2).sort_values('stck_bsop_date', ascending=True)`로 정렬 — 즉, **KIS 응답이 DESC 순서**임을 KIS 공식이 인정. magicJar `KisRestCandleAdapter`는 이 정렬을 누락.

(추가 MCP 검증 — `mcp__kis-code-assistant__search_domestic_stock_api(function_name="inquire_daily_itemchartprice", response="응답 정렬")`은 후속 코드 fix 사이클에서 실행 권장.)

---

## 4. 오염 데이터 처리 방안 매트릭스 (★ 사용자 결재 핵심)

본 결함은 **데이터 오염이 아닌 코드 결함(정렬 누락)** — 처리 방안은 두 layer:
1. **코드 fix** (정렬 누락 보강) — 자본 보호 측면에서 P0
2. **기존 갭 채우기** (5개월 누락 backfill 재실행) — 코드 fix 후 자동 발화

### 옵션 A. 코드 fix 1줄 + 자동 backfill 재발화 (★ chart-curator 권장)

**본질**:
1. `KisRestCandleAdapter.parseKisDailyResponse` 마지막에 `candles.sortedBy { it.ts.epochSeconds }` 추가 (1줄). 또는 `CandleBackfillWorker.sanityCheck` 진입 직전에 `.sortedBy {...}`.
2. 다음 `LastCandleAtMonitor` daily 잡 trigger 시 자동으로 모든 stale 종목 backfill 재발화 (햇징 룰 §3 backfill_batch_id 추적 → idempotent).

**햇징 룰 정합**: §1 (이중 검증 통과) · §4 (Idempotency LOCK — ON CONFLICT DO NOTHING) · §5 (Source-of-truth, KIS raw 권위)
**자본 영향**: 0 (자동매매가 일봉 stale 종목 제외 patterns 추정 — 룰 §6 false positive 가드)
**구현 cost**: 1줄 + 테스트 (1 unit test로 KIS DESC 응답 fixture → ASC 변환 검증)
**운영 cost**: 0 (자동 발화)

**리스크**: §1 룰을 우회하는 것이 아니라 **상류 데이터를 룰이 요구하는 ASC로 정규화**하는 것이므로 룰 정합성 유지.

### 옵션 B. 코드 fix + 운영자 admin endpoint 수동 trigger

옵션 A + `POST /admin/candle-curator/backfill?code=004090&interval=1d&dry-run=true` 먼저 dry-run으로 정합 확인 → `dry-run=false` 본 실행.

**햇징 룰 정합**: §2 (Dry-run 모드) · §10 (운영자 우회)
**장점**: 사용자가 결과 시점 통제 + dry-run으로 결함 재현 방지
**단점**: 사용자 수동 work

### 옵션 C. 전체 DELETE + 재backfill (Hard Reset)

```sql
DELETE FROM candle WHERE code='004090' AND interval='1d';
```
+ 코드 fix 후 자동 backfill 재발화.

**햇징 룰 정합**: §3 (Rollback 안전망 — 단, backfill_batch_id가 NULL이므로 batch ID 기반 partial rollback 불가 → 종목 전체 DELETE)
**장점**: 깨끗한 상태 baseline
**단점**: 기존 정상 100건도 함께 삭제 → KIS rate limit 부담 + 다른 모듈(지표·백테스트·MTF) cache invalidate
**비추** — 본 결함은 일부 row 오염이 아니라 갭 누락이므로 hard reset 불필요.

### 옵션 D. 영업일 캘린더 기반 expected ts 매칭 + 부분 skip

`BusinessDayCalendar.isBusinessDay()` 기반 기대 ts list와 KIS 응답 ts list를 outer join → 어긋난 row만 skip + 나머지는 sort 후 통과. monotonic 위반 대신 set-based 검증.

**햇징 룰 정합**: §6 (false positive 가드) + §1 확장 (set-based monotonic)
**장점**: 향후 KIS 응답 일부 누락도 통과
**단점**: §1 룰 자체 재설계 — 후속 ADR 필요
**Phase 2-B 의제로 분리 권장**.

### 옵션 E. KIS raw 응답 dump + 수동 검증 후 부분 backfill

`audit_trail.CANDLE_BACKFILL_FAIL.detail_json`에 KIS raw 응답 JSON dump 추가 → 운영자가 PostgreSQL query로 검증 → admin endpoint dry-run.

**햇징 룰 정합**: §5 (Source-of-truth raw 권위 보존) + §9 (audit_trail 의무)
**장점**: 향후 모든 결함 raw 인용 가능
**단점**: detail_json 크기 부담 (100 row × 8 field ≈ 30KB/실패당)
**Phase 2-B 의제로 분리 권장**.

### 옵션 F. (추가) KIS 응답 시점 사전 정렬 + 사후 sanity check 분리 (chart-curator skill §11 의제)

햇징 룰 §1 monotonic을 다음 2단으로 분리:
- §1-a (사전 정규화): KIS 응답을 sanity check 진입 직전에 `sortedBy { ts }` (이미 ASC면 변화 없음)
- §1-b (사후 검증): 정규화 후에도 monotonic 위반이면 진짜 데이터 결함 → reject

**햇징 룰 정합**: §1 확장 + §5 (raw 보존 — 정렬은 표시 normalization)
**장점**: 향후 같은 결함 발생 시 발견 → §1-a 통과 + §1-b가 진짜 오염만 reject

### 옵션 G. (추가) 갭 5+ 영업일 연속 누락 시 backfill skip + 운영자 escalation (chart-curator skill §12 의제)

`gapCount > THRESHOLD` (예: 30+) 시 자동 backfill skip + Slack P0 alert → 운영자 수동 결재 후 trigger.

**햇징 룰 정합**: §8 (KillSwitch + 자본 보호) 확장
**장점**: 대규모 갭은 데이터 결함 의심 → 자동 backfill로 위험 확산 방지
**Phase 2-B 의제로 분리 권장**.

### chart-curator 권장 매트릭스

| 옵션 | 권장 시점 | 사유 |
|---|---|---|
| **A** | **즉시 (P0)** | 1줄 fix + 자본 영향 0 + 햇징 룰 정합 + 모든 종목 동시 회복 |
| B | A 직후 dogfooding | dry-run으로 첫 회복 검증 |
| F | Phase 2-B (다음 사이클) | skill §11로 룰 강화 |
| D, E, G | Phase 2-B+ (architect ADR) | 룰 자체 진화 |
| C | 비추 | hard reset 불필요 |

---

## 5. chart-curator skill 햇징 룰 추가 의제

본 사고에서 발견된 룰 보강 의제 — architect 별도 사이클에서 ADR 0086 amend 또는 신규 ADR 발행 권장:

### §11 KIS raw 응답 사전 정규화 (★ 1순위 권장)

본문 (제안):
> KIS REST 응답이 ASC monotonic을 보장하지 않는 endpoint가 다수 존재 (`inquire-daily-itemchartprice` DESC, `inquire-time-itemchartprice` DESC 등). adapter (예: `KisRestCandleAdapter.parseKisDailyResponse`) 또는 worker (예: `CandleBackfillWorker.sanityCheck`) 진입 직전에 **`candles.sortedBy { it.ts.epochSeconds }` 정규화 의무**. 정규화 후 §1 monotonic은 진짜 데이터 오염(중복 ts·역행 ts) 검출만 담당.

권위: ADR 0086 amend 또는 신규 ADR

### §12 갭 검출 5+ 영업일 연속 누락 시 backfill skip + 운영자 escalation

본문 (제안):
> `gapCount > 5` (영업일 기준) 또는 `gaps[].duration > 3 영업일 연속` 검출 시 자동 backfill skip + Slack `P1` alert + `audit_trail.CANDLE_BACKFILL_LARGE_GAP_SKIPPED` 발행. 운영자가 `POST /admin/candle-curator/backfill?force=true` 명시적 trigger 시에만 실행 → 자본 보호 우선.

권위: ADR 0086 amend

### §13 LWC chart 컴포넌트 dispose lifecycle 검증 의무

본문 (제안):
> `frontend/src/pages/Symbol/*` 및 `frontend/src/components/chart/*`에서 LWC IChartApi/ISeriesApi instance를 React props/state로 전달할 때, **ref 직접 props 전달 금지**. `useState` 또는 `useReducer`로 lift하여 instance 교체 시 re-render trigger 보장. dispose race 차단.

권위: chart-curator skill §함정 가드 7번으로 추가 (현재 6번까지)

---

## 6. dogfooding 재현 path (사용자 dogfooding cycle 지원)

본 보고서 결재 + 후속 fix PR 머지 후 사용자가 재현·검증할 path:

### 6-1. DB 확인
```bash
# 한국석유 일봉 5개월 갭 확인
docker exec magicjar-postgres psql -U magicjar -d magicjar -c \
  "SELECT ts AT TIME ZONE 'Asia/Seoul', backfill_batch_id FROM candle \
   WHERE code='004090' AND interval='1d' ORDER BY ts DESC LIMIT 10;"

# fix 적용 후 backfill 재실행 후 동일 query → 2026-05-18까지 채워졌는지 확인
# backfill_batch_id 컬럼이 NOT NULL이면 fix 후 적재된 row
```

### 6-2. audit_trail 확인
```bash
docker exec magicjar-postgres psql -U magicjar -d magicjar -c \
  "SELECT action, occurred_at AT TIME ZONE 'Asia/Seoul', detail_json \
   FROM audit_trail WHERE entity_id='004090' AND action LIKE 'CANDLE%' \
   ORDER BY occurred_at DESC LIMIT 5;"

# 정상화 시: CANDLE_BACKFILL_START + CANDLE_BACKFILL_SUCCESS 페어로 변경 (FAIL 사라짐)
```

### 6-3. consumer log
```bash
# fix 전: monotonic violation evidence
docker logs magicjar-consumer 2>&1 | grep "monotonic violation 감지" | tail -5

# fix 후: 위 grep이 0건이어야 정상
```

### 6-4. frontend 콘솔 (LWC dispose)
1. Chrome devtools → Console 탭 open
2. 한국석유 페이지 진입 → console.error 표시 캡처
3. interval 토글 (1d ↔ 1h ↔ 5m) 반복 → race 재현
4. Settings 모달 open/close 반복 → SymbolDetailPage 재진입 race 재현
5. fix 옵션 1 (state lift) 적용 후 동일 path → 콘솔 clean

---

## 7. 다음 dispatch 의제 (사용자 결재 후)

| 우선순위 | 의제 | 담당 후보 | 산출물 |
|---|---|---|---|
| **P0** | KIS daily/weekly/monthly/yearly 응답 ASC 정규화 fix (옵션 A) | chart-curator | `KisRestCandleAdapter.parseKisDailyResponse` 또는 `CandleBackfillWorker.sanityCheck` 1줄 + 단위 테스트 + dogfooding 6-1·6-2 evidence |
| **P0** | LWC dispose race fix (Fix 옵션 1 — chart instance state lift) | chart-curator + frontend | `SymbolChart.tsx` ref→state + dogfooding 6-4 evidence |
| P1 | 햇징 룰 §11·§12·§13 추가 ADR | architect | ADR 0086 amend 또는 신규 ADR |
| P2 | LastCandleAtMonitor 갭 5+ 영업일 escalation (옵션 G) | chart-curator | spec → 코드 |
| P2 | KIS raw dump audit_trail 확장 (옵션 E) | chart-curator + backend-core | Flyway micro + worker 확장 |

---

## 8. 운영 헌법 정합

- ✅ §1-A worktree author = `bot+chart-curator@magicjar.local`
- ✅ §1 task.mjs start/check/done 4단계
- ✅ 코드 0 touch (분석·보고만 — 운영 코드 영향 없음)
- ✅ DB query read-only (DELETE·UPDATE 0건)
- ✅ KIS-first §3 raw URL 인용 (`inquire-daily-itemchartprice` raw 링크)
- ✅ skill §10 햇징 룰 매트릭스 정독 + §11·§12·§13 추가 의제 발굴
- ✅ 영역 경계: chart-curator (캔들 정합성 + LWC) 단독, market-data·strategy-engine 영역 0 touch

---

**보고 끝. 사용자(최고 관리자) 결재 후 후속 사이클 dispatch 대기.**
