# ADR 0120 — 1wk/1mo/1y를 1d 합산으로 전환 — 기준봉/파생봉 통일 모델

- 상태: **Accepted (2026-06-10 — 사용자(최고 관리자) 결재 A안 + Phase 1 백엔드 구현 완료 · amend §9 성능 최적화 2026-06-10)** — 2026-06-09 MIGRATE wipe 가 일봉 패밀리 4종(1d·1wk·1mo·1y)을 전부 삭제했으나 R-BF 캠페인이 1d 만 재적재하여 1wk·1mo·1y 가 15종목만 남은 **년봉 회귀**의 근본 fix. 1wk/1mo/1y 를 KIS 직접 fetch(FHKST03010100 W/M/Y)가 아니라 **이미 보유한 1d(전종목 10년)에서 합산 생성**으로 전환한다(KIS 콜 0). **amend §9**: Phase 1 전종목 백필 실측(~3종목/분·45/2,517) 결함을 batch upsert + R2 incremental + 비동기 병렬 admin 으로 최적화.
- 작성: market-data
- 영역: 데이터(A) — candle ingestion / 상위 timeframe 생성 정책. 자본 결정권 0.
- 관계:
  - **ADR 0118** (전종목 일봉 universe + 저녁/장전 멀티시세 snapshot) — 본 ADR의 직접 트리거. ADR 0118 R-BF가 1d 기준봉을 전종목 10년 적재하므로, 1wk/1mo/1y는 그 1d에서 파생 합산으로 생성 가능해졌다. R2 저녁/장전 스케줄러에 합산 단계를 add-only로 확장(§3-3).
  - **ADR 0119** (1m proactive cold-start 백필 폐지) — cold-start가 1d·1wk·1mo·1y 5 interval을 fetch하던 역할을 무용화한 후속. 본 ADR이 그 정합 위에서 1wk/1mo/1y의 적재 출처를 KIS-fetch → 1d-rollup으로 narrow화한다. 기준봉/파생봉 통일 모델을 완성.
  - **ADR 0062 / 0093** (합산 interval 백엔드 precomputed + timeframe별 페이지네이션 매트릭스) — `CandleAggregator`(1m→3m~12h) 패턴을 그대로 1d-family에 대칭 적용(`DailyRollupAggregator`). ADR 0062의 "백엔드 precomputed(A-1)" 결정을 1d-family로 확장.
  - **ADR 0118 amend §9-B** (J 채널 단일화) — 1d가 J(KRX 본시장) 단일 채널이므로, 1d-rollup 결과의 거래량은 "우리가 서빙하는 J 1d의 합"과 항상 일치한다(레거시 KIS-fetch W/M/Y는 UN/통합 채널이라 거래량 불일치 — §5-2 evidence). 롤업이 정합 우위.
  - direction-guard: 장중 실시간 권위 = KIS WS tick + 진행봉 라이브 merge 원칙 유지(§4 실시간 보존 불변 제약). watchlist 자동삽입 금지·STRATEGY/DETAIL owner 분리 정책 무변경.

---

## 1. 배경 — 년봉 회귀 (2026-06-09 마이그레이션 부작용)

### 1-1. incident evidence (DB 실측 2026-06-10)

```
 interval |  rows   | codes
----------+---------+-------
 1d       | 5129649 |  2517   ← R-BF가 전종목 재적재
 1wk      |   18355 |    15   ← MIGRATE wipe 후 재적재 안 됨
 1mo      |    4230 |    15
 1y       |     361 |    15   ← 차트 년봉 안 나오는 원인
```

ADR 0118 amend의 MIGRATE 모드(`POST /admin/market-daily-backfill/run?mode=MIGRATE`)는 UN/NX stale 동결 일소를 위해 **일봉 패밀리 4종(1d·1wk·1mo·1y)을 wipe**한 뒤 J 채널로 재적재한다. 그러나 R-BF 캠페인(`MarketDailyBackfillCampaign`)은 `INTERVAL = ONE_DAY` 즉 **1d만 재적재**한다. 결과적으로 1wk·1mo·1y는 wipe된 채 비어버렸다(직전 15종목은 wipe 이전 잔재).

