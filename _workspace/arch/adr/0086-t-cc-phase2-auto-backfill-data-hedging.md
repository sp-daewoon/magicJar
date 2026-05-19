# ADR 0086: T-CC Phase 2 — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 14건 (chart-curator 신입 보호)

- 상태: **Accepted (2026-05-19 amend — 햇징 룰 §11·§12·§13·§14 추가 · 한국석유 PR #535·#587 dogfooding evidence + 사용자 명시 active gap detection 정합 + KIS 호출 한도 cover + fix 후 regression 영구 차단)**
- 작성일: 2026-05-19
- 결정자: architect (opus 4.7 1M) — 사용자 위임 명시 "신입한테 위 내용 잘 상기시켜서 진행해" + amend "갭 5+ 영업일 연속 누락 시 backfill skip + 운영자 escalation 이거 뭐야? 우리 정책이 있었어???" + PR #587 진단 evidence(`RealKisRestAdapter` → `KisRestCandleAdapter` 재작성 시 페이지네이션 algorithm regression)
- 영역: 캔들 정합성 + 메타·운영·하네스 (chart-curator skill SoT 정합)
- 관련: ADR 0013(영업일 캘린더) · ADR 0019(캔들 저장 정책) · ADR 0028(Watchlist Ingestion) · ADR 0037(KIS API Rate Limit) · ADR 0043(KillSwitch 행동 정의) · ADR 0057(KIS-only) · ADR 0062(합산 가공 5종) · ADR 0063(NXT 매트릭스) · ADR 0085(chart-curator 영입)

---

## 1. Context

### 1-1. T-CC Phase 1 dogfooding evidence (선행 마감)

사용자 dogfooding(2026-05-19 KST) — **한국석유(004090)** 일봉 last=2025-12-24 ≈5개월 stale vs 1분봉 last=2026-05-18 15:19 정상. T-CC Phase 1(PR #514 머지) — `LastCandleAtMonitor` daily 18:00 KST cron + `LastCandleAtBootstrapRunner` 부팅 시 즉시 발화 + admin endpoint `POST /admin/monitor/last-candle-at` 신설 완료.

### 1-2. Phase 2 진입 사유

Phase 1은 **stale 검출 + 일괄 재수집 트리거**까지. 그러나:
- 갭 검출 알고리즘 0 — 중간 갭 누락은 last_ts 단순 비교로 검출 불가
- 자동 backfill = `ColdStartExecutor.submit()` 일괄 재수집 — KIS 호출 cost 비효율 + 기존 정상 데이터 덮어쓰기 risk
- 재시도 + KillSwitch 미연동
- 데이터 햇징 룰 0 — sanity check·dry-run·rollback·idempotency 부재

### 1-3. 신입 chart-curator 보호 필요성 (사용자 의제 baseline)

사용자(최고 관리자) 2026-05-19 KST 명시:

> "신입 에이전트야 이거 할때 데이터가 기준으로 문제가 있는걸 어떻게 햇징할지 잘 고민하고 처리해야해. 아키텍처야 신입한테 위 내용 잘 상기시켜서 진행해"

chart-curator는 ADR 0085(2026-05-19, PR #511 머지) 영입 신입. **자동 backfill 워커가 잘못된 데이터를 적재할 risk가 운영 baseline의 잠재 위협** — architect는 사전에 **데이터 결함 햇징 룰 매트릭스**를 명시할 의무.

### 1-4. fitness gap (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합)

- 자동매매 필요성 — ✅ 일봉 갭 누락 → EMA 200 시그널 왜곡 → 자본 손실 risk
- v1/v2 잔재 — ❌ Phase 2는 chart-curator 신규 책임 (ADR 0085 영입 정합)
- MVP 적합 — ✅ watchlist 30종목 × 30 갭 = 900 호출/cycle (KIS rate limit 분산 충분)

---

## 2. Decision

### 2-1. 14 데이터 햇징 룰 LOCK

chart-curator가 캔들 데이터를 적재·변경·갱신·삭제할 때 **반드시** 다음 14 룰 적용 (spec §5 본문 정합 + 2026-05-19 amend §11·§12·§13·§14 추가).

| # | 룰 | 위반 시 risk |
|---|-----|-------------|
| 1 | **이중 검증 (Double Verification)** — KIS raw fetch 응답 적재 전 sanity check (가격 range·거래량 비음수·time monotonic·OHLC 정합성 `high≥max(open,close)·low≤min(open,close)`) | 잘못된 캔들 적재 → 백테스트 결과 왜곡 → 실거래 시그널 오작동 |
| 2 | **Dry-run 모드** — `magicjar.chart-curator.backfill.dry-run=true` toggle 시 KIS fetch만 + DB 적재 0 + log·response 출력 | 운영자 사전 검증 path 부재 → 잘못된 backfill 적재 |
| 3 | **Rollback 안전망** — backfill_batch_id로 변경 추적 + `audit_trail.CANDLE_BACKFILL_START` row + 결함 검출 시 `DELETE FROM candle WHERE backfill_batch_id=?` 가능 | 결함 검출 후 정정 path 부재 → 운영 부채 누적 |
| 4 | **Idempotency LOCK** — UPSERT `ON CONFLICT DO NOTHING` (historic 보존) | 기존 정상 historic candle을 KIS retry 응답으로 덮어써서 회귀 |
| 5 | **Source-of-truth 룰** — KIS raw 응답 권위 + 합성·계산·가공 적재 금지 | 백테스트 vs 실거래 시그널 분기 → 자본 손실 |
| 6 | **갭 검출 false positive 가드 (passive)** — 거래정지·정리매매·휴장일·시간외 제외 (`KIS_COLD_START_SKIPPED` 패턴 + `BusinessDayCalendar.isBusinessDay()` 의무 호출). **"이 조건이면 skip해도 OK"** | 정상 휴장 갭을 결함으로 오인 → KIS 무의미 호출 + alert spam |
| 7 | **갭 검출 false negative 가드** — `last_candle_at` null 시 `Long.MAX_VALUE` (Phase 1 패턴 100% 재사용) + watchlist 신규 진입 종목 즉시 cold-start | 미검출 갭 누적 → 자동매매 시그널 무력화 |
| 8 | **재시도 backoff + KillSwitch** — 3회 (즉시 + 1s + 5s) 후 stop + Slack P0 alert + ADR 0043 정합 KillSwitch on | 무한 retry → KIS rate limit burn + 자동매매 무력화 |
| 9 | **audit_trail 의무** — 시작·성공·실패·skip·rollback 5 path 모두 row 1건 (`CANDLE_BACKFILL_START/SUCCESS/FAIL/SKIPPED/ROLLBACK`) | 운영 audit 불가 → 결함 root cause 추적 불가 |
| 10 | **운영자 우회 path** — admin endpoint `POST /admin/candle-curator/backfill?code=...&interval=...&dry-run=true` 신설 | 운영 자율성 부재 → 결함 발생 시 수동 정정 불가 |
| 11 | **KIS raw 사전 ASC 정규화 (신설 amend)** — adapter layer가 raw 응답 반환 직전 `.sortedBy { it.ts.epochSeconds }` 의무. `KisRestCandleAdapter.parseKisDailyResponse`·`parseKisMinuteResponse` 등 모든 parse 함수. 이중 LOCK: `CandleBackfillWorker.sanityCheck` ASC monotonic 가드는 마지막 안전망 | KIS DESC 응답 + sanity check reject → insertedCount=0 → 갭 누적 (한국석유 PR #535 root cause) |
| 12 | **Active gap detection (4축 교차 검증, 신설 amend)** — 4축 모두 TRUE인데 candle row 부재 시 **명확한 결함 → 자동 backfill 의무**: (1) `market_calendar.is_business_day=TRUE` (ADR 0013) (2) `instrument.trading_halt=FALSE` 또는 거래정지 history 부재 (3) `instrument.liquidation=FALSE` (4) `instrument.listed_at <= ts`. **§6 passive와 대비 — §12는 active "이 조건인데 없으면 결함"** | 단순 임계치(예: "5+ 영업일 연속 skip")는 임의 + 정합성 검증 부재 → 결함 누락 risk |
| 13 | **LWC chart instance state lift (신설 amend)** — TradingView LWC v4 chart instance는 반드시 `useState`로 lift. `useRef` 직접 props 전달 금지. React 18 StrictMode 이중 mount + ref mutation re-render 미발생 → 자식 useEffect cleanup이 disposed chart에 도달 race → `Uncaught Error: Object is disposed` | LWC v4 console.error spam + 차트 컴포넌트 안정성 회귀 (한국석유 PR #535 결함 #1) |
| 14 | **KIS 호출 한도 cover 의무 (신설 amend)** — 모든 KIS 캔들/체결/잔고 endpoint 호출 시 **100건 한도 cover** (cursor 페이지네이션 + MAX_PAGES guard + distinctBy boundary dedup + 응답 row 0 break). **이중 LOCK** — (a) algorithm 본체는 별도 helper class/object 분리 의무 (예: `KisCursorPaginator`) — 후속 재작성/리팩토링 시 inline 발행 algorithm regression 영구 차단 (b) helper에 회귀 test 1건 의무 (algorithm 자체 검증) | 100건만 적재 후 종료 → 1년치 backfill 시 100봉 → 5개월 갭 재발 → 백테스트 왜곡 → 실거래 시그널 오작동 → **자본 손실** (한국석유 PR 915699b regression evidence) |

**위반 시 risk 종합**: 잘못된 캔들 적재 → 백테스트 결과 왜곡 → 실거래 시그널 오작동 → **자본 손실**. chart-curator는 단일 owner이므로 본 룰 위반은 운영 부채 전체 책임 단일 귀속.

### 2-2. 신규 컴포넌트 LOCK

| 산출물 | 위치 | 책임 |
|--------|------|------|
| `CandleGapDetectorPort` | `domain/.../market/port/` | 도메인 port (영업일 캘린더 기반 갭 검출 인터페이스) |
| `CandleGapDetectorAdapter` | `consumer/.../aggregation/` | port 구현 (영업일 expected sequence + DB actual diff) |
| `CandleBackfillWorker` | `consumer/.../aggregation/` | 갭만 정밀 backfill + 재시도 3회 + KillSwitch + 햇징 룰 10건 본체 |
| `CandleBackfillAdminController` | `consumer/.../aggregation/` | admin endpoint (`POST /admin/candle-curator/backfill` — dry-run 옵션) |
| `CandleRepository.findAllTsByCodeAndIntervalBetween()` add-only | `domain/.../port/` + 어댑터 | 범위 내 모든 ts 조회 (chart-curator 자율 결정 — 기존 `findByCodeAndInterval()` filter도 가능) |
| Flyway V39 micro | `api/.../resources/db/migration/` | `candle.backfill_batch_id VARCHAR(96) NULL` add-only (인덱스 0) |
| `LastCandleAtMonitor.checkAllCodes()` 1 line edit | `consumer/.../monitoring/` | `coldStartExecutor.submit()` → `candleBackfillWorker.submit()` (chart-curator 자율 결정) |

### 2-3. 갭 검출 알고리즘 LOCK

```
windowDays = 30 (LOCK — KIS 호출 cost 보호)
expected = (now - windowDays..now).filter { calendar.isBusinessDay(it) }
actual = candleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from, to)
gaps = expected.filterNot { it in actualSet }
```

### 2-4. dispatch plan LOCK

- **Phase 2-A**: chart-curator 단일 PR (~835 LOC) — 도메인 port + 어댑터 + 워커 + admin endpoint + Flyway V39 + Phase 1 1 line edit + unit test (햇징 룰 10건 검증)
- **Phase 2-B**: chart-curator 자율 결정 (Phase 2-A에 모두 포함하면 분리 불필요) — Slack outbox · audit_trail integration 검증

### 2-5. release v3.6.18 patch 권장

- `feedback_release_policy_patch_first` 정합 — v3.7.0=W4 LOCK
- T-CC Phase 2(chart-curator) + ADR 0084 P1-3(backend-core/market-data) 영역 분리 → 병렬 dispatch 가능

### 2-6. 룰 §11 KIS raw 사전 ASC 정규화 (신설 amend · 한국석유 PR #535 evidence)

KIS API 응답이 timeframe별 sort order가 일관되지 않음:
- `inquire-daily-itemchartprice` (FHKST03010100) — **DESC 응답** (최신 ts부터 역순)
- `inquire-time-itemchartprice` (FHKST03010200) — ASC 또는 DESC (페이지 분기)

**룰 본문**: adapter layer가 raw 정규화 책임. KIS 응답 변환 직후 반환 직전 `.sortedBy { it.ts.epochSeconds }` 의무. 모든 parse 함수가 단일 정렬 LOCK:
- `KisRestCandleAdapter.parseKisDailyResponse`
- `KisRestCandleAdapter.parseKisMinuteResponse`
- 향후 추가될 모든 KIS 캔들 응답 parse 함수

**이중 LOCK**: `CandleBackfillWorker.sanityCheck`의 time monotonic 가드(룰 #1)는 마지막 안전망. 정상 path는 adapter 정규화가 우선이고, sanity check은 회귀·결함 검출용.

**사유 (evidence)**: 한국석유(004090) PR #535 root cause:
- KIS DESC 응답(2025-12-30 → 2025-10-28 역순) 그대로 변환
- `CandleBackfillWorker.sanityCheck` ASC monotonic 가드가 idx 1~99 reject
- valid=1 → gaps[].matched=0 → insertedCount=0
- 5개월 갭 누적 (`2025-12-31 ~ 2026-05-18`)

consumer log evidence:
```
13:37:52.194 monotonic violation 감지 (KIS 응답 정렬 결함 의심): total=100 valid=1
```

**구현 의제 (market-data 별도 사이클)**: `KisRestCandleAdapter` 본문 5 parse 함수 각각 반환 직전 1줄 add-only.

### 2-7. 룰 §12 Active gap detection (4축 교차 검증) ★ 사용자 명시 정합 룰

**기존 §6 (passive false positive 가드)의 active 정합 버전**. 사용자(최고 관리자) 2026-05-19 KST 명시 인용:

> "갭 5+ 영업일 연속 누락 시 backfill skip + 운영자 escalation 이거 뭐야? 우리 정책이 있었어??? 이런거? 영업일 연속을 기준으로 하면 안되는거고. 실제 장이 열렸던 날과 아니였던 날을 관리하고, 그 장중이였는데 거래중지는 특별한 이력이없는데 데이터가 없는걸 찾아내는 검증 룰이 있어야지 않아?"

**4축 권위 source 교차 검증**:

| 축 | 권위 source | 검증 의미 |
|----|------------|----------|
| 1 | `market_calendar.is_business_day = TRUE` (ADR 0013) | KRX 영업일 + 임시휴장 반영 (DB 권위) |
| 2 | 종목 거래정지 이력 부재 | `instrument.trading_halt = FALSE` 또는 거래정지 history 부재 |
| 3 | 종목 정리매매 이력 부재 | `instrument.liquidation = FALSE` |
| 4 | 종목 상장일 이후 | `instrument.listed_at <= ts` |

**룰 본문**: 위 4 조건 **모두 TRUE** (장 개장 + 거래 가능 + 상장 이후)인데 candle row 부재 = **명확한 결함** → 자동 backfill 의무 + `audit_trail.CANDLE_GAP_DETECTED` row 발행.

**§6 (passive) vs §12 (active) 대비**:

| 측면 | §6 (passive) | §12 (active) |
|------|-------------|--------------|
| 의미 | "이 조건이면 skip해도 OK" | "이 조건인데 없으면 결함" |
| 결과 | KIS 호출 회피 + alert 회피 | KIS 호출 + backfill 의무 + audit |
| 적용 시점 | gap 검출 후 false positive 제거 | gap 검출 알고리즘 본체 |

**구현 의제 (chart-curator 별도 사이클)**:
1. `CandleGapDetectorAdapter.detectGaps()` 본문에 4축 검증 강화
2. expected ts list 생성 시 `market_calendar.is_business_day = TRUE` 의무 (현재 `BusinessDayCalendar.isBusinessDay()` LOCK이 1차 권위)
3. 각 ts마다 instrument status snapshot 조회 → 거래정지·정리매매·상장이전 ts skip
4. 위 가드 통과 ts에서 candle absence = gap 결과 리스트
5. `audit_trail.CANDLE_GAP_DETECTED` row 발행 (4축 통과 evidence inline)

**chart-curator(신입) §12 잘못된 제안 영구 차단**:

> ~~"갭 5+ 영업일 연속 누락 시 backfill skip + 운영자 escalation"~~

위 임의 임계치는 **폐기**. 사유:
- "5+"는 임의 숫자 — 어떤 권위 source도 정합되지 않음
- "연속"은 의미 부재 — 거래정지 종목·휴장 연휴 등 정합한 사유로 인한 갭과 데이터 결함을 구분 불가
- 정합한 §12는 4축 권위 source 교차 검증

**evidence**: chart-curator 한국석유 보고서 (`_workspace/reports/2026-05-19-kor-petroleum-investigation-report.md`)의 옵션 G "갭 5+ 영업일 escalation" 제안이 사용자 비판 trigger.

### 2-8. 룰 §13 LWC chart instance state lift (신설 amend · 한국석유 PR #535 결함 #1 evidence)

TradingView LWC v4 chart instance를 React `useRef`로 보관 + props로 직접 전달 시 dispose race 발생:

**race 시퀀스**:
1. React 18 StrictMode mount A → `chart_A` 생성 → `chartApiRef.current = chart_A`
2. StrictMode unmount A → `chart_A.remove()` (dispose) → 자식 cleanup useEffect는 아직 `chartApiRef.current = chart_A` 인식
3. StrictMode mount B → `chart_B` 생성 → `chartApiRef.current = chart_B`
4. ref mutation은 **re-render trigger 없음** → 자식 컴포넌트(IndicatorOverlay·PatternMarker)는 mount A에서 받은 prop 그대로 → cleanup이 disposed `chart_A`에 도달 → `Uncaught Error: Object is disposed`

**룰 본문**: chart instance는 반드시 `useState`로 lift. `useRef` 직접 props 전달 금지.

```tsx
// 잘못된 패턴 (race 발생)
const chartApiRef = useRef<IChartApi | null>(null)
useEffect(() => {
  chartApiRef.current = createChart(container, options)
  return () => { chartApiRef.current?.remove(); chartApiRef.current = null }
}, [])
return <IndicatorOverlay chart={chartApiRef.current} ... />

// 정합 패턴 (state lift)
const [chartApi, setChartApi] = useState<IChartApi | null>(null)
useEffect(() => {
  const chart = createChart(container, options)
  setChartApi(chart)
  return () => { chart.remove(); setChartApi(null) }
}, [])
return <IndicatorOverlay chart={chartApi} ... />
```

**구현 의제 (chart-curator 별도 사이클 — frontend SymbolChart 영역)**:
- chart instance setter pattern: `const [chartApi, setChartApi] = useState<IChartApi | null>(null)`
- candle series도 동일 패턴: `const [candleSeries, setCandleSeries] = useState<ISeriesApi<'Candlestick'> | null>(null)`
- cleanup 시 `setChartApi(null)` + `setCandleSeries(null)` → 자식 useEffect는 null 인식하면 진입 skip
- 자식 컴포넌트 props 타입 명시: `chart: IChartApi | null` + `series: ISeriesApi<'Candlestick'> | null`
- 자식 useEffect 진입 가드: `if (!chart || !series) return`

**evidence**: chart-curator 한국석유 보고서 §결함 #1 (`_workspace/reports/2026-05-19-kor-petroleum-investigation-report.md`):
- `SymbolChart.tsx` line 99·105 `chartApiRef`/`candleSeriesRef`를 `useRef`로 보관
- line 469·478에서 ref.current를 props로 전달
- console.error 추적 결과: React 18 StrictMode 이중 mount + ref mutation re-render 미발생 race

### 2-9. 룰 §14 KIS 호출 한도 cover 의무 (신설 amend · PR #587 dogfooding evidence)

**기존 §11(KIS raw 사전 ASC 정규화)만으로 부족** — 한국석유(004090) 빈 DB 재발 진단 결과(PR #587 chart-curator 보고서) KIS 100건 호출 한도 cover algorithm regression이 root cause. ADR 0086 §11이 ASC 정렬만 정합화하고 페이지네이션을 명시하지 않으면 후속 재작성 시 algorithm 누락 risk 영구 상존.

**역사 evidence (regression 시퀀스)**:

| 일자 | PR | 변경 | 검증 결과 |
|------|-----|------|---------|
| 2026-05-04 | 2cbd4e8 | `RealKisRestAdapter` cursor 페이지네이션 algorithm 검증 구현 | 한국석유 1년 full backfill 100봉 → 243봉 (페이지네이션 작동 evidence) |
| 2026-05-11 | 915699b | `RealKisRestAdapter` → `KisRestCandleAdapter` 재작성 | 1m만 페이징 유지 + **일/주/월/년봉 단일 호출 regression** (algorithm 누락) |
| 2026-05-19 | PR #587 | chart-curator 진단 보고서 + B+E fix 사이클 | ASC 정규화만 fix(§11) + 페이지네이션 누락 → 빈 DB 재발 확인 → §14 신설 trigger |

**룰 §14 본문**:

1. **호출 한도 cover 의무** — 모든 KIS 캔들/체결/잔고 endpoint 호출 시 100건 한도 cover. KIS-first MCP raw `inquire_daily_itemchartprice.py` docstring 정합("한 번의 호출에 최대 100건"). 단일 호출 종료 금지.
2. **cursor 페이지네이션 algorithm 의무** — MAX_PAGES 6 (1년치 backwalk) 또는 fromDate 기준 종료 + distinctBy boundary dedup (페이지 경계 중복 ts 방지) + 응답 row 0건 시 break (KIS 데이터 종점 도달) + ASC 정규화는 페이지 합산 후 마지막 단계 (§11 monotonic 정합).
3. **helper class 분리 의무** — algorithm 본체는 별도 helper class/object로 분리(예: `KisCursorPaginator`, `CandleSanityChecker`). caller 코드에서 helper 호출만 (algorithm inline 발행 금지). 후속 재작성/리팩토링에서 algorithm regression 영구 차단.
4. **회귀 test 1건 의무** — helper에 회귀 test 1건 의무 — algorithm 자체 검증 (pagination round-trip / sanity reject count / sort idempotence 등). caller 통합 test와 별도로 algorithm 자체 verification 보장.
5. **§함정 가드 cross-ref 매트릭스** — caller 코드 KDoc에 `// skill §함정 #N` 인용 의무 — 후속 작업자에게 함정 location 명시.

**구현 의제 (chart-curator·market-data 별도 사이클)**:
- `KisRestCandleAdapter` 본문 5 parse 함수에 `KisCursorPaginator` helper 합류 (또는 신규 helper class 생성)
- `KisCursorPaginator` 회귀 test 1건 (pagination round-trip + boundary dedup + 0-row break)
- caller KDoc에 `// skill §함정 #8·#10·룰 §14` 인용 매트릭스

**chart-curator 신입 잘못된 inline algorithm 발행 영구 차단**:

> ~~"adapter parse 함수에 페이지네이션 algorithm을 inline 발행"~~

위 inline 패턴은 **폐기**. 사유:
- inline algorithm은 후속 재작성/리팩토링에서 통째로 사라질 risk (PR 2cbd4e8 → PR 915699b regression evidence)
- helper class 분리 + 회귀 test 의무가 algorithm 영구 보존 LOCK

**evidence**: chart-curator 한국석유 PR #587 진단 보고서 — `KisRestCandleAdapter.parseKisDailyResponse` 등 5 parse 함수에 페이지네이션 algorithm 누락 → 100건만 적재 후 종료 → 한국석유 일봉 backfill 시 100봉(약 5개월)만 적재 → 그 이전 데이터 부재로 사용자 dogfooding 시점에 빈 DB 재발.

---

## 3. Consequences

### 3-1. 긍정 결과

- chart-curator skill SoT § 신설 — 신입 에이전트 영구 보호 룰 LOCK (10 → 13 룰 확장)
- 갭 검출 정밀화 → 자동 backfill 호출 cost 감소 (전체 재수집 대비 90%+ 효율)
- audit_trail 5 path → 운영 audit 가능 → 결함 root cause 추적 가능
- KillSwitch 자동 연동 → 자동매매 자본 보호
- **§11 (amend)** — KIS DESC 응답 root cause 영구 차단 (한국석유 PR #535 dogfooding evidence)
- **§12 (amend)** — 사용자 명시 active gap detection 4축 권위 source 교차 검증 정합 (임의 임계치 영구 폐기)
- **§13 (amend)** — LWC v4 React 18 StrictMode dispose race 영구 차단 (한국석유 PR #535 결함 #1)
- **§14 (amend)** — KIS 100건 호출 한도 cover 의무 + helper class 분리 + 회귀 test 영구 차단 (한국석유 PR #587 진단 evidence — PR 2cbd4e8 검증 후 PR 915699b 재작성 시 페이지네이션 algorithm regression)

### 3-2. 부정 결과

- chart-curator 영역 책임 추가 (운영 부채 단일 귀속) — ADR 0085 영입 결정 정합 보전
- Flyway V39 micro 1건 — `candle.backfill_batch_id` column 추가 (인덱스 0 → 운영 query 영향 0)
- KillSwitch 발동 임계치 fine-tune 필요 (전체 vs 부분 비율 — chart-curator dogfooding 자율 결정)

### 3-3. 인접 트랙 영향 0 검증

- T-CC Phase 1 (`LastCandleAtMonitor`) — 1 line edit만 (chart-curator 자율 결정 — `coldStartExecutor.submit()` 그대로 두고 워커 호출 추가도 가능)
- T-O 자동 path — 0 touch (캔들 데이터 layer 변경만 — 주문 path 영향 0)
- T-ACCT(ADR 0081) · T-MAN(ADR 0082) — 0 touch
- ADR 0084 P0 baseline (주문 path 안정성) — 0 touch (영역 분리 병렬 가능)

---

## 4. Alternatives Considered

### A. Cron-only manual (거부)

운영자가 admin endpoint로 수동 backfill만. 자동화 0.

**거부 사유**: 사용자 dogfooding evidence(한국석유 5개월 stale)는 자동화 부재로 발생. Phase 1 마감 evidence(PR #514) 이후 stale 검출은 자동화됐지만 backfill 실행은 여전히 manual 1단계 필요(`coldStartExecutor.submit()`가 호출되어도 KIS 응답 결함 시 재시도 없음). 운영자 매번 개입 불가능 → MVP 부적합.

### B. Auto-backfill 채택 + 햇징 룰 (채택) ✅

신입 chart-curator가 자동 backfill 워커를 구현하되, architect가 사전에 햇징 룰 매트릭스를 LOCK으로 명시하여 신입 보호 + 데이터 결함 risk 사전 차단.

**채택 사유**:
- 사용자 의제(2026-05-19) "신입한테 위 내용 잘 상기시켜서 진행해" 정합
- chart-curator 영입 baseline(ADR 0085) 정합 — 단일 owner 책임
- 10 햇징 룰 매트릭스가 운영 부채 잠재 risk를 사전 LOCK으로 차단 → 자본 보호

### C. ADR 0085 본문에 흡수 (거부)

T-CC Phase 2 의제를 ADR 0085(chart-curator 영입)에 add-only § 추가로 흡수.

**거부 사유**: 
- ADR 0085는 메타·하네스 영입 ADR — Phase별 작업 의제 분리 의무 (ADR README §9 정합)
- `feedback_spec_plan_scope` 정합 — 신규 트랙·새 모듈·다중 산출물은 spec/plan + ADR 발행 의무
- 본 ADR 0086은 chart-curator skill 본문 보강 + Phase 2-A·B 명세 LOCK 책임 단일 귀속

---

## 5. 변경 이력

| 일자 | 상태 | 사유 |
|------|------|------|
| 2026-05-19 | Proposed | T-CC Phase 2 spec 정합 신규 발행 — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 10건 LOCK + chart-curator 신입 보호. 사용자 명시 "신입한테 위 내용 잘 상기시켜서 진행해" 정합. spec 본문: `_workspace/specs/2026-05-19-t-cc-phase2-auto-backfill-gap-detection-spec.md`. (architect, opus 4.7 1M) |
| 2026-05-19 | Proposed → Accepted | Phase 2-A 햇징 룰 10건 모두 구현 + 27 단위 테스트 ALL GREEN (PR #525) · `CandleGapDetectorPort/Adapter` + `CandleBackfillWorker` + `CandleBackfillAdminController` + Flyway V39 `candle.backfill_batch_id` add-only + Phase 1 `LastCandleAtMonitor.checkAllCodes()` 1 line edit 통합 · 한국석유 dogfooding cycle 검증 path 확보 · v3.6.17 release 포함. (architect, opus 4.7 1M) |
| 2026-05-19 | Accepted (amend) | **햇징 룰 §11·§12·§13 추가 (10 → 13 룰 확장)** · §11 KIS raw 사전 ASC 정규화 (한국석유 PR #535 evidence — KIS DESC 응답 + sanity check reject 99건) · §12 Active gap detection 4축 교차 검증 (사용자 명시 정합 룰 · market_calendar + 거래정지 + 정리매매 + 상장일 권위 source · chart-curator 신입 잘못된 "5+ 영업일 임의 임계치" 영구 차단) · §13 LWC chart instance state lift (`useRef` → `useState` LOCK · 한국석유 PR #535 결함 #1 React 18 StrictMode dispose race) · chart-curator SKILL.md 매트릭스 13 룰로 갱신 · 운영 코드 0 touch (ADR + skill 본문만). (architect, opus 4.7 1M) |
| 2026-05-19 | Accepted (amend) | **햇징 룰 §14 추가 (13 → 14 룰 확장) — KIS 호출 한도 cover 의무** · §14 KIS 100건 한도 cover 의무 (모든 KIS 캔들/체결/잔고 endpoint 호출 시 cursor 페이지네이션 algorithm + MAX_PAGES guard + distinctBy boundary dedup + 응답 row 0 break + ASC 정규화 페이지 합산 후 마지막 단계) + helper class 분리 의무(예: `KisCursorPaginator`) + 회귀 test 1건 의무 + §함정 가드 cross-ref 매트릭스 (caller KDoc inline 인용) · evidence: PR 2cbd4e8(2026-05-04 cursor 페이지네이션 검증) → PR 915699b(2026-05-11 `RealKisRestAdapter` → `KisRestCandleAdapter` 재작성 시 페이지네이션 algorithm regression — 1m만 페이징 + 일/주/월/년봉 단일 호출) → PR #587(2026-05-19 chart-curator 진단 보고서 + B+E fix 사이클) · 사용자 비판 trigger: 한국석유 빈 DB 재발(ASC 정규화 §11만으로 부족 → 페이지네이션 명시 룰 부재 → algorithm regression 누락) · chart-curator SKILL.md 매트릭스 14 룰로 갱신 + §함정 #8 amend (KIS 100건 한도 추가) + §함정 #10 신설 (fix 후 regression 함정) · 운영 코드 0 touch (ADR + skill 본문만 + master·README·CHANGELOG 정합). 구현 의제 별도 사이클 위임 (chart-curator·market-data Phase — `KisCursorPaginator` helper class + 회귀 test + 5 parse 함수 합류). (architect, opus 4.7 1M) |
