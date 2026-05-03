# 캔들 데이터 정책 v2 — 1분봉 단일 영속 + 어플리케이션 레벨 합성

> **Status**: ACTIVE v2 (2026-05-03 사용자 정정)
> **Stewards**: architect (메타) + market-data 에이전트
> **Supersedes**: `candle-data-policy.md` v1 (2026-05-03 PR #91, deprecated 표시)
> **References**: ADR 0048 (1분봉 단일 영속·합성), ADR 0033 (3-tier fallback), ADR 0035 (KIS HTTP 정밀화), ADR 0038 (corp action raw·adjusted), ADR 0039 (IndicatorSource 추상), 마스터 §A.2·§3-5·§6-2
> **변경 정책**: 본 문서 변경 시 사용자 컨펌 의무 — 데이터 모델·운영 비용·외부 소스 정책에 직결

---

## 0. 변경 요지 (v1 → v2)

| 항목 | v1 | v2 | 사유 |
|---|---|---|---|
| **분봉 영속** | M1·M5·M30 다중 영속 (LRU cache, 합성 X) | **M1 단일 영속** | 정석 quant 플랫폼 패턴 — single source of truth |
| **N분봉 (2,3,5,10,15,30,60,90,120…)** | M5 영속 + M15·H1·M30(선택) 합성 | **전부 백엔드 합성** | 임의 N 지원·중복 영속 0·디스크 절약 |
| **백테스트 raw 분봉** | 최근 6개월 lazy fetch | **1분봉 LRU 1년 + Yahoo cold-start 30일** | KIS 분봉 API는 당일 전용 — 자체 누적 + Yahoo 보강 |
| **frontend candleAggregator** | 클라 D1→W/MO/Y · M5→M15/M30/H1 합성 | **D1→W/MO/Y만 클라 (요시 제거 후보)**. 분봉 합성 0 — 백엔드가 N분봉 응답 | 단일 진실원 + 코드 중복 제거 |
| **외부 source** | KIS REST 단일 | **KIS WS (장중 1분봉 누적) + Yahoo (cold-start backfill) 하이브리드** | KIS `FHKST03010200`은 당일 전용 — 과거 분봉 backfill 불가 |
| **interval CHECK constraint** | (없음) | **interval IN ('M1','D1')** PostgreSQL CHECK | 정책 위반 INSERT 사전 차단 |
| **API 호환성** | M5/M15/M30/H1 직접 query | **M5/M15/M30/H1/N분 모두 OK — 백엔드가 M1 query 후 합성 응답** | 사용자 경험 무회귀 |

---

## 1. 핵심 원칙 (v2 정정)

1. **단일 진실원 (Single Source of Truth)**
   - **M1 (1분봉)** = 분봉 SoT. 모든 N분봉은 M1 합성으로 생성.
   - **D1 (일봉)** = 일봉 SoT. 모든 W/MO/Y는 D1 합성으로 생성.
   - 그 외 interval은 영속 X.

2. **하이브리드 외부 source** (master §외부 매트릭스 정합)
   - 장중 실시간 1분봉: **KIS WS H0STCNT0 tick → consumer가 1분 OHLCV 합성 → DB INSERT** (TickToMinuteAggregator)
   - 과거 1분봉 cold-start (~30일): **Yahoo Finance** (`yfinance` Python lib · pyworker 영역). KIS 미지원 영역.
   - 일봉 backfill: **KIS REST `FHKST01010400` 1차 + Yahoo 보강** (3-tier fallback)
   - 외부 source 모두 끊겨도 자체 누적 1분봉으로 차트·screener·signal·backtest 정상 (master §외부 매트릭스 fallback 원칙)

3. **백엔드 합성 (Application-level Aggregation)**
   - 합성 위치: **api 모듈 application layer** (`CandleAggregator` 신규)
   - 알고리즘: open=첫 봉 open · high=max(high) · low=min(low) · close=마지막 봉 close · volume=sum(volume) · adjusted 컬럼들 동일
   - N분 lock-step 정렬: KST 09:00 기준 0/N 분 boundary로 grouping (5/15/30/60분 등)
   - 미완성 마지막 봉은 응답에 포함 (사용자가 차트에서 진행 중인 봉 보임)
   - 캐시: Redis `candle:agg:{code}:{N}m:{from}-{to}` TTL 60초 (자주 조회되는 윈도우)

4. **데이터 사이즈 — 8GB 한도 안에서 universe 결정**
   - 1분봉 1 row ≈ **150 byte** (PostgreSQL row 헤더 24B + payload + adjusted 컬럼 + index overhead)
   - 1 종목 1 영업일 = 6.5h × 60 = **390 분봉**
   - 1 종목 1년(250 영업일) = 97,500 rows ≈ **15 MB**
   - 1 종목 3년 = 292,500 rows ≈ **45 MB**
   - 350 종목(KOSPI200+KOSDAQ150) × 1년 ≈ **5.1 GB** ✅
   - 350 × 3년 ≈ **15 GB** ❌ (8GB 한도 위반 → LRU 폐기 또는 universe 축소)
   - **운영 default**: 사용자 watch list + 검색·진입 종목 LRU 1년 (≤200 종목 기준 ≤3 GB), KOSPI200·KOSDAQ150 분봉은 cold-start 시 30일만 (≤1.5 GB)

5. **interval별 영속·합성 매트릭스 (v2 단순화)**

   | Interval | 영속 정책 | 합성 정책 |
   |---|---|---|
   | **D1 (일봉)** | 전종목 10년 영구 영속 (일봉 SoT) | — |
   | **W (주봉) / MO (월봉) / Y (연봉)** | 영속 X | D1 → W/MO/Y 백엔드 합성 (또는 frontend candleAggregator — v1 잔존) |
   | **M1 (1분봉)** | LRU 1년 영속 (분봉 SoT) | — |
   | **M2/M3/M5/M10/M15/M30/H1/M90/M120 등** | 영속 X | M1 → N분봉 백엔드 합성 |
   | **틱** | 실시간 KIS WS only, Kafka retention 1h | — |

   - **CHECK constraint**: `candle.interval IN ('M1','D1')` (Flyway V20 — 후보 위반 INSERT 차단)
   - 호환성: 클라이언트 `interval=M5/M30/H1` 호출 시 백엔드 `CandleAggregator.aggregate(M1, N=5/30/60)` → 응답
   - alias: D1 ↔ DAY (v1 이미 적용), M1 ↔ 1m, M5 ↔ 5m, M30 ↔ 30m, H1 ↔ 60m (frontend 호환)

---

## 2. 외부 source 통합 — Yahoo + KIS WS 하이브리드

### 2-1. Yahoo Finance (yfinance Python lib)

- **위치**: pyworker (이미 Python 환경, FastAPI + APScheduler 구축)
- **lib**: `yfinance` (BSD-3, 23.3k stars, 활발한 유지보수)
- **운영 모드**: pyworker `POST /backfill/yahoo` REST endpoint (배치 호출용) + `BackfillScheduler` (APScheduler cron)
- **Kafka egress**: pyworker → `market.candle.backfilled.v1` (신규 토픽) → consumer가 INSERT ON CONFLICT DO NOTHING
- **PoC 검증 결과 (2026-05-03 docker exec magicjar-pyworker python ... 실측)**:

| Interval | period 한도 | per request | 005930.KS PoC |
|---|---|---|---|
| 1m | **최근 30일 절대** | 8일 chunk | 7일 chunk → 1800 rows / 0.86s. 30일 직접 시도 → "Only 8 days worth of 1m granularity allowed per request" |
| 5m | 최근 60일 | 60일 직접 OK | period=60d → 4166 rows / 0.51s |
| 15m / 30m | 최근 60일 | 60일 직접 OK | (5m와 동일) |
| 60m / 1h | 최근 **2년** | 2년 직접 OK | period=2y → 2894 rows / 0.38s |
| 1d | **10년+** | 직접 OK | period=10y → 2448 rows / 0.24s |
| 30종목 burst (1d 1mo) | 10.6초 / ~3 req/s | — | 0.35s/종목 평균. 350종목이면 ~120초 |

- **한국 종목 suffix**: `.KS` 단일 (KOSPI·KOSDAQ 모두). `.KQ`는 작동 X — 005930.KS·091990.KS·293490.KS·086520.KS·263750.KS·247540.KS·141080.KS 모두 정상 응답 확인.
- **timezone**: `tz=Asia/Seoul` 자동 (KST 09:00~15:00 정확. UTC 변환 시 zoneKst() 헬퍼 사용 — master §3-5 시간대 원칙)
- **auto_adjust**: default=True (Yahoo가 자동 보정한 OHLC 반환). **우리는 `auto_adjust=False`로 호출** → raw OHLC + 별도 `Adj Close` 컬럼 동시 수령 → DB의 `close_raw` + `close_adjusted`에 매핑 (master §3-5 수정 주가 정합, ADR 0038)
- **결측·휴장 처리**: Yahoo는 휴장일 row 미포함. 우리 DB는 `INSERT ... ON CONFLICT (code, interval, open_time) DO NOTHING` 멱등 — 중복 없이 누적.
- **rate limit 안전 운영**: 호출 사이 0.5초 sleep + 동시 ≤5 ticker (일봉 350종목 ≈ 120초 / 1분봉 30일 ≈ 350 × 4 chunks × 1초 = 1400초 ≈ 23분)
- **장애 대응**: Yahoo 401·429·5xx 시 exponential backoff (1s → 2s → 4s, 최대 3회) + Slack alert (post-MVP). 본체 흐름 영향 0 — 영속 1분봉 유지로 차트·signal 정상.

### 2-2. KIS WS H0STCNT0 (장중 실시간 1분봉 누적)

- **위치**: consumer 모듈 (이미 KisExecutionNoticeListener·KisQuoteListener 패턴 있음)
- **신규 컴포넌트**: `TickToMinuteAggregator` (in-memory rolling buffer)
  - 종목별 현재 1분 윈도우(`Map<String, MinuteCandleAccumulator>`)
  - tick 도달 시 윈도우 갱신 (`high = max(high, price)`, `low = min(low, price)`, `close = price`, `volume += tick_volume`)
  - 매분 정각(00초) close 시점에 `INSERT INTO candle (..., interval='M1', ...) ON CONFLICT DO NOTHING` + Kafka `market.candle.v1` 발행 (master §6-4 정합)
- **rate limit**: KIS WS는 30 종목 동시 구독 한도 (T2 트랙) — universe ≤30 종목 동시 누적. 사용자 watch list + 활성 시그널 종목 우선.
- **결측 대응**: WS 끊김 시 ADR 0033 fallback (T2 cache·T3 last-day-close) + 재연결 시 `KisRestPort.fetchCandles(M1)` 당일분 보강 (KIS `FHKST03010200` 당일 분봉 — 30분 이내라면 가능).

### 2-3. 외부 source 매트릭스 (master §외부 매트릭스 정합 갱신)

| Source | 역할 | 빈도 | 본체 의존도 | fallback |
|---|---|---|---|---|
| **KIS WS H0STCNT0** | 장중 실시간 1분봉 누적 | 분당 (장중) | 매매 모니터링 시 필수 | TickFallbackEmitter (10s last-cached, ADR 0033) |
| **KIS REST FHKST01010400** | 일봉 backfill (1차) | 일별 batch (cron 18:00) | 일봉 영속 시 1차 | Yahoo 일봉 보강 |
| **Yahoo Finance 1d** | 일봉 backfill (보강) | 주별 cron (KIS 결측 보강) | **옵션** | 끊겨도 본체 영향 0 |
| **Yahoo Finance 1m** | 1분봉 cold-start backfill (~30일) | 종목 신규 진입 시 1회 + 주말 보강 | **옵션** | 끊겨도 자체 누적 분봉만 사용 |

---

## 3. 어플리케이션 레벨 합성 (Backend Aggregation)

### 3-1. 책임 위치 결정

| 옵션 | 장점 | 단점 | 채택? |
|---|---|---|---|
| **(a) 백엔드 (api 모듈) 단일** | 단일 진실원·캐시 가능·다양한 N 지원·DSL/screener/backtest 동일 합성 함수 재사용 | 응답 payload 약간 큼 | **✅ 채택** |
| (b) 프론트엔드 (frontend candleAggregator) 단일 | 백엔드 응답 작음·클라 cpu 활용 | 백엔드 측 DSL/signal/screener는 별도 합성 필요 → 코드 중복 | ❌ |
| (c) 양쪽 (backend = M1→Nm, frontend = D1→W/MO/Y) | v1 절충 | 합성 알고리즘 두 군데 — 실수 회귀 위험 | ❌ |

**결정**: **백엔드 단일** — v1의 frontend candleAggregator는 D1→W/MO/Y만 잔존(backend도 응답 가능하지만 사용자 zoom in/out UX 빠르게). 분봉 합성은 frontend에서 **완전 제거**.

### 3-2. CandleAggregator 알고리즘 (Kotlin pseudo)

```kotlin
// api/src/main/kotlin/com/mj/api/application/market/CandleAggregator.kt
class CandleAggregator {
    fun aggregate(
        m1Candles: List<Candle>,          // M1 영속 query 결과 (시간 오름차순)
        targetIntervalMinutes: Int,       // 2, 3, 5, 10, 15, 30, 60, 90, 120, ...
        sessionStart: LocalTime = LocalTime.of(9, 0)  // KST 09:00
    ): List<Candle> {
        return m1Candles.groupBy { candle ->
            val minutesFromOpen = ChronoUnit.MINUTES.between(
                candle.openTime.atZone(KST).toLocalDate().atTime(sessionStart),
                candle.openTime.atZone(KST)
            )
            val bucketIndex = minutesFromOpen / targetIntervalMinutes
            // bucket의 시작 시각
            candle.openTime.atZone(KST).toLocalDate().atTime(sessionStart)
                .plusMinutes(bucketIndex * targetIntervalMinutes)
        }.map { (bucketStart, bars) ->
            Candle(
                code = bars.first().code,
                interval = "M${targetIntervalMinutes}",
                openTime = bucketStart.toInstant(),
                open = bars.first().open,
                high = bars.maxOf { it.high },
                low = bars.minOf { it.low },
                close = bars.last().close,
                volume = bars.sumOf { it.volume },
                tradeCount = bars.sumOf { it.tradeCount },
                openAdjusted = bars.first().openAdjusted,
                highAdjusted = bars.maxOf { it.highAdjusted ?: it.high },
                lowAdjusted = bars.minOf { it.lowAdjusted ?: it.low },
                closeAdjusted = bars.last().closeAdjusted,
                volumeAdjusted = bars.sumOf { it.volumeAdjusted ?: it.volume },
                adjustmentFactor = bars.last().adjustmentFactor
            )
        }.sortedBy { it.openTime }
    }
}
```

### 3-3. CandleQueryService 통합 (기존 v1 정정)

```kotlin
// 현행 (v1 PR #91): repo.findByCodeAndIntervalAndOpenTimeBetween("M5", ...)
// v2: 분봉 요청 시 항상 M1 query → aggregator
fun query(code: String, interval: String, from: Instant, to: Instant): List<Candle> {
    return when {
        interval == "D1" || interval == "DAY" -> repo.findD1(code, from, to)
        interval == "M1" || interval == "1m"  -> repo.findM1(code, from, to)
        interval.matches(Regex("M\\d+|H\\d+|\\d+m|\\d+h")) -> {
            val minutes = parseToMinutes(interval)  // M5→5, H1→60, 90m→90
            require(minutes > 0 && minutes <= 1440) { "interval out of range" }
            val m1 = repo.findM1(code, from, to)
            aggregator.aggregate(m1, minutes)
        }
        interval in setOf("W","MO","Y") -> {
            val d1 = repo.findD1(code, from, to)
            aggregator.aggregateDailyToWeekly(d1, interval)
        }
        else -> throw IllegalArgumentException("unsupported interval: $interval")
    }
}
```

### 3-4. 캐시 (master §6-3 Redis 매트릭스 추가)

| key 패턴 | 용도 | TTL |
|---|---|---|
| `candle:m1:{code}` | M1 SoT (직접 조회 빠르게) | 60초 (장중) / 1시간 (장외) |
| `candle:agg:{code}:{N}m:{from}-{to}` | 합성 결과 (자주 조회되는 from/to) | 60초 |
| `candle:d1:{code}` | D1 영구 (장 마감 후 갱신) | 1시간 |

캐시 invalidate:
- `TickToMinuteAggregator`가 M1 INSERT 직후 `candle:m1:{code}` evict → 다음 query는 fresh
- `DailyCandleBackfillJob` D1 INSERT 직후 `candle:d1:{code}` evict
- agg 캐시는 60초 TTL로 자연 expire

### 3-5. frontend candleAggregator 처리

- **분봉 합성 함수** (`aggregateMinuteToN`): 즉시 폐기 + 호출처 1개(SymbolDetail 호출 시 N분봉 query) → backend N분봉 응답으로 대체
- **D1→W/MO/Y 합성 함수**: 잔존 (사용자 zoom out UX 빠름). 단 backend도 동일 응답 가능 — 사용자가 클라 합성 vs 서버 합성 선택 가능 (default = 클라).
- **테스트**: 분봉 vitest 케이스 6건 제거 (vitest 36 → 30). D1→W/MO/Y 케이스 4건만 잔존.

---

## 4. 마이그레이션 plan

### 4-1. 현재 candle 테이블 (production 검증 — 2026-05-03 PoC)

```sql
SELECT interval, count(*) FROM candle GROUP BY interval;
-- D1 | 278    ← 영속 유지
-- M1 | 360    ← 영속 유지
-- M5 | 222    ← DELETE (M1 합성으로 대체)
-- M30| 61     ← DELETE
-- H1 | 35     ← DELETE
```

총 318 row 정리 (D1·M1 638 row 보존). 운영 데이터 손실 0 — 합성으로 동일 결과 보장.

### 4-2. Flyway V20 — interval CHECK + 정리

```sql
-- V20__candle_policy_v2_m1_d1_only.sql

-- 1. M5/M15/M30/H1/M90 등 모든 비-M1 분봉 + 비-D1 row DELETE
DELETE FROM candle
 WHERE interval NOT IN ('M1', 'D1');

-- 2. instrument_cache_meta도 동일 정리 (T19 cache meta 폐기 후보지만 일단 데이터만 정리)
DELETE FROM instrument_cache_meta
 WHERE interval NOT IN ('M1', 'D1');

-- 3. CHECK constraint — 신규 INSERT 사전 차단
ALTER TABLE candle
  ADD CONSTRAINT candle_interval_v2_chk
  CHECK (interval IN ('M1', 'D1'));

ALTER TABLE instrument_cache_meta
  ADD CONSTRAINT cache_meta_interval_v2_chk
  CHECK (interval IN ('M1', 'D1'));

-- 4. 검증 view (운영 모니터링용)
CREATE OR REPLACE VIEW candle_v2_summary AS
SELECT interval,
       count(*) AS rows,
       count(DISTINCT code) AS distinct_codes,
       min(open_time) AS oldest,
       max(open_time) AS newest,
       pg_size_pretty(pg_total_relation_size('candle')) AS table_size
  FROM candle
 GROUP BY interval
 ORDER BY interval;

COMMENT ON CONSTRAINT candle_interval_v2_chk ON candle IS
  'Candle Policy v2 (ADR 0048): M1 (분봉 SoT) + D1 (일봉 SoT)만 영속. N분봉은 백엔드 합성.';
```

### 4-3. 코드 변경 매트릭스

| 영역 | 파일 | 변경 |
|---|---|---|
| **api/application/market** | `CandleAggregator.kt` (신규) | 함수 1·테스트 5 |
| **api/application/market** | `CandleQueryService.kt` (개정) | when 분기 + aggregator 호출 |
| **api/application/market** | `CandleResponse.kt` (DTO 매퍼 정정) | Synthesized 표시 (선택) |
| **api/controller** | `InstrumentController.kt` (정정) | interval valid 정규식 갱신 |
| **api/test** | `InstrumentControllerTest.kt` (정정) | M5/M30 응답 검증 케이스 추가 (합성 결과) |
| **api/test** | `CandleAggregatorTest.kt` (신규) | open=첫·high=max·low=min·close=마지막·volume=합 |
| **batch/infrastructure/persistence** | `CandleJpaAdapter.kt` (정정) | INSERT 멱등 + interval CHECK 정합 |
| **consumer/.../market** | `TickToMinuteAggregator.kt` (신규) | tick → 1분 윈도우 + 정각 INSERT |
| **consumer/test** | `TickToMinuteAggregatorTest.kt` (신규) | 윈도우 정확성 + 정각 flush + 결측 |
| **pyworker** | `app/routers/backfill_yahoo.py` (신규) | POST /backfill/yahoo · period · interval · code list |
| **pyworker** | `app/scheduler/backfill_scheduler.py` (신규) | APScheduler cron — 일별 일봉 보강·주말 1분봉 보강 |
| **pyworker** | `pyproject.toml` | yfinance 의존 추가 + uv lock |
| **pyworker test** | `tests/test_backfill_yahoo.py` (신규) | KOSPI 005930.KS PoC + 멱등 + 결측 |
| **frontend** | `src/lib/candleAggregator.ts` (정정) | 분봉 합성 함수 제거 (D1→W/MO/Y만 잔존) |
| **frontend test** | `src/lib/__tests__/candleAggregator.test.ts` | 분봉 케이스 6건 제거 |
| **db/migration** | `V20__candle_policy_v2_m1_d1_only.sql` | DELETE + CHECK + view |
| **docs/architecture** | `candle-data-policy-v2.md` (본 문서) | 신설 |
| **docs/architecture** | `candle-data-policy.md` (v1) | deprecated 헤더 |
| **\_workspace/arch/adr** | `0048-1m-only-candle-policy-and-application-aggregation.md` | 신설 |
| **docs/architecture** | `master-architecture.md` (선택 갱신) | §A.2 분봉 합성·§6-2 V20·§외부 매트릭스 Yahoo 추가 |

### 4-4. 롤백 plan

V20은 **데이터 삭제 + CHECK 추가** 둘 다 — 롤백 위험 매트릭스:

| 시점 | 롤백 가능? | 절차 |
|---|---|---|
| V20 적용 직후 (M1·D1 정상 운영 중) | ✅ CHECK drop만 | `ALTER TABLE candle DROP CONSTRAINT candle_interval_v2_chk` + 일봉 backfill로 D1 채움 + KIS WS 재연결로 M1 재누적 |
| 운영 1주 후 (M1 누적 30만 row 도달) | ⚠️ | DELETE된 M5/M30/H1는 영구 손실. 단 합성으로 동일 결과 응답 가능 — 사용자 영향 0. 롤백 거의 불요. |
| 운영 1개월 후 | ❌ | 운영 데이터 누적 — 정책 v2가 default로 굳음. v3 신규 정책 발행 path. |

운영 검증 절차:
1. V20 staging 적용 → smoke test (`scripts/smoke.sh` candle scenario) PASS 후 main.
2. 1주 모니터링 (`SELECT * FROM candle_v2_summary` daily) — M1 누적 정상 + 합성 응답 정상 확인.
3. v1 candle-data-policy.md → `_workspace/_archive/` 이동 (1주 후 또는 다음 베이스라인 컷 시).

---

## 5. 운영 가이드 (T19 후속 — 정정)

### 5-1. cold-start 절차

신규 종목 차트 진입 시:

```
0초:    api `/api/instruments/{code}/candles?interval=DAY` → D1 즉시 응답 (영속)
0~3초:  api `/api/instruments/{code}/candles?interval=M5&from=now-1d` → M1 query → 합성 → 응답
        cache miss 시 백그라운드 KIS REST FHKST03010200(당일 M1) fetch + INSERT
3~30초: pyworker `POST /backfill/yahoo {code, interval=1m, period=30d}`
        → Kafka market.candle.backfilled.v1 → consumer INSERT (~50K rows / 30일)
        사용자 zoom out 시 Yahoo 누적분 자동 표시
30+초:  사용자 차트 끝 스크롤 → infinite scroll fetch (T19-T6)
        → 더 깊은 과거는 1분봉 부재 (Yahoo 30일 한도) → 그 이전은 D1만 표시
```

### 5-2. 주말·휴장 보강

- **금요일 장 마감 후 cron**: pyworker `BackfillScheduler` → KOSPI200·KOSDAQ150 1분봉 30일 Yahoo 동기 (~23분, 토요일 새벽 02:00 권장)
- **일요일**: M1 갱신 0 (정상)
- **월요일 장 시작 (09:00)**: KIS WS 자동 재연결 + TickToMinuteAggregator 누적 시작

### 5-3. 모니터링

- `GET /api/admin/candle/summary` — interval별 row count·종목 수·oldest·newest·table size
- Prometheus metric (post-MVP): `magicjar_candle_m1_rows_total`, `magicjar_candle_aggregation_latency_seconds`, `magicjar_yahoo_backfill_success_total/failure_total`
- 일일 헬스체크: M1 fresh row 갱신 (장중 분당 ≥1 종목 INSERT 확인)

### 5-4. universe 운영

| 단계 | universe | M1 누적 | 디스크 estimate |
|---|---|---|---|
| MVP | 사용자 watch list 5종목 + 진입 종목 ≤20 | 1년 | ≤300 MB |
| v2 베이스라인 (2026-05) | KOSPI200 + KOSDAQ150 = 350 | 30일 (Yahoo cold-start) + 장중 누적 | ≤2 GB |
| post-MVP | KOSPI 전체 + KOSDAQ 전체 ≈ 2400 | 30일만 | ≤14 GB ⚠️ (TimescaleDB 검토 — master §부수) |

universe 확장 결정 시 사용자 컨펌 의무 (master §외부 매트릭스 정합).

---

## 6. 호환성·legacy 처리

### 6-1. v1과의 호환성

| v1 동작 | v2 동작 | 사용자 영향 |
|---|---|---|
| M5 직접 query 응답 | M1 query → 합성 응답 | **무회귀** — 응답 schema 동일 |
| frontend candleAggregator(M5→M15) | backend 직접 M15 응답 | frontend 코드 단순화·동일 결과 |
| LRU eviction (8GB 임박 시) | M1 LRU 1년 + universe 정책 | 운영 절차 동일 |
| `instrument_cache_meta` LRU | M1·D1만 추적 | 코드 변경 작음 |

### 6-2. v1 폐기 후보 (즉시 X — 운영 1주 후 평가)

- `instrument_cache_meta` interval column → 단일 값(M1) 시 column 폐기 가능 (V21 후보)
- `candleAggregator.aggregateMinuteToN` (frontend) — 즉시 제거
- v1 정책 문서 → `_workspace/_archive/candle-data-policy-v1.md` 이동 (운영 안정 후)

### 6-3. 미래 — TimescaleDB 검토 트리거

- 1분봉 row ≥ 50M 도달 시 (universe 350 × 1년 ≈ 34M row까지는 PostgreSQL 정상)
- TimescaleDB hypertable + chunk 1주 단위 → INSERT 성능·압축 70%↓
- 결정 시점: master §부수 (post-MVP) — 사용자 결정 필요. 본 정책 v2는 vanilla PostgreSQL 기준.

---

## 7. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-03 (v1) | 초기 정책 — 3-tier·LRU·다중 interval 영속 | 사용자 sanity 테스트 중 분봉 부재 발견·HTS 패턴 정합 |
| 2026-05-03 (v2) | **1분봉 단일 영속 + 백엔드 합성 + Yahoo 하이브리드** | 사용자 진단: 정석 quant 패턴 어긋남 + KIS 분봉 API 당일 한정 발견 + 8GB 한도 안 universe 매트릭스 재산정 |

---

## 8. 본 정책 변경 시

본 문서 변경 시 **사용자 컨펌 의무** (master §외부 매트릭스 + §3-5 데이터 정합 직결). 변경 절차:
1. 본 문서 + ADR 신규 발행 (0048 이후)
2. 마이그레이션 plan (V21+) + 롤백 plan
3. master-architecture.md §A.2·§6-2·§외부 매트릭스 동시 갱신
4. CLAUDE.md 변경 이력 + `_workspace/_harness-state.json` 갱신
5. 사용자 사전 승인 후 디스패치

본 v2 변경은 사용자 진단(2026-05-03)에 따른 정정.