### 1-2. 핵심 발견 — 1wk/1mo/1y를 KIS 직접 fetch할 이유가 없다

현 아키텍처는 1wk/1mo/1y를 KIS 직접 fetch(FHKST03010100 period_div_code W/M/Y) 전제였다:
- `KisRestCandleAdapter.fetchDailyCandles` / `CandleRefreshService.refreshDailyCandles` — `DAILY_INTERVALS = {1d→D, 1wk→W, 1mo→M, 1y→Y}` cursor 페이지네이션.
- `AggregateBackfillRunner`는 1m→3m~12h만 합산하고 "1wk/1mo/1y는 KIS 직접 수집이므로 합산 제외"라고 주석.

그러나 우리는 **1d 전종목 10년(5.1M row · 2,517종목)을 이미 보유**한다. 1wk/1mo/1y는 1d의 단순 OHLCV 롤업으로 완전 복원 가능하다. KIS를 전종목 W/M/Y로 다시 긁으면 ~95k콜(2,517종목 × 3 timeframe × ~10여 페이지)이 드는데, **1d 롤업은 KIS 콜 0**이다.

---

## 2. 결정 (사용자 결재 A안) — 1d 롤업 전환 + 기준봉/파생봉 통일 모델

### 2-A. 대안 비교

| 안 | 방식 | KIS 콜 | 정합성 | 채택 |
|----|------|--------|--------|------|
| **A (채택)** | 1wk/1mo/1y ← 1d in-memory 롤업 | **0** | 우리가 서빙하는 1d와 항상 일치 (§5-2) | ✅ |
| B | 전종목 KIS W/M/Y 재fetch | ~95k콜 | 레거시 UN 채널이면 1d(J)와 거래량 불일치 | ❌ |

### 2-B. 통일 모델 (기준봉 / 파생봉)

```
기준봉 (KIS raw fetch)        파생봉 (in-memory 합산 · KIS 콜 0)
─────────────────────        ────────────────────────────────
1m (기간 한정, FHKST03010230) → 3m·5m·15m·30m·90m·1h·4h·12h   [CandleAggregator]  (기존)
1d (R-BF/R2 전종목, FHKST03010100 D) → 1wk·1mo·1y             [DailyRollupAggregator] (신규·본 ADR)
```

KIS raw fetch는 **1m + 1d 둘뿐**으로 좁혀진다. 나머지 11개 interval은 전부 두 기준봉에서 파생 합산.

### 2-C. ts·OHLCV 관례 — KIS FHKST03010100 (W/M/Y) 실측 정합 (자체 추론 0)

기존 15종목(005930 삼성전자) KIS-fetch 봉을 DB 실측하여 KIS의 period-boundary 관례를 reverse-engineering하고 1:1 정합시켰다(헌법 §3 KIS-first 정합 — ts 관례를 자체 추론하지 않고 KIS 실측 출처로 결정):

| interval | 그룹 경계 | bar ts (KIS 관례) | open | high | low | close | volume |
|----------|-----------|-------------------|------|------|-----|-------|--------|
| ONE_WEEK | ISO 주(월~일) | **그 주 첫 거래일** (월요일·휴장 시 화요일) | 첫 거래일 open | max | min | 마지막 거래일 close | sum |
| ONE_MONTH| 역월 | **그 달 마지막 거래일** | 첫 거래일 open | max | min | 마지막 거래일 close | sum |
| ONE_YEAR | 역년 | **그 해 마지막 거래일** | 첫 거래일 open | max | min | 마지막 거래일 close | sum |

⚠ **주봉만 ts=첫 거래일, 월/연봉은 ts=마지막 거래일** (KIS 비대칭 관례). 진행 중(오늘 포함) 미닫힌 봉도 KIS는 "현재까지의 마지막 거래일"을 ts로 준다(월봉 06-09 등). 본 롤업은 입력 1d의 마지막 거래일을 그대로 사용하므로 동일 동작.

수정주가: 현 1d는 raw-only(adj 4필드 null — `fid_org_adj_prc=0`). 롤업도 동일 — openAdj=첫날·closeAdj=마지막날·highAdj/lowAdj=non-null max/min. 1d가 adj를 갖게 되면 롤업이 자동 동반(별도 분기 0).

