# T-CC Phase 2 spec — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 10건

- 작성일: 2026-05-19 (KST)
- 작성자: architect (opus 4.7 1M)
- 발행 단계: spec (코드 변경 0 LOCK · 분석+spec 사이클)
- ADR: **0086 Proposed** (본 spec 정합 신규 발행)
- 트랙: **T-CC Phase 2** (chart-curator 단일 owner — ADR 0085)
- 단일 진실원: `docs/architecture/master-architecture.md` §10 (ADR 0030 SoT)
- 관련 spec/PR:
  - 선행: `_workspace/tasks/T-20260519-*` T-CC Phase 1(`LastCandleAtMonitor` + `LastCandleAtBootstrapRunner` + `LastCandleAtAdminController`) PR #514 머지 evidence
  - ADR 0085(chart-curator 영입 — PR #511 머지 v3.6.16 baseline)
- release 후보: **v3.6.18 patch** (T-CC Phase 2 + ADR 0084 P1-3 묶음 권장 — `feedback_release_policy_patch_first` 정합 + `feedback_dev_priority_qa_batch` 정합)
- 사용자 메시지(최고 관리자 2026-05-19 KST) baseline: **"신입 에이전트야 이거 할때 데이터가 기준으로 문제가 있는걸 어떻게 햇징할지 잘 고민하고 처리해야해. 아키텍처야 신입한테 위 내용 잘 상기시켜서 진행해"** — chart-curator 신입 보호용 햇징 룰 매트릭스 사전 LOCK 의무

---

## §1. 결함 진단 — T-CC Phase 1 워커가 stale 검출만 하고 backfill 자동화는 미흡

### 1-1. Phase 1 마감 evidence (PR #514)

`LastCandleAtMonitor` 구현 완료 — daily 18:00 KST cron `monitor()` + admin endpoint `POST /admin/monitor/last-candle-at`. 흐름:

1. watchlist 전체 조회
2. 거래정지·정리매매 skip
3. 종목별 (ONE_DAY · ONE_MIN) 마지막 ts 조회 → stale 검출 (`STALE_THRESHOLD_DAILY_BUSINESS_DAYS=5L` · `STALE_THRESHOLD_MINUTE_BUSINESS_DAYS=1L`)
4. stale 발견 시 `coldStartExecutor.submit(code)` — **비동기 backfill 트리거**
5. Slack alert + audit_trail row 1건

### 1-2. Phase 1 미흡 영역 (Phase 2 진입 사유)

| 영역 | Phase 1 상태 | Phase 2 보강 |
|------|--------------|---------------|
| **갭 검출 알고리즘** | `findMaxTsByCodeAndInterval()` 기반 "마지막 ts vs now" 단순 비교만 — **중간 갭 검출 불가** (예: 5거래일 중 3개만 적재돼도 last_ts는 최신이라 stale 미검출) | 종목·timeframe별 영업일 expected sequence 생성 + DB actual sequence diff → 갭 list 반환 |
| **자동 backfill 트리거** | stale 검출 시 `ColdStartExecutor.submit()` 호출 — **전체 재수집** (5종 interval 일괄) | 검출된 갭만 정밀 backfill — KIS 호출 cost 최적화 + 기존 데이터 보존 |
| **재시도 정책** | `ColdStartExecutor` 단발 호출 — 실패 시 재시도 0건 | 3회 backoff + KillSwitch 트리거 (ADR 0043 정합) |
| **사용자 보고** | `LastCandleAtMonitor` Slack alert만 — 매매일지 timeline 미연동 | Slack outbox + 매매일지 timeline + audit_trail backfill_batch_id 추적 |
| **데이터 햇징 룰** | dry-run·rollback·idempotency·sanity check 0건 — **신입 chart-curator의 잘못된 데이터 적재 risk** | 10 햇징 룰 매트릭스 사전 LOCK (§5 본문) |

### 1-3. fitness gap (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합)

- "자동매매에 필요한가?" — ✅ 일봉 기반 long-period 지표(EMA 200·SMA 50)는 갭 누락 시 시그널 왜곡 → 자본 손실 risk
- "v1/v2 잔재 아닌가?" — ❌ Phase 2는 신규 책임(chart-curator 영입 정합 트랙)
- "신규 결함 검증 필요" — ✅ 사용자 dogfooding evidence(한국석유 일봉 5개월 stale)는 단발 fix(Phase 1) + 재발 방지(Phase 2 자동화) 두 단계 필요

### 1-4. 사용자 의제(2026-05-19 KST) baseline

> "신입 에이전트야 이거 할때 데이터가 기준으로 문제가 있는걸 어떻게 햇징할지 잘 고민하고 처리해야해."

**번역**: chart-curator는 ADR 0085 영입 신입 → 자동 backfill 워커가 잘못된 데이터를 채울 risk가 크다 → architect가 사전에 **데이터 결함 햇징 룰 매트릭스**를 명시하여 chart-curator skill에 보강할 의무.

---

## §2. 현행 코드 매트릭스 (정독 완료 baseline)

### 2-1. `LastCandleAtMonitor` 현행 동작 (Phase 1 — `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/monitoring/LastCandleAtMonitor.kt`)

- `@Scheduled(cron = "0 0 18 * * *", zone = "Asia/Seoul")` daily 18:00 KST
- 거래정지·정리매매 skip 정책 LOCK(`KIS_COLD_START_SKIPPED` 정합)
- 검출 메서드: `detectStale(code, interval, nowKst, threshold)` — `findMaxTsByCodeAndInterval` + `countBusinessDaysBetween` 주말 가드(공휴일 미반영 — Phase 2 보강 의제)
- `lastTs == null` → `staleBusinessDays = Long.MAX_VALUE` (row 0건 표기 — false negative 가드)
- audit_trail `action = "CANDLE_LAST_AT_MONITOR"` row 1건
- Slack alert — stale 발견 시에만 발화 (정상 시 spam 회피 LOCK)

### 2-2. `CandleBootstrapRunner` 기존 동작 (`consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/bootstrap/CandleBootstrapRunner.kt`)

- 부팅 시 watchlist 1m·5m count 검사 — `count1m >= 700 && count5m == 0` 시 `AggregateBackfillRunner.backfill(code)` 자동 호출 (PR #303 옵션 c)
- T-CC Phase 2와의 관계: 부팅 시 한 번만 발화. 운영 중 갭은 감지 0 → Phase 2 `CandleGapDetector`가 보완.

### 2-3. `ColdStartExecutor.submit()` 단방향 트리거 (`consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ingestion/ColdStartExecutor.kt`)

- `KIS_COLD_START_INTERVALS` 5종 fetch(1m·1d·1wk·1mo·1y) — 일괄 재수집
- `aggregateBackfillRunner.backfill(code)` 후속 호출
- audit action: `KIS_COLD_START_DONE` / `KIS_COLD_START_PARTIAL_FAIL` / `KIS_COLD_START_FAILED` / `KIS_COLD_START_SKIPPED`
- thread pool size=2 (`cold-start-N`)
- **갭 검출 부재 영역**: stale 검출 후 호출되지만 "어떤 ts가 누락됐는지" 정보 0 — Phase 2 `CandleGapDetector.detectGaps(code, interval, from, to): List<MissingTs>`가 호출 전에 발화 필요

### 2-4. 갭 검출 부재 영역 — Phase 2 핵심 책임

- 현행: `findMaxTsByCodeAndInterval(code, interval): Instant?` — 마지막 ts만
- Phase 2 신설: `findAllTsByCodeAndIntervalBetween(code, interval, from, to): List<Instant>` — 범위 내 모든 ts 조회
- Phase 2 신설: `CandleGapDetector.detectGaps()` — 영업일 캘린더 기반 expected sequence와 actual diff

### 2-5. 영업일 캘린더(ADR 0013) — `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/session/BusinessDayCalendar.kt`

```kotlin
interface BusinessDayCalendar {
    fun isBusinessDay(date: LocalDate): Boolean
    fun seedRange(from: LocalDate, to: LocalDate): List<MarketCalendarEntry>
}
```

- 1차 소스: market_calendar DB + 정적 시드
- 2차 소스: KIS chk-holiday(CTCA0903R) cron 동기화
- Phase 2 활용: expected sequence 생성 시 `isBusinessDay()` 의무 호출 (주말·공휴일 제외)

---

## §3. KIS API 매트릭스 (KIS-first raw fetch 의무 — 헌법 §3)

본 spec은 KIS endpoint 신규 호출 0건 — Phase 1 + ADR 0085 §2-4 baseline 100% 재사용. raw URL 인용 의무는 chart-curator 코드 발행 시 inline KDoc으로 본문 의무 부담.

### 3-1. 본 spec 사용 endpoint (모두 Phase 1 baseline reuse)

| endpoint | tr_id | timeframe | Phase 2 사용 |
|---------|-------|----------|--------------|
| `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | FHKST03010100 | 일/주/월/년 (`FID_PERIOD_DIV_CODE` 분기) | 갭 검출된 일봉 backfill |
| `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | FHKST03010200 | 분봉 (1·3·5·10·30분 — `FID_INPUT_HOUR_1` 분기) | 갭 검출된 1분봉 backfill |
| `/uapi/domestic-stock/v1/quotations/inquire-time-conclusion` | tick | 체결 단위 | 비범위 (Phase 2 미사용 — Phase 5 LWC 확장 시 검토) |

**raw 출처** (헌법 §3 5단계 절차 정합):
- KIS 공식 GitHub: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/`
- MCP: `mcp__kis-code-assistant__search` 호출 검증 의무 (chart-curator 코드 발행 시점)
- 매 코드 발행 시 inline KDoc 주석에 raw URL 명시 (Phase 1 baseline 패턴 1:1)

### 3-2. KIS 페이지네이션 함정 가드 (chart-curator skill §함정 가드 4번 정합)

- 일봉 100건 한도 — 5개월 stale = 약 100 거래일 → 1.5+ 페이지 요청 필요
- 분봉 30건 한도 — 30 거래일 페이지네이션 (`FHKST03010230`)
- backoff + 토큰 만료 처리 — ADR 0037 Rate Limit(20 req/s) 정합

### 3-3. KIS endpoint 호출 비용 (ADR 0037 Rate Limit 영향)

- Phase 2 `CandleBackfillWorker`는 **갭만 정밀 backfill** → 전체 재수집(`ColdStartExecutor`) 대비 호출 수 감소
- 갭 1건 = KIS REST 1회 호출 (페이지 1건 또는 부분 페이지)
- 30 거래일 윈도우 → 최대 30 갭 = 30 호출/종목 → watchlist 30종목 × 30 = 900 호출 / cycle
- 18:00 KST cron 1회/일 → 토큰 버킷 분산 충분

---

## §4. 신규 워커 매트릭스 (chart-curator 영역)

### 4-1. `CandleGapDetector` (신규 — domain port + consumer 어댑터)

**위치**: 
- domain port: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/port/CandleGapDetectorPort.kt` (인터페이스만)
- consumer 어댑터: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/aggregation/CandleGapDetectorAdapter.kt` (구현)

**책임**:
- 종목·timeframe별 영업일 캘린더 기반 expected sequence 생성
- DB actual sequence 조회 (`CandleRepository.findAllTsByCodeAndIntervalBetween()` 신규 메서드 add-only)
- diff 산출 → 갭 list 반환

**시그니처 (예시 — chart-curator 자율 결정 가능)**:

```kotlin
interface CandleGapDetectorPort {
    /**
     * 종목·interval별 갭 검출.
     * 윈도우: 최근 30 거래일 (KIS 호출 cost 보호 — ADR 0037 Rate Limit 정합)
     * @return 누락된 ts list (영업일 expected sequence diff)
     */
    fun detectGaps(code: String, interval: CandleInterval, windowDays: Int = 30): List<Instant>
}
```

### 4-2. `CandleBackfillWorker` (신규 — consumer)

**위치**: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/aggregation/CandleBackfillWorker.kt`

**책임**:
- `CandleGapDetector`로 갭 list 획득
- 갭만 정밀 backfill (KIS REST 부분 호출)
- 재시도 3회 backoff + KillSwitch 트리거 (ADR 0043 정합)
- audit_trail row 발행 (시작·성공·실패·skip·rollback 5 path)

**진입점 매트릭스**:

| 진입점 | trigger | 비고 |
|--------|---------|------|
| `LastCandleAtMonitor.checkAllCodes()` 내부 | stale 검출 시 — 현행 `coldStartExecutor.submit(code)` 대체 | Phase 1 코드 minimal touch (1 line edit) |
| `CandleBackfillAdminController.trigger()` | admin endpoint `POST /admin/candle-curator/backfill` | dry-run 모드 토글 (`?dry-run=true&code=...`) |
| Cron (선택) | `@Scheduled` daily 17:00 KST | LastCandleAtMonitor 18:00 KST와 분리 (선행 운영) — chart-curator 자율 결정 |

### 4-3. `CandleBackfillAuditRecorder` (신규 — consumer, 옵션 — chart-curator 자율)

**위치**: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/aggregation/CandleBackfillAuditRecorder.kt`

**책임**:
- audit_trail row 발행 (5 path) — `CANDLE_BACKFILL_START` · `CANDLE_BACKFILL_SUCCESS` · `CANDLE_BACKFILL_FAIL` · `CANDLE_BACKFILL_SKIPPED` · `CANDLE_BACKFILL_ROLLBACK`
- Slack outbox 발행 (failure만 — spam 회피)
- backfill_batch_id 추적 (`audit_trail.detailJson.backfillBatchId`)

**참고**: chart-curator 자율 결정 — `CandleBackfillWorker` 내부에 inline 구현해도 무방(분리는 단위 테스트 편의 목적).

### 4-4. `CandleBackfillAdminController` (신규 — consumer)

**위치**: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/aggregation/CandleBackfillAdminController.kt`

**endpoint**:
```
POST /admin/candle-curator/backfill?code={code}&interval={1d|1m}&dry-run={true|false}
```

**파라미터**:
- `code` (필수 6자리 종목코드)
- `interval` (필수 — `1d` / `1m` — Phase 2 시작 시 2종만 cover, 5m·15m·30m·90m·1h 합산은 `AggregateBackfillRunner` 기존 위임)
- `dry-run` (옵션 — default false)

**응답**:
```json
{
  "code": "004090",
  "interval": "1d",
  "dryRun": true,
  "gapCount": 100,
  "gapsSample": ["2025-12-25T00:00:00Z", "..."],
  "backfillBatchId": "candle-backfill:004090:1d:20260519T1009-uuid"
}
```

---

## §5. ★ 데이터 햇징 룰 매트릭스 (CRITICAL — 신입 chart-curator 보호)

### 5-1. 이중 검증 (Double Verification)

**룰**: KIS raw fetch 응답을 적재 전 sanity check.

| 검증 항목 | 룰 | 위반 시 |
|----------|-----|---------|
| 가격 range | `0 < open · high · low · close ≤ 1_000_000_000` (KOSPI 상한가 단위 보호) | 적재 0 + warn log + audit |
| 거래량 비음수 | `volume >= 0` | 적재 0 + warn log + audit |
| time monotonic | 응답 list 내 ts 오름차순 정렬 (LWC v4 `setData()` 요구사항 정합 — chart-curator skill §함정 가드 2번) | 적재 0 + warn log + audit |
| OHLC 정합성 | `high >= max(open, close)` ∧ `low <= min(open, close)` ∧ `low <= high` | 적재 0 + warn log + audit |

**구현 위치 (권장)**: `CandleBackfillWorker.sanityCheck(candles: List<Candle>): List<Candle>` — invalid candle 필터링 후 valid만 반환.

### 5-2. Dry-run 모드

**룰**: `magicjar.chart-curator.backfill.dry-run=true` toggle 시 KIS fetch만 수행 + DB 적재 0 + log·response 출력.

**구현 path**:
- Spring profile property: `application.yml`에 default `false`
- admin endpoint 파라미터: `?dry-run=true` 우선 적용 (property override)
- `CandleBackfillWorker.backfill(code, interval, dryRun: Boolean = false)`

**용도**: 운영자 사전 검증 — "이 종목 일봉 갭 100개 정말 backfill 진행해도 되나?" 확인 path.

### 5-3. Rollback 안전망

**룰**: backfill 시작 직전 `audit_trail` row 발행 (`CANDLE_BACKFILL_START`) + Git-like 변경 ID로 batch 단위 추적 + 결함 검출 시 `DELETE FROM candle WHERE backfill_batch_id=?` 가능.

**구현 매트릭스**:

| 항목 | 룰 |
|------|-----|
| `backfill_batch_id` 생성 | `"candle-backfill:${code}:${interval.code}:${epochMs}-${UUID}"` 형식 |
| 적재 시 column 옵션 | Flyway V39 micro add-only — `candle.backfill_batch_id VARCHAR(96) NULL` (인덱스 0 — 운영 query 영향 0) |
| audit_trail row | `action=CANDLE_BACKFILL_START` + `detailJson.backfillBatchId=...` (시작) → `CANDLE_BACKFILL_SUCCESS` (완료) |
| rollback path | admin endpoint `DELETE /admin/candle-curator/backfill/{backfillBatchId}` — chart-curator 자율 결정 (1차 MVP는 SQL 수동 가능) |

**비범위**: 자동 rollback은 1차 MVP 0건 — 운영자 수동 path만.

### 5-4. Idempotency LOCK

**룰**: 동일 (code · timeframe · ts) row UPSERT `ON CONFLICT DO NOTHING` (기존 데이터 보존 우선 · 결함 없는 historic data 덮어쓰기 금지).

**구현**: `CandleRepository.saveAll(candles)` 기존 동작 100% 재사용 — UQ `candle.code+interval+ts` (ADR 0019 정합) + `ON CONFLICT DO NOTHING`이 기본 동작.

**위반 risk**: 만약 UPDATE로 변경 시 — 기존 정상 historic candle을 KIS retry 응답으로 덮어써서 회귀 risk.

### 5-5. Source-of-truth 룰

**룰**: KIS raw 응답이 권위 + 합성·계산·가공된 데이터 적재 금지.

**구현 매트릭스**:

| 데이터 종류 | 적재 위치 | 비고 |
|------------|----------|------|
| KIS raw 1m·1d·1wk·1mo·1y | `candle` 테이블 직접 | 권위 출처 |
| 5m·15m·30m·90m·1h 합산 가공 | `candle` 테이블 + audit `aggregateInserted` 필드 (ADR 0062 정합) | `AggregateBackfillRunner` 위임 — Phase 2 직접 touch 0 |
| Phase 2 신규 합성 | **0건 LOCK** | 가공 데이터 적재 금지 룰 LOCK |

**위반 시 risk**: 백테스트 vs 실거래 시그널 분기 → 자본 손실.

### 5-6. 갭 검출 false positive 가드

**룰**: 거래정지·정리매매·휴장일·시간외 종목은 갭 검출 대상 제외.

**구현 매트릭스**:

| 가드 | 룰 |
|------|-----|
| 거래정지·정리매매 | `LastCandleAtMonitor.checkAllCodes()` 기존 `instrumentJpaRepository.findById(code)` skip 정책 100% 재사용 (`KIS_COLD_START_SKIPPED` 패턴) |
| 휴장일 | `BusinessDayCalendar.isBusinessDay()` 의무 호출 — 휴장일 ts는 expected sequence에서 제외 |
| 시간외(NXT) | ADR 0063 정합 — `fid_cond_mrkt_div_code` 분기 (KOSPI=UN / KOSDAQ=J) — chart-curator 자율 결정 (1차 MVP는 시간외 분봉 제외 가능) |
| 신규 종목 | 상장일(`instrument.listedAt`) 이전 ts는 expected sequence 제외 — chart-curator 자율 결정 |

### 5-7. 갭 검출 false negative 가드

**룰**: `last_candle_at` null 시 max stale로 처리 (`Long.MAX_VALUE` — Phase 1 패턴) + watchlist 신규 진입 종목 즉시 cold-start.

**구현**: Phase 1 `LastCandleAtMonitor.detectStale()` 패턴 100% 재사용.

| 케이스 | 처리 |
|--------|------|
| `findMaxTsByCodeAndInterval()` returns null | `StaleEntry(code, interval, null, Long.MAX_VALUE)` → cold-start submit |
| watchlist 신규 종목 (DB row 0건) | `WatchlistChangedKafkaListener.onAdd()` 기존 동작 — `ColdStartExecutor.submit()` 즉시 호출 (Phase 2 미터치) |

### 5-8. 재시도 backoff + KillSwitch

**룰**: 3회 backoff 후 KillSwitch on (ADR 0043 정합) + Slack P0 alert.

**구현 매트릭스**:

| 시도 | 동작 |
|------|------|
| 1회 | 즉시 호출 |
| 2회 (backoff 1) | 1s 대기 후 재시도 |
| 3회 (backoff 2) | 5s 대기 후 재시도 |
| 3회 실패 | KillSwitch on (ADR 0043) + Slack P0 alert + `CANDLE_BACKFILL_FAIL` audit |

**KillSwitch 시점 분기**:
- **전체 backfill failure** (모든 갭 3회 실패) → KillSwitch on (자동매매 중지 — 자본 보호 우선)
- **부분 backfill failure** (일부 갭만 실패) → KillSwitch off + warn log + Slack notice (운영자 수동 확인 권고)

**chart-curator 자율 결정**: KillSwitch 발동 임계치(전체 vs 부분 비율)는 chart-curator가 운영 dogfooding 사이클에서 fine-tune 가능.

### 5-9. audit_trail 의무

**룰**: 모든 backfill 시도 발행 — 시작·성공·실패·skip·rollback 5 path 모두 row 1건.

**audit action 매트릭스**:

| action | 발행 시점 | detailJson |
|--------|-----------|-----------|
| `CANDLE_BACKFILL_START` | backfill 시작 직전 | `{"code":...,"interval":...,"backfillBatchId":...,"gapCount":...,"dryRun":...}` |
| `CANDLE_BACKFILL_SUCCESS` | 완료 (모든 갭 성공) | `{"code":...,"backfillBatchId":...,"insertedCount":...,"durationMs":...}` |
| `CANDLE_BACKFILL_FAIL` | 3회 retry 실패 | `{"code":...,"backfillBatchId":...,"failedGaps":[...],"err":...}` |
| `CANDLE_BACKFILL_SKIPPED` | 거래정지·정리매매·휴장 | `{"code":...,"reason":"TRADING_HALT|HOLIDAY|..."}` |
| `CANDLE_BACKFILL_ROLLBACK` | 운영자 수동 rollback admin endpoint | `{"backfillBatchId":...,"deletedCount":...,"reason":...}` |

**구현 위치 (권장)**: `CandleBackfillAuditRecorder` 또는 `CandleBackfillWorker` inline.

### 5-10. 운영자 우회 path

**룰**: admin endpoint dry-run 가능 — 수동 검증 path 의무.

**구현**: §4-4 `CandleBackfillAdminController` 본문.

**용도 매트릭스**:

| 케이스 | 운영자 동작 |
|--------|-----------|
| 갭 검출 의심 | `POST /admin/candle-curator/backfill?code=004090&interval=1d&dry-run=true` → 응답 확인 → 실 backfill `?dry-run=false` |
| 갭 검출 false positive 의심 | dry-run으로 갭 list 출력만 → 운영자가 직접 KIS 응답 비교 |
| backfill 후 회귀 검출 | admin rollback endpoint(MVP는 SQL 수동) — `backfill_batch_id` 기반 DELETE |

---

## §6. 갭 검출 알고리즘 LOCK

### 6-1. expected sequence 생성

```
nowKst = LocalDate.now(KST)
windowFrom = nowKst - 30 거래일 (BusinessDayCalendar로 backward 계산)
expectedDates = (windowFrom..nowKst).filter { calendar.isBusinessDay(it) }

# 1d: expected = expectedDates.map { it.atStartOfDay(KST).toInstant() }
# 1m: expected = expectedDates.flatMap { date -> 0..(720-1).map { minute -> date.atTime(08, 00) + minute.minutes } } // NXT cover 08:00~20:00 KST
```

**비고**: 분봉 갭 검출은 1m 720 bar/일 × 30 거래일 = 21,600 ts/종목. in-memory diff 가능 (chart-curator skill §함정 가드 정합 — 30 거래일 윈도우 LOCK).

### 6-2. actual sequence 조회

`CandleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from: Instant, to: Instant): List<Instant>` 신규 메서드 add-only (현행 `findByCodeAndInterval()` 전체 fetch 대비 효율).

**chart-curator 자율 결정**: 신규 메서드 add 또는 기존 `findByCodeAndInterval()` filter 둘 다 가능 — 영역 owner 결정.

### 6-3. diff 산출

```kotlin
val gaps = expected.filterNot { it in actualSet }
```

**비고**: `actualSet`은 `HashSet<Instant>` (lookup O(1)). 30K ts × 30K diff = ~1초 in-memory (CPU bound 무시 가능).

### 6-4. 윈도우

- **최근 30 거래일** (`windowDays = 30`) LOCK
- 사유: KIS 호출 cost 보호 + chart-curator skill §캔들 데이터 SLA 매트릭스 정합 (`5m·15m·30m·90m·1h` aggregation 30 거래일 정합)
- 장기 historic backfill 의제는 운영자 admin endpoint `?windowDays=...` 옵션 후속 backlog (1차 MVP 0건)

---

## §7. ADR 신설 결정

### 7-1. ADR 0086 (Proposed) — T-CC Phase 2 자동 backfill + 데이터 햇징 룰 매트릭스

**상태**: Proposed (2026-05-19 architect 자율 결정 · 사용자 위임 "신입한테 위 내용 잘 상기시켜서 진행해")

**Context**: T-CC Phase 1 dogfooding evidence + 자동 backfill 데이터 결함 잠재 risk + 신입 chart-curator 보호

**Decision**: 10 햇징 룰 LOCK (§5-1 ~ §5-10) + `CandleGapDetector` + `CandleBackfillWorker` + `CandleBackfillAdminController` 신설 + Flyway V39 micro add-only (`candle.backfill_batch_id`)

**Consequences**: 
- chart-curator skill SoT § 보강 (데이터 햇징 룰 매트릭스 신설)
- audit_trail 5 path 발행 의무 LOCK
- KillSwitch 트리거 조건 추가 (ADR 0043 cross-ref)
- Phase 1 `LastCandleAtMonitor` 최소 touch (1 line edit — `coldStartExecutor.submit()` → `candleBackfillWorker.submit()`) — chart-curator 자율 결정

**Alternatives Considered**:
- A. **Cron-only manual** — 운영자가 admin endpoint로 수동 backfill만. **거부** 사유: 사용자 dogfooding evidence(한국석유 5개월 stale)는 자동화 부재로 발생.
- B. **Auto-backfill 채택 + 햇징 룰** ✅ — chart-curator 영입 정합 + 데이터 결함 risk를 사전 LOCK으로 보호.

**변경 이력**:
| 일자 | 상태 | 사유 |
|------|------|------|
| 2026-05-19 | Proposed | T-CC Phase 2 spec 정합 신규 발행 — chart-curator 신입 보호 햇징 룰 매트릭스 10건 LOCK |

---

## §8. sub-agent dispatch plan

### Phase 2-A: chart-curator — 워커 + admin endpoint 본체

| 산출물 | 위치 | LOC 추정 |
|--------|------|---------|
| `CandleGapDetectorPort` (port) | `domain/.../market/port/` | ~30 |
| `CandleGapDetectorAdapter` (구현) | `consumer/.../aggregation/` | ~120 |
| `CandleBackfillWorker` | `consumer/.../aggregation/` | ~250 (햇징 룰 10건 흡수) |
| `CandleBackfillAdminController` | `consumer/.../aggregation/` | ~80 |
| `CandleRepository.findAllTsByCodeAndIntervalBetween()` add-only | `domain/.../port/` + 어댑터 | ~40 |
| Flyway V39 micro | `api/.../resources/db/migration/` | ~10 |
| `LastCandleAtMonitor.checkAllCodes()` 1 line edit | `consumer/.../monitoring/` | ~5 (Phase 1 touch 최소화) |
| unit test (햇징 룰 10건 검증) | `consumer/src/test/` | ~300 |

**총 LOC 추정**: ~835 (~250 LOC ± 햇징 룰 검증 단위 테스트 의무)

**dispatch**: 단일 PR (chart-curator 단독 owner — ADR 0085 영역 정합)

### Phase 2-B: chart-curator — Slack outbox + audit_trail integration (옵션 분리)

- Phase 2-A 머지 후 dogfooding 사이클 진입
- Slack outbox 표준화 (`SlackWebhookSender` 100% 재사용)
- audit_trail 5 path 모두 evidence 검증 (DB row 직접 확인)

**dispatch**: Phase 2-A 머지 후 chart-curator 자율 결정 (단일 PR 또는 omit — Phase 2-A에 모두 포함하면 분리 불필요)

---

## §9. v3.x cut 시점 매트릭스

**권장**: v3.6.18 patch cut (T-CC Phase 2 + ADR 0084 P1-3 묶음)

**사유**:
- `feedback_release_policy_patch_first` 정합 — v3.7.0=W4 LOCK이므로 모든 W3 보강은 patch
- `feedback_dev_priority_qa_batch` 정합 — 단일 트랙 묶음 cut + QA 누적 후 일괄
- T-CC Phase 2(chart-curator) + ADR 0084 P1(backend-core/market-data) 영역 분리 — 병렬 dispatch 가능

**baseline**: v3.6.16 (2026-05-18 cut) → v3.6.17 (ADR 0084 P1-3) → **v3.6.18 (T-CC Phase 2 + ADR 0084 잔류 묶음)**

---

## §10. KIS endpoint 호출 0건 추가 LOCK

본 spec은 KIS endpoint 신규 호출 0건 — Phase 1 baseline 100% 재사용. raw URL inline KDoc 의무는 chart-curator 코드 발행 시점 (헌법 §3 5단계 절차).

**ADR 0037 Rate Limit 영향**: 0건 (호출 수 감소 — 갭만 정밀 backfill로 전체 재수집 대비 효율↑).

---

## §11. AI 호출 0 LOCK

- 룰 5 (CLAUDE.md 개발 룰북) + ADR 0023 정합
- 데이터 햇징 룰 sanity check은 결정론적 boolean — LLM 호출 0
- `grep -r "anthropic\|openai\|llm\|ai" consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/aggregation/` 의무 검증 (chart-curator 코드 발행 시)

---

## §12. backlog §79 Phase 2 update

`_workspace/backlog.md` §79 Phase 2 row 갱신:
- spec link: `_workspace/specs/2026-05-19-t-cc-phase2-auto-backfill-gap-detection-spec.md`
- ADR 0086 ref 추가
- 상태: ⏳ → 🚧 진행 중 (chart-curator dispatch 대기)

---

## §13. rollback (single revert · 자본 영향 0)

- 본 spec 코드 변경 0 LOCK
- chart-curator Phase 2-A PR 머지 후 결함 발견 시: `git revert <merge-commit>` 단일 revert
- Flyway V39 micro는 add-only column → revert 시 자동 fallback (column NULL이면 기존 동작 유지)
- 자본 영향 0 (데이터 정합 보강만 수행 — 주문 발행 0건)

---

## §14. fitness gap 종합 evidence

(`feedback_v3_skepticism_no_regression` 정합)

| 질문 | 답 | evidence |
|------|-----|----------|
| 자동매매에 필요한가? | ✅ | 일봉 갭 누락 → 백테스트 vs 실거래 시그널 분기 → 자본 손실 risk |
| v1/v2 잔재 아닌가? | ❌ | Phase 2는 ADR 0085 영입 신입 chart-curator 영역 신규 책임 |
| 신규 결함 검증? | ✅ | 한국석유 dogfooding 5개월 stale + Phase 1 단발 fix vs Phase 2 재발 방지 자동화 |
| MVP 적합? | ✅ | watchlist 30종목 × 30 갭 = 900 호출/cycle (KIS rate limit 20 req/s 내 분산 충분) |

---

## §15. KIS-first 정합 evidence (헌법 §3)

| 항목 | evidence |
|------|----------|
| KIS endpoint 자체 결정? | ❌ — Phase 1 baseline 100% 재사용 (`inquire-daily-itemchartprice` · `inquire-time-itemchartprice`) |
| raw URL inline KDoc? | chart-curator 코드 발행 시점 의무 (헌법 §3 5단계 절차) |
| MCP `mcp__kis-code-assistant__*` 검증? | chart-curator 코드 발행 시점 의무 |
| 합성·가공 데이터 적재 LOCK? | ✅ — §5-5 source-of-truth 룰 |

---

## §16. 인접 트랙 정합 검증 (T-CC Phase 1과 모순 0건)

| 항목 | Phase 1 동작 | Phase 2 영향 | 정합 |
|------|--------------|--------------|------|
| `LastCandleAtMonitor.detectStale()` | last_ts vs now 단순 비교 | `coldStartExecutor.submit()` 호출을 `candleBackfillWorker.submit()`로 1 line edit (chart-curator 자율 결정) | ✅ Phase 1 동작 100% 보존 — 단지 backfill 실행자 교체 |
| Phase 1 stale 임계치 | daily=5거래일·minute=1거래일 | Phase 2 LOCK 보존 | ✅ |
| Phase 1 audit `CANDLE_LAST_AT_MONITOR` | row 1건 | Phase 2 추가 audit 5 path | ✅ row 추가 (Phase 1 row 변경 0) |
| Phase 1 Slack alert | stale 발견 시에만 발화 | Phase 2는 backfill 실패 시 추가 alert | ✅ 발화 source 추가 |

**chart-curator skill §함정 가드** 정합:
- 1번 (time 타입 함정) — §5-1 OHLC 정합성 검증으로 보강
- 2번 (series 데이터 정렬) — §5-1 time monotonic 검증
- 3번 (multi-chart sync race) — Phase 2 비범위 (Phase 5 LWC 확장)
- 4번 (KIS 페이지네이션) — §3-2 명시
- 5번 (MTF 합산 timezone) — Phase 3 비범위
- 6번 (corp action retroactive) — Phase 4 비범위

---

## §17. master-architecture.md §10 갱신 의무 (ADR 0030 SoT)

본 spec 머지 시 `docs/architecture/master-architecture.md` 갱신:
- §10 헤더 카운트 — 0086 Proposed 추가
- §10 본 표 — 0085 row (chart-curator 영입) + 0086 row (T-CC Phase 2) add (현재 0085 row 누락 — 본 PR에서 동반 add)
- §10 후속 예정 표 — 0086 row 추가
- §14 변경 이력 — row 추가

---

## §18. dispatch 결정 종합 매트릭스

| 항목 | 결정 |
|------|------|
| 트랙 owner | chart-curator (ADR 0085 단일 owner) |
| Phase 2-A dispatch | 단일 PR (chart-curator 단독 영역) — backend-core·market-data·frontend 0 touch |
| Phase 2-B dispatch | Phase 2-A 머지 후 chart-curator 자율 결정 (omit 가능) |
| 병렬 가능 트랙 | ADR 0084 P1-3 (backend-core·market-data 영역) — modify-shared risk 0 |
| release cut | v3.6.18 patch (T-CC Phase 2 + ADR 0084 P1-3 묶음 권장) |
| QA 일괄 | `feedback_dev_priority_qa_batch` 정합 — Phase 2-A 머지 후 dogfooding 사이클 누적 후 일괄 |

---

## §19. 결정 요약

1. **T-CC Phase 2 진입** — chart-curator 단일 owner (ADR 0085 정합)
2. **신규 워커 3건** — `CandleGapDetector` + `CandleBackfillWorker` + `CandleBackfillAdminController`
3. **데이터 햇징 룰 10건 LOCK** — chart-curator skill SoT §추가 + ADR 0086 본문 LOCK + 신입 보호
4. **갭 검출 알고리즘 LOCK** — 영업일 캘린더(ADR 0013) 기반 + 30 거래일 윈도우
5. **ADR 0086 Proposed 신설** — backlog §79 Phase 2 ref + chart-curator dispatch prereq
6. **release v3.6.18 patch 권장** — ADR 0084 P1-3 묶음 (영역 분리 병렬 가능)
7. **KIS 호출 0 추가 + AI 호출 0 + mock 합성 0** — 3중 LOCK
8. **자본 영향 0** — 데이터 정합 보강만 수행

---

## §20. 변경 이력

| 일자 | 상태 | 사유 |
|------|------|------|
| 2026-05-19 | spec 발행 | T-CC Phase 2 자동 backfill + 갭 검출 + 데이터 햇징 룰 매트릭스 10건 LOCK + ADR 0086 Proposed + chart-curator dispatch prereq. 사용자 명시 "신입 에이전트야 이거 할때 데이터가 기준으로 문제가 있는걸 어떻게 햇징할지 잘 고민하고 처리해야해" 정합. (architect, opus 4.7 1M) |
