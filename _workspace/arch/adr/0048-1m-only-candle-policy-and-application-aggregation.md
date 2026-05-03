# ADR 0048: 1분봉 단일 영속 + 어플리케이션 레벨 합성 + Yahoo 하이브리드

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.2 · §3-5 · §6-2 · §외부 매트릭스
- 트랙: T2 (Market Data Pipeline) — 정책 v1 정정
- 선행: ADR 0033 (3-tier fallback), ADR 0035 (KIS HTTP 정밀화), ADR 0038 (corp action raw·adjusted), ADR 0039 (IndicatorSource 추상)
- Supersedes: 캔들 정책 v1 (`docs/architecture/candle-data-policy.md` PR #91)

---

## 1. 맥락

**1차안(2026-05-03 PR #91)의 진단**:
- candle 테이블에 `interval='M1'/'M5'/'M30'/'H1'/'D1'` 5개 row를 **각각 별도 영속**
- LRU cache 정책 + 부분 합성(M5→M15, M5→H1, D1→W/MO/Y)
- 이 구조는 정석 quant 플랫폼 패턴 어긋남.

**사용자 진단 (정정)**:
> "1분봉만 가지고 있고 그걸로 각 2분 3분 10분 90분 120분 모든 분봉을 만들어질 수 있게 어플리케이션 레벨로 생각했어 데이터 레벨이 아닌"

**추가 제약 발견 (1번 트랙 dogfooding 2026-05-02)**:
- KIS `FHKST03010200` (분봉 API)은 **당일 전용** — 과거 분봉 backfill 불가
- 모의투자 server는 주말 더 제한적

**용량 시뮬레이션 (8GB host 한도)**:
| universe | 기간 | 1분봉 row | 디스크 |
|---|---|---|---|
| 5 (사용자 watch list) | 60일 | 117K | ≤30 MB |
| 50 | 1년 | 4.9M | ≤800 MB |
| 350 (KOSPI200+KOSDAQ150) | 1년 | 34M | ≤5 GB |
| 350 | 3년 | 102M | ~15 GB ⚠️ 한도 위반 |

**Yahoo Finance 1분봉 PoC (2026-05-03 컨테이너 실측, yfinance 1.3.0)**:
- 1m: 최근 30일 절대 한도 + per-request 8일 (chunk 분할 필수)
- 5m/15m/30m: 최근 60일
- 60m/1h: 최근 2년 (4000+ rows)
- 1d: 10년+ 정상
- KOSPI·KOSDAQ 모두 `.KS` suffix 작동 (`.KQ`는 X)
- burst 30종목 × 1d 1mo = 10.6초 (~3 req/s 안정)
- timezone Asia/Seoul 자동, auto_adjust=False로 raw + Adj Close 양 컬럼 수령 가능

이 모든 정보를 종합해 **1분봉 단일 영속 + 백엔드 합성 + Yahoo cold-start 하이브리드**로 정책 v2 정정.

---

## 2. 결정

### 2-1. 영속 매트릭스 (단일 진실원)

| Interval | 영속 | 합성 |
|---|---|---|
| **D1 (일봉)** | 전종목 10년 영구 영속 — 일봉 SoT | — |
| **M1 (1분봉)** | LRU 1년 영속 — 분봉 SoT (universe ≤350 가정) | — |
| **W / MO / Y** | 영속 X | D1 → W/MO/Y 합성 (frontend 잔존 + backend 옵션) |
| **M2 / M3 / M5 / M10 / M15 / M30 / H1 / M90 / M120 / 임의 N분** | 영속 X | M1 → N분봉 백엔드 합성 |
| **틱** | Kafka retention 1h, 영속 X | — |

PostgreSQL CHECK constraint:
```sql
ALTER TABLE candle ADD CONSTRAINT candle_interval_v2_chk
  CHECK (interval IN ('M1', 'D1'));
```

### 2-2. 백엔드 합성 (CandleAggregator)

위치: `api/src/main/kotlin/com/mj/api/application/market/CandleAggregator.kt`

알고리즘:
- KST 09:00 기준 N분 lock-step bucket grouping
- `open`=첫 bar의 open · `high`=max(high) · `low`=min(low) · `close`=마지막 bar의 close · `volume`=sum(volume)
- adjusted 컬럼 동일 정책 (raw + adjusted 양 컬럼 master §3-5 정합)
- 미완성 마지막 bar 포함 (사용자 진행 중인 봉 표시)

캐시:
- Redis `candle:m1:{code}` (60s 장중 / 1h 장외)
- Redis `candle:agg:{code}:{N}m:{from}-{to}` (60s)
- TickToMinuteAggregator INSERT 직후 evict

### 2-3. 외부 source 하이브리드

| Source | 역할 |
|---|---|
| **KIS WS H0STCNT0** (consumer `TickToMinuteAggregator`) | 장중 실시간 1분봉 누적 |
| **KIS REST FHKST01010400** (batch `DailyCandleBackfillJob`) | 일봉 backfill 1차 |
| **Yahoo Finance** (pyworker `YahooBackfillService`) | 1분봉 cold-start (~30일) + 일봉 보강 |

Yahoo 운영 정책:
- `auto_adjust=False` → raw OHLC + Adj Close 양 컬럼 수령
- 1분봉 30일 cold-start: 7일 chunk × 4~5회 분할 (per-request 한도 정합)
- rate: 호출 사이 0.5초 sleep + 동시 ≤5 ticker
- backoff: 401·429·5xx → 1s/2s/4s × 3
- pyworker → Kafka `market.candle.backfilled.v1` (신규) → consumer INSERT ON CONFLICT DO NOTHING

### 2-4. frontend 합성 폐기 (분봉만)

- `candleAggregator.aggregateMinuteToN`·`aggregateMinuteTo15`·`aggregateMinuteTo30`·`aggregateMinuteToHour` 즉시 제거
- `aggregateDailyToWeekly`·`aggregateDailyToMonthly`·`aggregateDailyToYearly` 잔존 (사용자 zoom out UX 빠름)
- vitest 분봉 케이스 6건 제거 (36 → 30 PASS)

### 2-5. 마이그레이션 (Flyway V20)

```sql
-- V20__candle_policy_v2_m1_d1_only.sql

DELETE FROM candle WHERE interval NOT IN ('M1','D1');
DELETE FROM instrument_cache_meta WHERE interval NOT IN ('M1','D1');

ALTER TABLE candle ADD CONSTRAINT candle_interval_v2_chk
  CHECK (interval IN ('M1','D1'));
ALTER TABLE instrument_cache_meta ADD CONSTRAINT cache_meta_interval_v2_chk
  CHECK (interval IN ('M1','D1'));

CREATE OR REPLACE VIEW candle_v2_summary AS
SELECT interval, count(*) AS rows,
       count(DISTINCT code) AS distinct_codes,
       min(open_time) AS oldest, max(open_time) AS newest,
       pg_size_pretty(pg_total_relation_size('candle')) AS table_size
  FROM candle GROUP BY interval ORDER BY interval;

COMMENT ON CONSTRAINT candle_interval_v2_chk ON candle IS
  'Candle Policy v2 (ADR 0048): M1·D1만 영속. N분봉은 백엔드 합성.';
```

운영 검증 (2026-05-03 production 측정):
- 현재 candle 테이블: D1=278, M1=360, M5=222, M30=61, H1=35 → 318 row 정리, 638 row 보존
- 합성 응답으로 동일 결과 → 사용자 무회귀

### 2-6. 호환성

| 클라이언트 호출 | v1 동작 | v2 동작 | 응답 schema |
|---|---|---|---|
| `interval=DAY` 또는 `D1` | findD1 | findD1 | 동일 |
| `interval=M1` 또는 `1m` | findM1 | findM1 | 동일 |
| `interval=M5` 또는 `5m` | findByCodeAndIntervalAndOpenTimeBetween("M5",...) | findM1 → aggregator.aggregate(_, 5) | 동일 |
| `interval=M30` 또는 `30m` | findM30 | findM1 → aggregator.aggregate(_, 30) | 동일 |
| `interval=H1` 또는 `60m`/`1h` | findH1 | findM1 → aggregator.aggregate(_, 60) | 동일 |
| `interval=90m` 등 임의 N | (지원 X) | findM1 → aggregator.aggregate(_, 90) | 신규 지원 |

사용자 영향 0 — 기존 frontend 코드의 `interval=M5/M30` 호출은 그대로 작동.

---

## 3. 결과·영향

### 3-1. 코드 변경 매트릭스 (5 영역)

- **api**: CandleAggregator·CandleQueryService·CandleRedisCache·InstrumentController·InstrumentControllerTest·CandleAggregatorTest
- **consumer**: TickToMinuteAggregator·CandleBackfilledListener·ConsumerCandleConfig
- **pyworker**: YahooBackfillService·routers/backfill·BackfillScheduler·candle_publisher (Kafka egress) + yfinance 의존
- **frontend**: candleAggregator (분봉 함수 제거)·SymbolDetail·TradingChart·vitest
- **db**: V20 Flyway

### 3-2. 운영 영향

- 디스크: 5GB → ~3GB (M5/M15/M30/H1 영속 폐기)
- KIS API 호출 감소: 종목당 분봉 cold-start 시 다중 interval 호출 → M1 단일 호출 (Yahoo 보강)
- Kafka 신규 토픽: `market.candle.backfilled.v1` (pyworker → consumer)
- Redis 신규 key 패턴: `candle:m1:*`, `candle:agg:*` (master §6-3 갱신)
- pyworker 의존: yfinance 1.3.0 (BSD-3, anonymous public API)

### 3-3. 본체 흐름 영향

- **차트**: 합성 응답 — 사용자 무회귀
- **DSL/screener/signal**: M1 query → 동일 합성 함수 재사용 (master §3-2 정합)
- **백테스트**: M1 LRU 1년 + Yahoo cold-start 30일 = 1년 분봉 backtest 가능 (post-MVP은 일봉 위주)
- **AI 옵션 (W4)**: candle 응답 schema 동일, 영향 0

### 3-4. 외부 source 끊김 시 (master §외부 매트릭스 정합)

- Yahoo 끊김: 자체 누적 분봉만 사용 (KIS WS 활성 시 정상 누적). 본체 영향 0.
- KIS WS 끊김: ADR 0033 fallback (T2 cache · T3 last-day-close). 본체 영향 0.
- 둘 다 끊김: 영속 candle 테이블의 마지막 데이터로 차트·signal·screener 정상 (mock profile MockTickGenerator 대체).

---

## 4. 검토 대안

| 안 | 설명 | 채택? |
|---|---|---|
| **(a) v1 그대로 유지** | M1·M5·M30 다중 영속, LRU cache, 부분 합성 | ❌ 정석 quant 플랫폼 패턴 어긋남 + KIS 분봉 당일 한정 → 5분봉 1년 영속 자체가 운영 비용 ↑ |
| **(b) 1분봉 + 5분봉 양 영속, 그 외 합성** | M5 영속 보존 + 합성 부분 적용 | ❌ 단일 진실원 위반 — 두 영속 간 불일치 위험 (5분 합성 vs 5분 영속 차이) |
| **(c) v2 — 1분봉 + 일봉 단일 영속 + 백엔드 합성 + Yahoo 하이브리드** | M1·D1만 영속, 임의 N분봉 합성, Yahoo cold-start | **✅ 채택** — 단일 진실원·디스크 절약·운영 단순화·정석 quant 정합 |
| **(d) M1만 영속, D1도 합성** | M1로 D1 합성 | ❌ D1 backtest 10년치는 M1 누적 불가 (universe 350 × 10년 = 100GB+). D1 영속은 운영 정합 |
| **(e) 모든 합성을 frontend** | 백엔드 응답 작음 | ❌ DSL/signal/screener는 백엔드에서 평가 — 합성 위치 두 군데 → 코드 중복·실수 회귀 |
| **(f) TimescaleDB 즉시 도입** | hypertable + chunk + 압축 | ❌ universe ≤350 × 1년 = 5GB는 vanilla PostgreSQL 충분. TimescaleDB는 post-MVP universe 확장 시 |

---

## 5. References

- 정책 문서: `docs/architecture/candle-data-policy-v2.md` (전문)
- 디스패치 spec: `docs/superpowers/specs/2026-05-04-candle-architecture-v2-1m-only-and-yahoo.md`
- v1 정책 (deprecated): `docs/architecture/candle-data-policy.md`
- master-architecture.md §A.2 · §3-5 · §6-2 · §외부 매트릭스
- ADR 0033 (3-tier fallback) — Yahoo·KIS WS 끊김 시 fallback path
- ADR 0035 (KIS HTTP 정밀화) — KIS REST 정합
- ADR 0038 (corp action raw + adjusted) — auto_adjust=False 정합
- ADR 0039 (IndicatorSource 추상) — DSL/signal에서 합성 candle 입력 정합
- yfinance 공식 lib (BSD-3): https://github.com/ranaroussi/yfinance
- 1번 트랙 dogfooding (`_workspace/_archive/dogfooding/2026-05-02.md`) — KIS 분봉 당일 한정 발견
- PoC 데이터: `_workspace/tasks/T-20260503-2230-architect.md` (yfinance 1.3.0 컨테이너 실측)

---

## 6. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-03 | 초안 발행 (Accepted) | 사용자 진단: 1분봉 + 어플리케이션 레벨 합성. KIS 분봉 당일 한정 발견 → Yahoo 하이브리드. PoC 검증 완료. |