### 2-D. 적재 경로 (R-BF / R2 / admin rollup 분업)

| 잡 | 시각 | 대상 | 동작 |
|----|------|------|------|
| R-BF (`MarketDailyBackfillCampaign`) | admin /run·야간 | 1d 과거 10년 | KIS range fetch — **기준봉 적재** (무변경) |
| **admin rollup (`MarketDailyRollupService` · 본 ADR)** | `POST /admin/market-daily-rollup/run` | 전종목 1wk/1mo/1y | 1d 전량 in-memory 롤업 upsert(DO UPDATE) — **1회 복구 + 수동 재실행** (KIS 콜 0) |
| **R2 확장 (`MarketDailyNightlySnapshotService` · 본 ADR §3-3)** | 매 영업일 20:05 + 08:05 | 전종목 1wk/1mo/1y | 1d snapshot 직후 1d 전량에서 재롤업(증분 최신화) |

---

## 3. Phase 1 구현 (백엔드 — 본 PR)

### 3-1. `DailyRollupAggregator` (domain · 순수 함수)

`domain/.../market/candle/DailyRollupAggregator.kt` — `CandleAggregator`(1m→3m~12h)의 1d-family 대칭 컴포넌트. Spring·JPA 의존 0, 외부 의존 0(kotlinx.datetime), 결정론적. `rollup(candles: List<Candle>, target)` — 1d list를 ISO 주/역월/역년 그룹화 → OHLCV 롤업. 구간 내 1d 0건이면 bar 생략(mock 합성 0). 단위테스트 12건 GREEN(실측 회귀 비교 포함 — §5).

### 3-2. `DailyFamilyRollupRunner` + `MarketDailyRollupService` + admin (consumer)

- `DailyFamilyRollupRunner.rollupDetailed(code)` — 종목 1d 전량 조회 → 1wk/1mo/1y 롤업 → `upsertAll`(ON CONFLICT DO UPDATE). `AggregateBackfillRunner` 패턴 정합. KIS 콜 0.
- `MarketDailyRollupService.runRollup()` — `MarketDailyUniverseProvider` 보통주 전종목 순회 + 종목별 runner 호출 + 집계 + audit(MARKET_DAILY_ROLLUP_START/DONE). 멱등(upsert DO UPDATE).
- `MarketDailyRollupAdminController` — `POST /admin/market-daily-rollup/run` (port 8082). 년봉 회귀 1회 복구 + 수동 재실행.

### 3-3. R2 저녁/장전 스케줄러 확장 (`MarketDailyNightlySnapshotService`)

1d snapshot(멀티시세 upsert) 직후 `runDailyFamilyRollup(codes)` 단계 추가 — DB 1d 전량(닫힌 봉 + today)에서 1wk/1mo/1y 재롤업. 매 영업일 20:05 today 봉 최신화 + 08:05 직전 영업일 재확정 후 주월년봉도 함께 최신화한다. best-effort(종목별 실패는 다음 영업일 재시도). SnapshotResult에 rollupSymbols/Weekly/Monthly/Yearly 관측 필드 add-only.

### 3-4. KIS W/M/Y fetch 경로 — 잔존(폐지 아님)

`KisRestCandleAdapter`·`CandleRefreshService`의 W/M/Y fetch 경로는 **삭제하지 않는다** — Phase 2 사용자 트리거 재수집(`recollect`)에서 1d 강제 재수집 후 롤업하는 경로의 일부로 잔존 가능하며, 자동 적재 출처만 KIS-fetch → 1d-rollup으로 전환된다. 보수적 add-only(기존 경로 회귀 0).

---

## 4. 실시간 보존 (불변 제약 — direction-guard LOCK)

오늘 진행봉이 1m~1y 전부 실시간으로 움직이는 현 동작을 **반드시 유지**한다.

