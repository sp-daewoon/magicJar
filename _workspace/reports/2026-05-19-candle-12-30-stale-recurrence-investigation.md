# 캔들 12-30 stale 재발 root cause 진단 (보수 페르소나)

**작성**: chart-curator (`bot+chart-curator@magicjar.local`) — 보수 페르소나 "안정빌드"
**발행일**: 2026-05-19 KST
**Task**: T-20260519-2311-candle-12-30-stale-recurrence-investigat
**브랜치**: `agent/chart-curator/candle-12-30-stale-recurrence-investigation`
**근거 권위**: ADR 0085 (chart-curator 영입) · ADR 0086 (T-CC Phase 2 햇징 룰 §11·§12·§13) · skill `magicjar-chart-curator` §10 + §함정 #2·#8
**사용자 결재 요청**: §4 처리 방안 매트릭스에서 선호 옵션 선택 → 후속 코드 fix 사이클 발사

---

## 0. 사용자 보고 의제 인용

> "추가 이슈 발견. 최초 캔들 가져오는데 12월 30일 캔들이 마지막임. 오늘 얘기했던 부분인데 아직 해소가 안된것으로 보여짐. 캔들전문가에게 확인요청 이유 명확하게 확인필요"

**의제 분해**:
1. 빈 DB 첫 cold-start → 캔들 적재. 최신이 2025-12-30. 오늘은 2026-05-19. 약 **5개월 갭**.
2. 이전 fix 매트릭스 (PR #535·#537·#540·#541·#558·#571) 머지 완료 상태에서 동일 결함 재발.
3. 사용자가 chart-curator에게 **명확한 root cause 확인** 의무 부여.

---

## 1. DB Evidence 매트릭스 (4 timeframe × 한국석유)

```
   code | interval | rows |  min_kst    |  max_kst   
--------+----------+------+-------------+-------------
 004090 | 1d       |  100 | 2025-08-04  | 2025-12-30
 004090 | 1mo      |  100 | 2017-09-29  | 2025-12-30
 004090 | 1wk      |  100 | 2024-01-29  | 2025-12-22
 004090 | 1y       |   30 | 1996-12-27  | 2025-12-30
```

- 적재 총 330건 (id=1~330, 단일 batch ASC 정렬 정상)
- `source='KIS'` 100%, `backfill_batch_id=NULL` 100% (Phase 1 cold-start path는 batch_id 추적 미적용 — §3 룰 적용 범위 밖)
- 1d max=2025-12-30, 1wk max=2025-12-22(주봉 마지막 거래주), 1mo max=2025-12-30, 1y max=2025-12-30 → **모두 동일 시점에 정확히 일치**
- `instrument(004090)`: KOSPI / type=STOCK / `listed_at=1977-06-25` / `delisted_at=NULL` / `active=t` / `trading_halt=f` / `liquidation=f` / `management_stock=f` / `market_alert='00'` → **정상 종목 (4축 모두 active 조건 충족)**
- 다른 종목 candle row: **0건** (instrument 마스터 3599건 중 한국석유만 적재. 첫 dogfooding 빈 DB cold-start 상태)

### 1-1. 시점 evidence (id ASC 적재 + ASC 정규화 정상 동작)

- id=1 → 2025-08-04, id=100 → 2025-12-30. 100건 ASC 정렬된 채 일괄 적재.
- 즉 **ASC 정규화(ADR 0086 §11)는 정상 동작**. PR #537·#540·#558·#571 fix는 잘 들어가 있음.

### 1-2. ADR 0086 §12 4축 active gap detection 별개 결함

- `market_calendar` 테이블에 **영업일 row가 0건** (holiday 31건만 존재. min=2026-01-01, max=2027-12-31)
- §12 4축 중 1축 `market_calendar.is_business_day=TRUE`가 항상 FALSE → **active gap detection 작동 불가**
- 본 진단 의제와 직접 관련된 root cause는 아니지만, **fix되어야 할 동반 결함** (§5 부수 의제로 분리)

---

## 2. consumer log evidence (KIS_COLD_START 흐름)

`docker logs magicjar-consumer` 추출 (2026-05-19 KST 22:57~22:57):

```
22:57:35.599 WatchlistChangedKafkaListener — userId=7468**** code=004090 action=ADDED
22:57:35.634 cold-start 트리거: code=004090
22:57:35.668 ColdStartExecutor — KIS cold-start 시작: code=004090
22:57:36.022 KisRestCandleAdapter — KIS 1m backfill 일자 완료: code=004090 date=20260519 pages=1 total=0
... (1m backfill 30 거래일 모두 total=0 반복)
22:57:39.806 KisRestCandleAdapter — KIS 1m backfill 전체 완료: code=004090 total=0
22:57:39.807 ColdStartExecutor — KIS cold-start 빈 응답: code=004090 interval=1m
22:57:40.113 AggregateBackfillRunner — 1m candle 없음 — 합산 skip: code=004090
22:57:40.135 ColdStartExecutor — KIS cold-start 완료: code=004090 action=KIS_COLD_START_PARTIAL_FAIL success=4 fail=1 aggregateInserted=0
```

`docker logs magicjar-api` (23:01:09 사용자 수동 갱신):

```
23:01:09.316 CandleRefreshService — 캔들 갱신 완료: code=004090 interval=1d fetched=100 inserted=0
23:01:09.443 CandleRefreshService — 캔들 갱신 완료: code=004090 interval=1wk fetched=100 inserted=0
23:01:09.805 CandleRefreshService — 캔들 갱신 완료: code=004090 interval=1mo fetched=100 inserted=0
23:01:09.890 CandleRefreshService — 캔들 갱신 완료: code=004090 interval=1y fetched=30 inserted=0
```

`audit_trail` row:
```
action                       | entity_id | detail_json                                              | occurred_at
-----------------------------+-----------+----------------------------------------------------------+----------------------
KIS_COLD_START_PARTIAL_FAIL  | 004090    | {"code":"004090","success":4,"fail":1,"aggregateInserted":0}| 2026-05-19 22:57:40.115
WATCHLIST_ADDED              | 004090    | {"code":"004090","userId":"74683223"}                    | 2026-05-19 22:57:35.457
```

**해석**:
- cold-start success=4 = 1d/1wk/1mo/1y 4 timeframe 모두 적재 성공 (consumer log에는 debug 레벨이라 fetched/inserted 수치 미노출)
- cold-start fail=1 = 1m 빈 응답 (한국석유 KIS API 1m 분봉이 빈 응답 — KIS 별도 결함 의심)
- 23:01 수동 갱신은 이미 적재된 100건과 동일 (UPSERT idempotency로 inserted=0)
- 즉 **22:57 cold-start로 적재된 4 timeframe 100건이 모두 2025-12-30 한도** → 후속 호출에서도 같은 100건 반환

---

## 3. KIS Adapter 코드 정독 evidence

### 3-1. `consumer/.../KisRestCandleAdapter.kt::fetchDailyCandles` (line 556~702)

```kotlin
val today = LocalDate.now(KST_ZONE).format(DATE_FMT)           // line 587: 2026-05-19
val startDate = LocalDate.now(KST_ZONE).minusYears(30).format(DATE_FMT)  // line 589: 1996-05-19

// line 616~640: 단일 webClient.get() 호출
.queryParam("fid_input_date_1", startDate)
.queryParam("fid_input_date_2", today)
.queryParam("fid_period_div_code", periodDivCode)
.queryParam("fid_org_adj_prc", "0")
// ... block()
return parseKisDailyResponse(body, code, interval)  // line 652: 단일 페이지 처리 + 종료
```

**핵심 결함**: `fetchDailyCandles`는 **단일 호출 1회로 종료**. KIS REST는 응답 최대 100건만 반환 → 응답에서 받은 oldestDate가 startDate보다 신선해도 **추가 페이지 호출이 없음**.

비교 — 같은 어댑터의 `fetchOneMinCandles` (line 163~547):
- line 231: `while (!dayDone && pageCount < maxPagesPerDay)` — 일자당 6 page 페이지네이션
- line 525: `MAX_PAGES_PER_DAY 도달 안전 종료`
- **1m는 페이지네이션 적용, 1d/1wk/1mo/1y는 미적용** ← 결함의 비대칭

### 3-2. `api/.../CandleRefreshService.kt::refreshDailyCandles` (line 168~262)

```kotlin
val today = LocalDate.now(KST_ZONE).format(DATE_FMT)              // line 177
val startDate = LocalDate.now(KST_ZONE).minusYears(30).format(DATE_FMT)  // line 179
// line 188~214 단일 webClient.get() 호출 1회로 종료
// line 228: output2 = body["output2"] as? List<*>
// line 229: parseKisDailyOutput2(output2, ...)
// line 231: upsertCandles(...) → 정상 종료
```

**동일 결함 — 페이지네이션 0회**. ASC 정규화(line 459 `result.sortedBy { it.epochSec }`)는 적용되어 있으나 페이지네이션이 없으므로 100건 한도 그대로.

### 3-3. KIS 공식 raw spec 검증 (헌법 §3 KIS-first MCP 인용)

`mcp__kis-code-assistant__read_source_code` 응답 (KIS open-trading-api/main/examples_llm/.../inquire_daily_itemchartprice.py):

```python
def inquire_daily_itemchartprice(...):
    """
    국내주식기간별시세(일/주/월/년) API입니다.
    실전계좌/모의계좌의 경우, 한 번의 호출에 최대 100건까지 확인 가능합니다.
    
    Args:
        fid_input_date_2 (str): [필수] 입력 날짜 1 (ex. 조회 종료일자 (최대 100개))
        ...
```

→ **KIS 공식 docstring 명시 "한 번의 호출에 최대 100건"**. 30년치(약 7500 거래일) 조회 시 단일 호출로는 절대 도달 불가. 사용자가 `fid_input_date_2`를 더 과거로 옮겨 재호출하는 페이지네이션 의무.

### 3-4. KIS 페이지네이션 알고리즘 — 과거 commit에서 이미 구현 후 누락

`git log` 추적:

| 시점 | commit | 영향 |
|------|--------|------|
| 2026-05-04 | `2cbd4e8` `feat(market-data): KIS REST 일봉 페이징 — 1년 full backfill (100봉 limit 우회)` | `RealKisRestAdapter`에 cursor 페이지네이션 (MAX_PAGES=6) 구현. **한국석유 004090 1년 full backfill 검증 완료** (커밋 본문 인용: "이전 100봉 → 페이징 후 243봉 (2025-05-07~2026-05-04)") |
| 2026-05-11 | `915699b` `feat(market-data): Yahoo Finance 전면 폐기 + KIS-only cold-start 1d/1m backfill` | `KisRestCandleAdapter` **전면 재작성**. 1d/1wk/1mo/1y 페이지네이션 algorithm **누락**. 1m만 페이지네이션 (다른 algorithm, MAX_PAGES_PER_DAY=6) 적용 |
| 2026-05-19 | 본 진단 시점 | 페이지네이션 부재 잔존. PR #535·#537·#540·#541·#558·#571은 ASC 정규화 + LWC dispose만 fix. 페이지네이션 fix는 빠짐 |

**즉 root cause는 다음 한 줄로 환원**:

> 2026-05-04 PR `2cbd4e8`에서 한 번 fix됐던 **KIS REST 일봉 100건 페이지네이션 algorithm**이 2026-05-11 PR `915699b` KIS-only 전환 시 `RealKisRestAdapter` → `KisRestCandleAdapter` 전면 재작성에서 **regression**. 그 후 chart-curator 영입(2026-05-19) 시점에 발견된 dogfooding 결함의 **ASC 정규화**만 fix하고 페이지네이션 결함은 **놓침**.

ASC 정규화 fix만으로 부족한 이유:
- KIS가 100건만 반환하므로 ASC 정렬 후에도 가장 최신 100건(또는 fid_input_date_2 부근 100건)만 남음
- 빈 DB → 한국석유의 경우 KIS가 어떤 기준으로 "최신 100건"을 반환했는지가 결정적. **2025-12-30 ~ 2025-08-04 구간**이 반환됨 — KIS가 이 구간을 "fid_input_date_2=2026-05-19 기준 최신 100건"으로 정의했다는 evidence
- 즉 KIS의 응답은 fid_input_date_2 기준 거꾸로 100건 — 한국석유는 fid_input_date_2=2026-05-19 시점에서 최신 거래일이 2025-12-30 또는 그 이전 KIS 데이터 캐시였을 가능성 (KIS 자체 데이터 stale 가능성)
- 그러나 어느 가설이든 **100건 한도가 도달했다는 사실 자체로 페이지네이션 의무**

---

## 4. instrument 마스터 검증 (§12 4축 active gap)

```
  code  |   name   | market | type  | listed_at  | delisted_at | active | trading_halt | liquidation | management_stock | market_alert | market_alert_warning
--------+----------+--------+-------+------------+-------------+--------+--------------+-------------+------------------+--------------+----------------------
 004090 | 한국석유 | KOSPI  | STOCK | 1977-06-25 |             | t      | f            | f           | f                | 00           | f
```

- **§12 4축 모두 active gap detection 조건 충족**:
  - (1) `instrument.listed_at=1977-06-25 ≤ 2026-05-19` ✓
  - (2) `trading_halt=f` ✓
  - (3) `liquidation=f` ✓
  - (4) `market_calendar.is_business_day=TRUE` — **market_calendar에 영업일 row 0건이라 평가 불가**. 별개 결함(§5 부수 의제).

비교군:
```
 000660 | SK하이닉스 | KOSPI  | 1996-12-26 |  | t | f
 005930 | 삼성전자   | KOSPI  | 1975-06-11 |  | t | f
 035420 | NAVER      | KOSPI  | 2008-11-28 |  | t | f
```
모두 한국석유와 동일 active. 즉 **한국석유 특이성 0** → 데이터 stale은 한국석유 종목 사정이 아니라 **KIS adapter 페이지네이션 부재의 일반 결함**. 다른 종목으로 cold-start해도 동일 100건 한도가 표면화될 것.

---

## 5. 개발자1 (보수적) 의견 — 캔들 12-30 stale 재발 root cause 진단

### 안정성 관점

- **기존 fix (PR #537·#540·#558·#571) 동작 영향**: 변경 없음. ASC 정규화는 그대로 정상 동작 중. 본 결함은 ASC 정규화 이전 단계(KIS API 호출 페이지네이션)의 문제이므로 **기존 fix 무효화 risk 0**.
- **리스크 포인트**:
  1. KIS rate limit (ADR 0037 초당 10건) — 페이지네이션 추가 시 호출 횟수 증가. 30년치 cover 시 종목당 75 page (7500 거래일 / 100) × 4 timeframe = 최대 300 calls. 단일 종목 cold-start만 30초 ↑. **운영자 우회 path 필요(§8 룰 §10 admin endpoint)**.
  2. KIS 응답 stale 종목 (한국석유 KIS 캐시가 2025-12-30 이후 멈춰있을 가능성). 페이지네이션해도 KIS가 더 신선한 데이터를 안 줄 risk. **fix 후 한국석유 적재 결과로 KIS측 결함 여부 확정 가능**.
  3. cold-start path가 audit_trail에 `backfill_batch_id` 미기록 (DB evidence: 모든 row `backfill_batch_id=NULL`). ADR 0086 §3 Rollback 안전망 미적용 — **운영 중 결함 검출 시 rollback 불가**.
- **롤백 가능성**: 페이지네이션 추가 fix는 idempotent (UPSERT ON CONFLICT DO NOTHING — §4 룰 §4). 회귀 risk 낮음. 정 안되면 commit revert만으로 즉시 원복.

### 코드 리뷰 (진단 결과)

1. 🔴 **[필수 수정] `consumer/.../KisRestCandleAdapter.kt::fetchDailyCandles` (line 556~702) — KIS 일봉 페이지네이션 부재 (root cause #1)**

   KIS 공식 "한 번의 호출에 최대 100건" 한도를 cursor-pagination으로 우회하는 로직이 **누락**. 2026-05-04 `RealKisRestAdapter`에 있었던 동일 algorithm을 복원 의무. 적용 범위: D/W/M/Y 4 PERIOD_DIV_CODE 모두 동일 endpoint(FHKST03010100) 단일 스키마 → 단일 알고리즘 단일 LOCK으로 cover (skill §함정 #8 정합).

   권장 algorithm (PR `2cbd4e8` 복원 + ADR 0086 §3·§9 정합):
   - cursor = `today`로 초기화
   - 응답 < KIS_PAGE_LIMIT(100) → 종료 (마지막 페이지)
   - oldestDate ≤ startDate → 종료 (요청 범위 도달)
   - cursor = oldestDate - 1day (또는 oldestDate - 1week / 1month / 1year for W/M/Y)
   - MAX_PAGES safety guard (예: 일봉 75, 주봉 16, 월봉 4, 연봉 1) — 30년치 보호
   - 페이지별 ASC 정규화 + `distinctBy { it.ts.epochSeconds }` (경계 중복 방어)
   - audit_trail `CANDLE_BACKFILL_START`/`SUCCESS` + `backfill_batch_id` (ADR 0086 §3·§9 햇징 룰 의무)

2. 🔴 **[필수 수정] `api/.../CandleRefreshService.kt::refreshDailyCandles` (line 168~262) — 동일 페이지네이션 부재 (root cause #2)**

   사용자 트리거 path (POST `/api/symbols/{code}/candles/refresh`)도 같은 결함. consumer adapter fix 후 동일 algorithm 이식 의무. 또는 `KisRestCandleAdapter`를 api에서도 재사용 가능하도록 port 인터페이스로 격상 검토.

3. 🟡 **[권장 수정] market_calendar 영업일 row 부재 (root cause 외 동반 결함)**

   `market_calendar` 31건 모두 holiday only. §12 4축 1축 평가 불가. KIS chk-holiday(CTCA0903R) 정기 sync 결함 또는 영업일 자동 seed runner 미구현 의심. 별도 task로 분리 권고:
   - 옵션 (a): 영업일 자동 seed runner — 부팅 시 KIS chk-holiday 응답 누락 영업일 backfill
   - 옵션 (b): MarketCalendarJpaRepository에 fallback (date 본 record 부재 → 평일 + holiday table miss = 영업일 추정)
   - 옵션 (c): KIS chk-holiday cron 동기화 활성화 검증 (이미 구현되어 있는지 audit_trail 조회)

4. 🟡 **[권장 수정] cold-start path가 ADR 0086 §3·§9 햇징 룰 미적용**

   `ColdStartExecutor`는 `backfill_batch_id` 미기록 + `CANDLE_BACKFILL_START/SUCCESS` audit 미발행 (대신 `KIS_COLD_START_*` 사용). ADR 0086 §3 Rollback 안전망 누락. T-CC Phase 2 본체 `CandleBackfillWorker`는 적용되었지만, 부팅 시 cold-start path는 적용 범위 밖. **chart-curator 영구 룰 적용 범위 확장 의제** — skill §룰 적용 의무 영역에 cold-start path 추가.

5. 🔵 **[참고] KIS 1m endpoint(FHKST03010230) 빈 응답 — 별개 의제로 추적 권고**

   한국석유 1m 30 거래일 모두 `total=0`. KIS 1m endpoint 자체 결함 또는 한국석유 1m 데이터 KIS 미제공 가능성. 본 진단 의제와 분리. 다른 종목으로 cold-start 시 1m도 빈 응답인지 dogfooding 의무.

6. 🔵 **[참고] KIS rate limit + 대량 backfill 운영자 우회 path**

   30년치 cover는 비용 높음. 사용자/운영자 의도가 "갭만 메우기"인지 "30년 full backfill"인지 분기 필요. ADR 0086 §10 admin endpoint(`POST /admin/candle-curator/backfill?code={}&interval={}`)에 `range` 파라미터 추가 권고 (예: `range=auto`(기본·갭만) / `range=1y` / `range=full`).

### 우려 사항

- **PR #535 한국석유 진단 보고서 당시 본 결함을 놓침**. ASC 정규화 결함을 일봉 stale의 단일 root cause로 환원하고 종료. 그러나 실제로는 **2 가지 결함이 중첩** (ASC 정규화 + 페이지네이션). PR #537~#571 사이클 동안 한국석유 빈 DB cold-start 재현 검증 evidence 부재 → 두 번째 결함 노출 지연. **chart-curator 영구 룰**: 이후 모든 캔들 결함 dogfooding은 "빈 DB 첫 cold-start 재현 + DB row 4 timeframe min/max evidence" 의무.
- **chart-curator 신입의 "5+ 영업일 임의 임계치" 임계치 영구 차단(룰 §12)**과 일관되게, **"단일 호출로 KIS 100건 다 받아온다는 가정"도 영구 차단**해야 함. skill §함정 #8 amend 권고: "KIS 일봉 응답은 항상 최대 100건이므로 단일 호출 종료 금지 — cursor 페이지네이션 의무."
- **운영 헌법 §3 KIS-first raw 출처 의무**가 algorithm 차원(페이지네이션)까지 cover하지 못하고 있음. raw `inquire_daily_itemchartprice.py`에는 페이지네이션 예시 코드가 없어 magicJar 구현자가 단일 호출로 충분하다고 오인 가능. **KIS-first 의무는 raw docstring "최대 100건" 같은 한도 명시 정독까지 포함**으로 amend 권고.

### 대안 제시 — 검증된 패턴 기반

대안 1: **2026-05-04 PR `2cbd4e8` algorithm 복원 (검증된 패턴)**

`RealKisRestAdapter`에 있었던 cursor-pagination + MAX_PAGES safety guard + distinctBy boundary dedup. 이미 한국석유 004090 1년 full backfill 검증 완료된 패턴 (commit 본문 인용 evidence). 신규 algorithm 도입 risk 0. 검증된 패턴 우선 적용 (보수적 페르소나 핵심 가치 §2).

대안 2: **`KisRestCandleAdapter`만 fix + `CandleRefreshService`는 그것을 호출**

api·consumer 양쪽에 같은 algorithm 중복하지 않도록 consumer adapter를 단일 source of truth로 두고, api `CandleRefreshService`는 그것을 호출하는 구조로 리팩토링. 단 모듈 경계 (api → consumer 호출 금지) 검토 필요 — port 인터페이스로 격상 또는 별도 shared 모듈.

대안 3: **chart-curator skill §룰 §11 amend — 모든 KIS 일봉 호출은 페이지네이션 의무**

ADR 0086 §11 (ASC 정규화)의 자매 룰로 **§14 KIS 호출 한도 cover 의무** 신설 권고. 본문: "KIS REST endpoint별 단일 호출 한도(일봉 100건 등)는 cursor 페이지네이션으로 우회 의무. 단일 호출로 종료 금지. evidence: 본 진단 보고서." 신입 chart-curator(및 향후 market-data·api 모듈 변경자)의 동일 결함 영구 차단.

---

## 6. 처리 방안 매트릭스 (사용자 결재 의제)

| 옵션 | 범위 | 예상 PR 수 | 예상 cycle | 리스크 | 권장도 |
|------|-----|----|---------|--------|--------|
| **A — 최소 fix** | consumer `KisRestCandleAdapter.fetchDailyCandles` 단일 함수에 페이지네이션 algorithm 복원 (대안 1) + 회귀 test 1건 | 1 | 1 cycle | 낮음 (검증된 패턴) | ★★★★★ |
| **B — A + api 동조** | A + `CandleRefreshService.refreshDailyCandles`에도 동일 algorithm 이식 | 2 | 1~2 cycle | 낮음 | ★★★★★ |
| **C — A + market_calendar 동반 fix** | A + market_calendar 영업일 row seed runner + KIS chk-holiday 정기 sync 검증 | 2 | 2 cycle | 중간 (별개 의제 묶음 risk) | ★★★ |
| **D — full** | A + B + market_calendar fix + ADR 0086 §14 룰 신설 + cold-start path가 §3·§9 햇징 룰 적용 (`backfill_batch_id` + `CANDLE_BACKFILL_*` audit) + admin endpoint range 파라미터 | 4~5 | 3~4 cycle | 중간 | ★★★ |
| **E — A + chart-curator skill §함정 #8 amend** | A + skill amend (raw docstring 한도 명시 정독 의무 추가) — 영구 룰화 | 1 PR + skill commit | 1 cycle | 낮음 | ★★★★ |

### 권장 (보수 페르소나)

- **1순위: 옵션 B (A + api 동조)** — root cause 두 path(consumer cold-start + api 사용자 수동 갱신) 동시 fix. 검증된 패턴 복원 + 회귀 risk 낮음. 단일 cycle 1~2 PR로 마무리 가능. chart-curator 영역 핵심 단일 owner 책임 정합.
- **2순위: 옵션 E를 1순위와 묶기** — 1순위 PR과 같은 사이클에 skill amend commit 추가. 동일 결함 영구 차단 (보수 페르소나 핵심 가치 §1·§5).
- **3순위: 옵션 C·D의 잔여 의제는 별도 task로 분리** — market_calendar 영업일 row seed + cold-start path §3·§9 룰 적용은 본 의제와 별개 backlog. 같은 PR에 묶지 말 것 (개발 룰 #2 단일 책임 코드 정합).

### 비권장

- **D 일괄 처리**: 한 PR에 4영역 묶음 → 개발 룰 #2 위반 + 리뷰 부담 + 충돌 risk. 강력히 비권장.
- **사용자 임의 fix 시도**: KIS 페이지네이션 algorithm은 cursor·MAX_PAGES·distinctBy 경계 중복·boundary skip 등 함정 다수. 신입 자율 추론 risk (보수 페르소나 §사유 — chart-curator 첫 2 사이클 결함 5건 패턴 회귀 위험). **2026-05-04 commit `2cbd4e8` 본문에 있는 algorithm을 그대로 복원**해야 안전.

---

## 7. chart-curator skill 후속 룰 추가 의제 (사용자 결재 시 별도 commit)

skill `magicjar-chart-curator/SKILL.md` 갱신 권고:

### §함정 #8 amend (KIS 페이지네이션 의무 추가)

```diff
- 8. **KIS DESC 응답 정렬 함정 (amend · 룰 §11 cross-ref)** — `inquire-daily-itemchartprice` (FHKST03010100) 응답은 DESC. adapter parse 함수에서 반환 직전 `.sortedBy { it.ts.epochSeconds }` 의무.
+ 8. **KIS DESC 응답 정렬 + 100건 한도 함정 (amend · 룰 §11·§14 cross-ref)** — `inquire-daily-itemchartprice` (FHKST03010100) 응답은 (a) DESC 정렬 (b) 한 번의 호출에 최대 100건. parse 함수에서 ASC 정규화 의무 + adapter 호출 함수에서 cursor 페이지네이션(MAX_PAGES + oldestDate 기준 cursor backwalk + distinctBy boundary dedup) 의무. 단일 호출 종료 금지. evidence: 한국석유 5개월 stale 재발 진단 보고서 (2026-05-19) + 2026-05-04 commit 2cbd4e8 algorithm 복원 path.
```

### §데이터 햇징 룰 §14 신설 (선택)

```markdown
| 14 | **KIS 호출 한도 cover 의무 (amend)** | KIS REST endpoint별 단일 호출 한도(일봉 100건 등)는 cursor 페이지네이션으로 우회 의무. 단일 호출로 종료 금지. cursor는 oldestDate 기반 backwalk. MAX_PAGES safety guard 의무. 경계 중복은 distinctBy로 dedup. evidence: 한국석유 12-30 stale 재발 진단 (2026-05-19) — PR `2cbd4e8` algorithm 복원 path. |
```

### §함정 추가 #10 — "임시 fix가 regression되는 함정"

```markdown
10. **fix 후 regression 함정 (amend · 보수 페르소나 §사유 정합)** — 한 번 fix된 algorithm이 후속 리팩토링·전면 재작성에서 누락될 수 있음 (evidence: 2026-05-04 `2cbd4e8` 페이지네이션 → 2026-05-11 `915699b` KIS-only 전환 시 regression). 정합 패턴: 핵심 algorithm은 별도 helper 함수로 분리 + 회귀 test 1건 의무 + skill §함정 가드에 명시 등재. 전면 재작성 시 §함정 가드 cross-ref 매트릭스 의무.
```

---

## 8. 영역 경계 검증 (보수 페르소나 self-audit)

- ✅ 진단 + 보고서만 (코드 fix 0). consumer/api/batch/frontend 운영 코드 0 touch.
- ✅ DB read-only만 (SELECT만, DML 0).
- ✅ KIS API 직접 호출 시도 자체는 했으나 보안 차단으로 회피 — secret 노출 risk 0.
- ✅ git author: `bot+chart-curator@magicjar.local` (`git config --worktree` 적용 확인).
- ✅ KIS-first raw 출처 의무 — KIS 공식 docstring `mcp__kis-code-assistant__read_source_code` 응답 인용 ("한 번의 호출에 최대 100건").
- ✅ ADR 0086 §11·§12·§13 cross-ref 의무.
- ✅ 헌법 §1·§3·§4 정합.

---

## 9. 부수: 본 진단 보고서 발행 후 즉시 권고 액션

1. **사용자 결재**: §6 처리 방안 매트릭스에서 선호 옵션 선택. 권장은 옵션 B+E.
2. **결재 후**: chart-curator (또는 market-data·api 합동)이 별도 PR 발사. base는 `main`. 본 진단 보고서를 PR 본문에 참조.
3. **검증 의무**: fix PR 머지 후 사용자가 dogfooding 재실행 — 빈 DB 초기화 + watchlist 한국석유 등록 + `last_candle_at` 확인. DB row 4 timeframe max=오늘 또는 직전 영업일이어야 통과. **이 evidence 없이 "fix 완료" 종결 금지** (사용자 메모리 `feedback_verification_strict.md` 정합).

---

## 10. 본 보고서 발행 metadata

- **PR**: agent/chart-curator/candle-12-30-stale-recurrence-investigation
- **task.mjs id**: T-20260519-2311-candle-12-30-stale-recurrence-investigat
- **CHANGELOG row**: 후속 작업 종료 시 추가
- **연관 PR 매트릭스 (사용자 인용)**: #535(LWC+일봉 진단) · #537(parseKisDailyResponse ASC) · #540(잔여 timeframe ASC) · #541(LWC dispose §12·§13) · #558(parseKisDailyOutput2 ASC api) · #571(KIS adapter 8 통합)
- **신규 발견 회귀 commit**: `915699b` (2026-05-11) — `RealKisRestAdapter` → `KisRestCandleAdapter` 전면 재작성 시 페이지네이션 누락
- **회복 기준 commit**: `2cbd4e8` (2026-05-04) — algorithm evidence + 한국석유 검증 완료