- **닫힌 봉**: 본 롤업이 "어제까지(+R2가 적재한 today 1d 포함) 닫힌 봉"을 생성·upsert.
- **장중 진행봉**: `CandleQueryService.mergeQuoteLastCurrentCandle`(api)가 `quote:last:{code}`(KIS WS 현재가)를 최신 봉에 merge. `isSameQuotePeriod`가 1d=같은 날·1wk=`weekStart`(월요일 기준)·1mo=year+month·1y=year로 같은 기간 판정.
- **회귀 0 근거**: 롤업의 ts 관례를 KIS와 **동일하게** 유지(§2-C 실측 정합)하므로, live-merge의 `isSameQuotePeriod` 매칭이 깨지지 않는다. 롤업 결과(닫힌 스냅샷) 위에 quote:last가 그대로 덮인다(같은 ts → replace, 신규 ts → append — `getCandles`의 same-ts replace 패턴).
  - ⚠ 주의: `isSameQuotePeriod`의 1wk 판정은 `weekStart`=직전 월요일 calendar anchor지만, 롤업 ts는 "그 주 첫 거래일"(휴장 시 화요일)이다. 같은 ISO 주이면 quoteTs·latestTs 둘 다 같은 월요일로 정규화되므로 매칭 일치(연말연시 ISO 경계 포함 — isoWeek 단위테스트 검증). 월요일 휴장 주의 latest 봉 ts(화요일)도 weekStart로 정규화 시 같은 월요일 → 매칭 유지.

---

## 5. 검증 (회귀 비교 — KIS-fetch 봉 vs 1d 롤업)

### 5-1. 월봉 OHLCV 완전 일치 (005930 2026-05 · DB 실측)

| 항목 | KIS-fetch 1mo 봉 | 1d 18봉 롤업 | 일치 |
|------|------------------|--------------|------|
| ts | 2026-05-29 (5월 마지막 거래일) | 2026-05-29 | ✅ |
| open | 228000 (=05-04 첫 거래일 open) | 228000 | ✅ |
| close | 317000 (=05-29 close) | 317000 | ✅ |
| high | 323000 | 323000 | ✅ |
| low | 224000 | 224000 | ✅ |
| volume | 622,815,748 | 622,815,748 (1d sum) | ✅ |

주봉 2026-06-08주: ts=06-08(첫 거래일 월) · open=293000(=06-08 1d open) · close=322000(=06-09 1d close). 연봉 2025: ts=2025-12-30(마지막 거래일). 전부 실측 정합.

### 5-2. ★ 거래량 채널 차이 (롤업이 정합 우위)

주봉 2026-06-08주 거래량 비교:
- 레거시 KIS-fetch 1wk: **67,918,935** (source=`KIS` — UN/통합 채널 era, NXT 포함 추정)
- 현 J-채널 1d sum: **69,053,931** (06-08 R-BF 38,929,682 + 06-09 R2-snap 30,124,249)

레거시 KIS-fetch W봉은 UN/통합 채널로 긁힌 반면, 현 1d는 J(KRX 본시장) 단일(ADR 0118 amend §9-B)이라 거래량이 다르다. **롤업은 "우리가 차트로 서빙하는 J 1d의 합"과 항상 일치**하므로, 1d 일봉을 더해 주봉을 만들면 정확히 그 합이 나오는 내부 정합이 보장된다. 레거시 KIS W봉은 1d(J)와 어긋나 오히려 사용자 혼란 유발. 채널 통일(J)이 롤업의 부수 정합 이득.

---

## 6. Phase 2 — 재수집 UI (✅ 구현 완료 2026-06-10 — frontend + api additive)

> **status: 구현 완료** (2026-06-10, PR 미정 — frontend `RecollectModal` + api `recollect` additive 보강). 아래 spec 대로 차트 "모든 캔들" 버튼을 체크박스 2그룹 모달로 재구성하고, 1wk/1mo/1y 재수집 경로를 1d 강제 재수집 → 롤업으로 전환했다. 진입 봉수 기억은 6-2 변형(접힘 대신 봉수 마지막값 복원·default 200)으로 확정.

차트 "모든 캔들" 버튼을 통일 모델 정합으로 재구성한다(향후 frontend + backend-core 협업 사이클):

### 6-1. 재수집 패널 2 그룹 (기준봉 + 파생 체크박스)

```
┌─ 분 단위 (기준봉 1m) ────────────────────────┐
│ ☑ 1m  [기간 선택: 1개월 ▾]   (KIS raw fetch)   │
│   └ 파생 합산 (기본 전체 체크):                │
│     ☑ 3m ☑ 5m ☑ 15m ☑ 30m ☑ 90m ☑ 1h ☑ 4h ☑ 12h │
├─ 일 단위 (기준봉 1d) ────────────────────────┤
│ ☑ 1d  [강제 재수집]          (KIS raw fetch)   │
│   └ 파생 합산 (기본 전체 체크):                │
│     ☑ 1wk ☑ 1mo ☑ 1y                          │
└──────────────────────────────────────────────┘
```

- 1m은 기간 선택(KIS 보관 한도 내), 1d는 강제 재수집(전 기간). 파생은 기준봉 재수집 후 자동 롤업.
- 파생 체크박스는 기본 전체 체크 — 기준봉만 재수집하고 파생을 끄는 것도 허용(부분 재계산).

### 6-2. ① 진입 봉수 localStorage 기억 (✅ 구현 — 확정 형태)

차트 표시 봉수(CandleCountSelector)의 **사용자가 콤보/직접입력으로 조작한 마지막 값**만 localStorage 전역 키(`magicjar:chart:candleCount`)에 저장하고, 재진입 시 복원한다. 조작 전(저장값 부재) default 는 **200**(직전 ADR 0093 §Phase 4-B 의 600 default 를 "마지막 사용값 우선" 정책으로 일관 정리). `CANDLE_COUNT_PRESETS` 에 200 추가.

(당초 spec 의 "진입 횟수 기억 → N회 이상 패널 접기"는 사용자 결재 단계에서 "마지막 사용 봉수 복원"으로 단순·확정. 패널 접힘 UX 는 v1 범위 외.)

---

## 7. 가드 매트릭스

| 가드 | 본 PR |
|------|-------|
| KIS-only (외부 fallback 0) | ✅ 롤업은 KIS 콜 0 — 외부 출처 0 |
| mock 합성 0 | ✅ 구간 내 1d 0건이면 bar 생략 (DailyRollupAggregator) |
| LLM import 0 (룰 5) | ✅ 데이터 적재만 |
| 자본 영향 0 | ✅ 주문 0 — candle 적재만 |
| 실시간 보존 회귀 0 | ✅ §4 — ts 관례 KIS 정합 → live-merge 무변경 |
| 한 PR = 한 영역 | ✅ market-data (candle ingestion) |
| ts·수정주가 기존 관례 정합 | ✅ §2-C·§5 실측 회귀 비교 PASS |
| Repository default fun 함정 (ADR 0051) | ✅ 신규 Repository 0 — 기존 CandleRepository 포트 재사용 |

---

## 9. amend (2026-06-10) — 성능·무지성 재생성 최적화 (실측 결함 fix)

### 9-1. 실측 결함 (Phase 1 전종목 백필 가동)

`POST /admin/market-daily-rollup/run` 전종목 백필 실측:
- **처리율 ~3종목/분** → curl 10분 타임아웃에 **45/2,517종목만** 처리(나머지 ~2,470 미복구).
- **병목 = `ConsumerCandleRepositoryAdapter.upsertAll` row-by-row 루프**: 종목당 ~2,000봉(1wk 1,568+1mo 361+1y 30)을 `insertOrUpdate` 단건 호출로 반복 → DB round-trip 폭증(~20초/종목).
- **무지성 full-regen**: 매 run·매 R2 저녁/장전마다 종목별 1d 전량(10년 ~2,450봉) 읽어 1wk/1mo/1y **전체 history 재계산+upsert**. 1회성 복구엔 OK지만 R2 2회/영업일 전종목 전체 재생성은 낭비(과거 닫힌 주/월/년봉은 1d 불변인데 매 run ~160만 row upsert).
- 부팅 트리거는 0(admin + R2 스케줄뿐)이라 "재기동마다 무조건 도냐"는 무관 확인 — 단 무지성 full-regen·저속은 사실.

### 9-2. 최적화 3종 (사용자 결재)

**① batch upsert** — `ConsumerCandleRepositoryAdapter.upsertAll`을 `NamedParameterJdbcTemplate.batchUpdate`로 전환([UPSERT_SQL] = `insertOrUpdate` @Query 와 1:1 동일 `ON CONFLICT (code,interval,ts) DO UPDATE`). `UPSERT_BATCH_CHUNK`(500) 묶음 → 종목당 ~2,000 round-trip → 수 회. **semantics 보존**: SQL·무조건 DO UPDATE 동일, 반환 affected = batch row count 합산(음수 `SUCCESS_NO_INFO`=-2 는 1 보정). 공유 경로(R2 snapshot·rollup·AggregateBackfillRunner) 회귀 0. 헌법 §6 정합(네이티브 SQL·Repository default fun 0 — JPA `insertOrUpdate` 메서드는 보존). ts 는 pgjdbc `java.time.Instant` 직접 바인딩(`ConsumerRiskPositionAdapter` 정합).

**② R2 저녁/장전 incremental** — `DailyFamilyRollupRunner.rollupDetailed(code, recentDays)` 추가. R2 스케줄은 `DEFAULT_INCREMENTAL_RECENT_DAYS`(400 거래일 ≈1.6 calendar year)만 `findLatestByCodeAndInterval(ONE_DAY, 400)` 로 읽어 롤업 → 갱신 필요한 경계 봉만 upsert(올해 진행 + 작년 닫힌 연봉까지 안전 커버, 주/월 경계도 윈도우 내 포함). **경계 정확성**: 윈도우가 잘렸으면(`oneDayCount >= recentDays`) 가장 이른 그룹(윈도우 밖 더 이른 1d 포함 가능한 부분 그룹)은 upsert 제외 → 이미 정확 적재된 불변 봉을 부정확하게 덮지 않음 → full-regen 과 동일 결과. 양 ~95% 절감(1y 전체~30봉 → 2봉). **admin `/run`은 full-regen 유지**(recentDays=null · 1회성 복구·전 history 정합 보증).

**③ 백필 동시성 + 비동기 admin** — `MarketDailyRollupService.runRollupInternal`을 코루틴 `Semaphore(CONCURRENCY=4)` + `Dispatchers.IO.limitedParallelism(4)` 병렬화(KIS 콜 0 — rate-limiter 무관, hikari pool=5 대비 보수). `MarketDailyBackfillCampaign` 동시성 패턴 정합. admin `POST /run`은 `runRollupAsync()`로 **즉시 202 ACCEPTED + runId 반환 + 백그라운드 실행**(curl 10분 타임아웃 회피) — 동시 1건만(중복은 409 ALREADY_RUNNING). 진행 관측 = 로그 + DB audit + `GET /admin/market-daily-rollup/status`(running 여부 + lastResult).

### 9-3. 가드 (amend)

| 가드 | amend |
|------|-------|
| upsertAll 공유 경로 회귀 0 | ✅ SQL·semantics 동일(batchUpdate 만 교체)·R2/AggregateBackfillRunner 무영향 |
| KIS 콜 0 | ✅ 롤업·병렬·incremental 모두 DB+CPU only |
| mock 0 · LLM 0 · 자본 0 | ✅ 데이터 적재만 |
| 실시간 보존 회귀 0 | ✅ CandleQueryService(api) 무변경 — live merge 무영향 |
| Repository default fun 함정 (ADR 0051) | ✅ 신규 Repository 0 · 네이티브 SQL 인라인(JdbcTemplate) |
| incremental = full-regen 동일 결과 | ✅ 경계 부분 그룹 drop(단위테스트 검증) |

## 10. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-06-10 | ADR 0120 Accepted — 1wk/1mo/1y 1d 롤업 전환 + 기준봉/파생봉 통일 모델. Phase 1 백엔드 구현(DailyRollupAggregator·DailyFamilyRollupRunner·MarketDailyRollupService·admin·R2 확장 + 단위테스트). Phase 2 재수집 UI spec deferred. (market-data) |
| 2026-06-10 | **amend §9** — 성능·무지성 재생성 최적화(실측 ~3종목/분·45/2,517 결함 fix): batch upsert(JDBC batchUpdate·semantics 보존)·R2 incremental(400 거래일·경계 정확성)·비동기 병렬 admin(코루틴 Semaphore 4·202+runId+/status). 공유 경로 upsertAll 회귀 0. (market-data) |
