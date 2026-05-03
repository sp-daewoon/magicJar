# Spec — 캔들 아키텍처 v2 (1분봉 단일 영속 + 백엔드 합성 + Yahoo 하이브리드)

> **Status**: SPEC v1 (다음 사이클 디스패치 대기)
> **Author**: architect
> **Date**: 2026-05-04 (next session 첫 디스패치 후보)
> **References**:
> - 정책: `docs/architecture/candle-data-policy-v2.md`
> - ADR: `_workspace/arch/adr/0048-1m-only-candle-policy-and-application-aggregation.md`
> - master: `docs/architecture/master-architecture.md` §A.2 · §6-2 · §외부 매트릭스
> - 선행 ADR: 0033 (3-tier fallback), 0035 (KIS HTTP 정밀화), 0038 (corp action raw + adjusted), 0039 (IndicatorSource 추상)
> - PoC 데이터: `_workspace/tasks/T-20260503-2230-architect.md` (yfinance 1.3.0 실측)

---

## §1. 목표

candle 정책 v2를 **5개 영역(backend/pyworker/frontend/db migration/docs)** 분담 구현. 코드·데이터·운영 모두 사용자 무회귀 + 디스크 8GB 안 + 외부 source 끊겨도 본체 동작.

성공 기준 (Definition of Done):
1. `SELECT interval, count(*) FROM candle GROUP BY interval` → **M1·D1 두 줄만**
2. `interval=M5/M15/M30/H1/M90` 등 임의 N분봉 GET 요청 → 백엔드 합성 응답 정상
3. KIS WS tick → consumer TickToMinuteAggregator → 매분 정각 INSERT 검증
4. pyworker `POST /backfill/yahoo {code:"005930", interval:"1m", days:30}` → consumer가 `market.candle.backfilled.v1` 수신 → DB INSERT 멱등
5. CandleAggregator 단위 테스트 PASS (open=첫·high=max·low=min·close=마지막·volume=합)
6. frontend candleAggregator 분봉 합성 함수 제거 + 차트 정상 (vitest 30 PASS, 6 케이스 제거)
7. Flyway V20 적용 + interval CHECK constraint 위반 INSERT 거부
8. 운영 가이드 `_workspace/operations/candle-policy-v2-guide.md` 작성

---

## §2. 작업 분해 (~5~7일치)

### W2-A. backend (api 모듈 — application layer + controller)

#### W2-A-T1. CandleAggregator 신규
**파일**: `api/src/main/kotlin/com/mj/api/application/market/CandleAggregator.kt`
**책임**:
- `aggregate(m1Candles, targetIntervalMinutes, sessionStart=09:00 KST)` → List<Candle>
- 알고리즘: KST 09:00 기준 N분 lock-step bucket grouping → open=첫 bar open · high=max · low=min · close=마지막 bar close · volume=sum
- adjusted 컬럼들 (`closeAdjusted` 등) 동일 합성 정책 (raw + adjusted 양 컬럼 master §3-5 정합)
- 미완성 마지막 bar 포함 (사용자가 진행 중인 봉 보임)
**테스트**: `CandleAggregatorTest.kt`
- 5분봉 합성: 5개 M1 → 1개 M5 정확성 (open/high/low/close/volume + adjusted)
- 60분봉 합성: 60개 M1 → 1개 H1
- 90분봉 합성: 90개 M1 → 1개 (lock-step KST 09:00·10:30 등)
- 미완성 bar (3개 M1만 있는 5분 윈도우) → 1개 M5 (open=첫·close=3번째·high/low 정상)
- 결측 보호 (M1 row 0개 → empty list 반환, exception 없음)

#### W2-A-T2. CandleQueryService 정정
**파일**: `api/src/main/kotlin/com/mj/api/application/market/CandleQueryService.kt`
**변경**:
- 현행 v1: `repo.findByCodeAndIntervalAndOpenTimeBetween("M5", ...)`
- v2: when 분기
  ```kotlin
  when (interval) {
    "D1", "DAY" -> repo.findD1(...)
    "M1", "1m" -> repo.findM1(...)
    matches Regex("M\\d+|H\\d+|\\d+m|\\d+h") -> {
      val n = parseToMinutes(interval)
      aggregator.aggregate(repo.findM1(...), n)
    }
    "W", "MO", "Y" -> aggregator.aggregateDailyToWeekly(repo.findD1(...), interval)
    else -> throw IllegalArgumentException
  }
  ```
- alias 매트릭스: D1↔DAY, M1↔1m, M5↔5m, M15↔15m, M30↔30m, H1↔60m·1h
**테스트**: `InstrumentControllerTest.kt` 정정 7+5 케이스 PASS
- 200 OK · interval=M5 합성 결과 (5분 boundary 정확성)
- 200 OK · interval=M30 합성
- 200 OK · interval=90m 합성
- 400 BAD_REQUEST · interval=M0 / interval=M1500 (out of range)
- D1·DAY alias 정상 동작

#### W2-A-T3. CandleRedisCache (선택 — 1차 PR에서 분리 가능)
**파일**: `api/src/main/kotlin/com/mj/api/infrastructure/cache/CandleRedisCache.kt`
**책임**:
- `candle:m1:{code}` (60s 장중 / 1h 장외) — M1 SoT 캐시
- `candle:agg:{code}:{N}m:{from}-{to}` (60s) — 합성 결과 캐시
- `candle:d1:{code}` (1h) — D1 캐시
- TickToMinuteAggregator INSERT 직후 `candle:m1:{code}` evict
**테스트**: 캐시 hit/miss + invalidation 3 케이스

#### W2-A-T4. InstrumentController interval validation 정정
**파일**: `api/src/main/kotlin/com/mj/api/controller/InstrumentController.kt`
**변경**: interval 정규식 갱신 (M1·D1·DAY·1m·M5·M15·M30·H1·5m·15m·30m·60m·1h·90m·120m·W·MO·Y 모두 허용) + Swagger doc 갱신
- example values 추가 (M1, M5, M30, D1, 90m)

**디스패치**: backend-core sub-agent (sonnet, 1회 escalation 가능 시 opus)

---

### W2-B. consumer (TickToMinuteAggregator — 장중 실시간 1분봉 누적)

#### W2-B-T1. TickToMinuteAggregator 신규
**파일**: `consumer/src/main/kotlin/com/mj/consumer/application/market/TickToMinuteAggregator.kt`
**책임**:
- 종목별 in-memory rolling buffer (`Map<String, MinuteCandleAccumulator>`)
- KisQuoteListener tick → `accumulator.update(tick)` (`high=max(high, price)`, `low=min(low, price)`, `close=price`, `volume += tickVolume`)
- @Scheduled fixedRate=60_000 매 정각 (00초) 직전 윈도우 flush:
  - `INSERT INTO candle (code, interval='M1', open_time, open, high, low, close, volume, ...) ON CONFLICT DO NOTHING`
  - Kafka `market.candle.v1` 발행 (master §6-4 정합)
  - 새 윈도우 시작
- 결측 처리: tick 0 종목은 INSERT 안 함 (휴장·세션 밖 정합)
- session=Closed 시 윈도우 진행 중단 (MarketSessionFsm 정합 — ADR 0032)
**의존**: `KisQuoteListener` (현행), `CandleJpaPort` (master §6-2 V14 정합), `TickPublisher → market.candle.v1`
**테스트**: `TickToMinuteAggregatorTest.kt`
- 정확성: 60 tick → 1개 M1 (open=첫·close=마지막·volume=합)
- 정각 flush 시점: 09:00:00 → 09:01:00 boundary
- 결측: tick 0 → INSERT 안 함
- session=Closed: 윈도우 무시
- 멱등: 동일 (code, interval, open_time) 재 INSERT → ON CONFLICT DO NOTHING 검증

#### W2-B-T2. ConsumerCandleConfig — Kafka producer 추가
**파일**: `consumer/src/main/kotlin/com/mj/consumer/config/ConsumerCandleConfig.kt`
**책임**: `market.candle.v1` topic producer Bean 등록 (현행 TickPublisher 패턴 답습)

#### W2-B-T3. CandleBackfilledListener — Yahoo egress 수신
**파일**: `consumer/src/main/kotlin/com/mj/consumer/application/market/CandleBackfilledListener.kt`
**책임**:
- Kafka `market.candle.backfilled.v1` (pyworker가 발행) 수신
- payload: `{code, interval='M1'|'D1', candles: [{open_time, open, high, low, close, volume, adjustment_factor, close_adjusted, ...}]}`
- 검증: interval IN ('M1','D1') (정책 v2 정합)
- INSERT ON CONFLICT DO NOTHING (멱등)
- 100 batch 단위 transaction
**테스트**: `CandleBackfilledListenerTest.kt` (EmbeddedKafka)
- 1분봉 30일 30K row INSERT 시간 측정
- 동일 payload 재수신 → 중복 0 (멱등 검증)
- interval='M5' payload → reject (v2 위반)

**디스패치**: market-data sub-agent (sonnet)

---

### W2-C. pyworker (Yahoo Finance backfill)

#### W2-C-T1. yfinance 의존 추가
**파일**: `pyworker/pyproject.toml` + `uv.lock`
**변경**: `yfinance>=1.3.0` 추가 + `uv lock` 후 commit (host에서 실행)

#### W2-C-T2. YahooBackfillService 신규
**파일**: `pyworker/app/backfill/yahoo_service.py`
**책임**:
- `fetch_candles(code: str, interval: str, period: str | None, start: date | None, end: date | None) -> list[dict]`
- yfinance Ticker(`{code}.KS`).history() 호출 (`auto_adjust=False` → raw + Adj Close 양 컬럼)
- 1분봉 30일 fetch는 7일 chunk 분할 (yfinance per-request 제한)
- timezone: KST 보존 (Yahoo가 자동 Asia/Seoul tz 부여)
- exponential backoff: 401·429·5xx → 1s → 2s → 4s, 3회
- rate limit: 호출 사이 0.5초 sleep
- 결측: 휴장일 row 0 (정상)
**테스트**: `tests/test_yahoo_service.py`
- 005930.KS 1m 7d → ≥1500 rows (KOSPI 1.5h 30분 거래일·휴일 제외)
- 005930.KS 1d 10y → ≥2000 rows
- 091990.KS (KOSDAQ 종목) `.KS` suffix 정상
- chunk 분할 정확성 (start/end 분할 후 결과 concat)
- 401 mocking → 3회 재시도 후 실패

#### W2-C-T3. POST /backfill/yahoo endpoint
**파일**: `pyworker/app/routers/backfill.py`
**API**:
```
POST /backfill/yahoo
{
  "codes": ["005930", "000660"],
  "interval": "1m" | "1d",
  "days": 30,                    // interval='1m'은 30 한도
  "publish_kafka": true          // false면 응답에 candles 직접 포함 (디버그)
}
→ 202 Accepted
{
  "task_id": "uuid",
  "estimated_seconds": 120,
  "topic": "market.candle.backfilled.v1"
}
```
**책임**: BackgroundTask로 실행 + Kafka egress (aiokafka)

#### W2-C-T4. BackfillScheduler (APScheduler cron)
**파일**: `pyworker/app/scheduler/backfill_scheduler.py`
**cron**:
- 토요일 02:00 KST: KOSPI200·KOSDAQ150 1분봉 30일 보강 (~23분)
- 일요일 02:00 KST: 일봉 (1d) 1년 보강 (~120초)
- 평일 16:00 KST: 당일 일봉 (KIS REST 결측 보강)
**환경변수**: `MAGICJAR_PYWORKER_BACKFILL_ENABLED` default false (mock profile은 false)

#### W2-C-T5. Kafka egress 스키마 정합
**파일**: `pyworker/app/messaging/candle_publisher.py`
**책임**:
- `market.candle.backfilled.v1` 발행
- payload schema: Kotlin 측 `CandleBackfilledEvent`와 camelCase·Type-Id 헤더 정합 (선행 패턴 — `news.crawled.v1` 답습)
- batch 100 candles per message (10000 row × 30 batch)

**디스패치**: ai-engineer sub-agent (opus, Python + Kafka schema 정합 복잡도) 또는 pyworker 영역 일반화 시 sonnet

---

### W2-D. db migration

#### W2-D-T1. Flyway V20
**파일**: `api/src/main/resources/db/migration/V20__candle_policy_v2_m1_d1_only.sql`
**SQL**: 정책 §4-2 (전문 그대로)
- DELETE M5/M15/M30/H1 (현 318 row → 0)
- DELETE instrument_cache_meta non-(M1,D1)
- ALTER ADD CONSTRAINT candle_interval_v2_chk CHECK (interval IN ('M1','D1'))
- ALTER ADD CONSTRAINT cache_meta_interval_v2_chk
- CREATE OR REPLACE VIEW candle_v2_summary
- COMMENT ON CONSTRAINT 명시 (운영 가시성)

**검증**:
- `SELECT * FROM candle_v2_summary` → M1·D1 두 행만
- `INSERT INTO candle (..., interval='M5', ...)` → 거부 (CHECK 위반)
- 기존 코드 (CandleQueryService) backward 정상

**디스패치**: backend-core sub-agent (sonnet) 또는 main 직접

---

### W2-E. frontend (분봉 합성 함수 제거)

#### W2-E-T1. candleAggregator.ts 정정
**파일**: `frontend/src/lib/candleAggregator.ts`
**제거**: `aggregateMinuteToN`·`aggregateMinuteTo15`·`aggregateMinuteTo30`·`aggregateMinuteToHour` (v1 PR #45·#103 추가)
**잔존**: `aggregateDailyToWeekly`·`aggregateDailyToMonthly`·`aggregateDailyToYearly` (사용자 zoom out UX 빠름)

#### W2-E-T2. SymbolDetail / TradingChart 정정
**파일**: `frontend/src/pages/SymbolDetail/index.tsx`, `frontend/src/components/chart/TradingChart.tsx`
**변경**: interval=M5/M15/M30/H1 호출 시 backend 직접 응답 (클라 합성 폐기) — types.ts·client.ts 변경 0

#### W2-E-T3. vitest 정정
**파일**: `frontend/src/lib/__tests__/candleAggregator.test.ts`
**제거**: 분봉 합성 케이스 6건 (M1→M5·M5→M15·M5→M30·M5→H1 등)
**잔존**: D1→W·D1→MO·D1→Y 케이스 4건
**검증**: vitest 30 PASS (현 36 → 6 제거)

**디스패치**: frontend sub-agent (sonnet)

---

### W2-F. docs

#### W2-F-T1. 운영 가이드
**파일**: `_workspace/operations/candle-policy-v2-guide.md`
**내용**:
- cold-start 절차 (정책 v2 §5-1)
- 주말 보강 cron (정책 v2 §5-2)
- 모니터링 SQL 쿼리·Prometheus metric (post-MVP)
- universe 운영 정책 (정책 v2 §5-4)
- 트러블슈팅: Yahoo 401·429·KIS WS 끊김·CHECK 위반 INSERT 거부

#### W2-F-T2. master-architecture.md 갱신 (선택)
**파일**: `docs/architecture/master-architecture.md`
**변경**:
- §A.2 분봉 합성 → "백엔드 CandleAggregator + Yahoo cold-start" 명시
- §6-2 V14 → V20 추가 (CHECK constraint·합성 정책)
- §외부 매트릭스 → Yahoo Finance 행 추가 (옵션·끊겨도 본체 영향 0)
- §13 변경 이력에 v2 정책 한 줄 추가

#### W2-F-T3. CLAUDE.md 변경 이력 1줄
**파일**: `CLAUDE.md`
**추가**: 2026-05-04 (또는 머지 일자) 행 — "**candle 정책 v2** — 1분봉 단일 영속 + 백엔드 합성 + Yahoo 하이브리드. ADR 0048 + V20 마이그레이션 + 5 영역 분담 구현."

**디스패치**: architect (메인) — docs는 architect 책임

---

## §3. 의존성 그래프

```
                ┌─────────────┐
                │ V20 마이그   │ ← W2-D-T1 (가장 먼저)
                │ (DELETE +    │
                │  CHECK)      │
                └──────┬───────┘
                       │
         ┌─────────────┼─────────────┬─────────────┐
         ▼             ▼             ▼             ▼
   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
   │W2-A      │  │W2-B      │  │W2-C      │  │W2-E      │
   │backend   │  │consumer  │  │pyworker  │  │frontend  │
   │Aggregator│  │TickToMin │  │Yahoo     │  │분봉 합성  │
   │QuerySvc  │  │Aggregator│  │backfill  │  │함수 제거  │
   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
        │             │             │             │
        │             ▼             ▼             │
        │     ┌──────────────┐  ┌──────────────┐  │
        │     │CandleBackfill│  │BackfillSched │  │
        │     │Listener (B-T3)│  │ (C-T4)        │  │
        │     │수신·INSERT    │  │APScheduler    │  │
        │     └──────┬───────┘  └──────────────┘  │
        │            │                             │
        └────────────┴─────────────────────────────┘
                            │
                            ▼
                    ┌─────────────┐
                    │W2-F docs    │
                    │운영 가이드    │
                    │master 갱신  │
                    └─────────────┘
```

**병렬화**:
- W2-A·W2-B·W2-C·W2-E 모두 W2-D 머지 후 병렬 가능 (영역 분리)
- W2-A 안에서도 T1·T2·T3·T4 순차 (T1 → T2 → T4, T3는 선택 분리)
- W2-C 안에서도 T1 → T2 → T3 → T4 → T5 순차

---

## §4. 디스패치 권장

| 영역 | sub-agent | 모델 | 토큰 추정 | 체크리스트 의존 |
|---|---|---|---|---|
| W2-D (V20 SQL) | architect or backend-core | sonnet | 작음 (~3K) | 가장 먼저 — main 직접 가능 |
| W2-A (backend) | backend-core | sonnet (1회 escalation 시 opus) | 중간 (~30K) | W2-D 후 |
| W2-B (consumer) | market-data | sonnet | 중간 (~25K) | W2-A 또는 병렬 |
| W2-C (pyworker) | ai-engineer 또는 backend-core | opus (Kafka 정합·Python·yfinance 복잡) | 큼 (~50K) | 독립 가능 |
| W2-E (frontend) | frontend | sonnet | 작음 (~10K) | W2-A의 응답 schema 정합 후 |
| W2-F (docs) | architect (main) | (직접) | 작음 | 모든 영역 머지 후 |

**총 예상 토큰**: ~120K (1주 분량) + architect 메타 ~20K = **~140K**

**머지 순서**:
1. PR #1: W2-D (V20 + 정책 v2 docs 1차 갱신) — main 직접
2. PR #2: W2-A + W2-B (backend Aggregator + consumer TickToMin) — backend-core
3. PR #3: W2-C (pyworker yfinance) — ai-engineer
4. PR #4: W2-E (frontend 정정) — frontend
5. PR #5: W2-F (운영 가이드 + master 갱신) — architect

---

## §5. QA 트리거 (ADR 0016)

| 트리거 | 적용 |
|---|---|
| #1 (보안 경계) | ❌ — 데이터 정책만, 인증 무관 |
| #2 (외부 키·token) | ❌ — Yahoo는 키 불요 (anonymous public API) |
| #3 (마이그레이션 회귀) | ✅ — V20 DELETE + CHECK 추가 |
| #4 (UI 변경) | ✅ — frontend candleAggregator 제거·차트 표시 회귀 가능 |
| #5 (시세 흐름) | ✅ — TickToMinuteAggregator 신규 (장중 분당 INSERT) |
| #6 (smoke 영역 흐름) | ⚠️ — backtest·screener·signal 모두 candle 의존 |
| #7 (사용자 보고 회귀) | ⚠️ — 사용자 진단 정정 |

**판정**: 트리거 ≥3 → QA 호출 의무. 머지 #5 전 통합 QA 풀 게이트 (PR #4까지 머지 후 5 시나리오 + 사용자 검증).

---

## §6. 리스크 + 완화

| 리스크 | 시나리오 | 완화 |
|---|---|---|
| **R1. Yahoo 차단** | yfinance 사용량 급증 시 IP 차단 | 0.5s sleep + ≤5 동시 + 운영 모니터링·alert. 차단 시 KIS 일봉 단일 fallback (1d만) — 본체 영향 0. |
| **R2. KIS WS 끊김 1분 이상** | TickToMinuteAggregator 윈도우 손실 | KIS REST `FHKST03010200` 당일 분봉 보강 + Yahoo 다음 보강 사이클. 짧은 끊김(<1분)은 결측 1봉만 — UI 표시 가능 (회색 마커). |
| **R3. 합성 결과 차이** | backend 합성 vs frontend (D1→W) 차이 | D1→W는 frontend 잔존 (사용자 zoom out 빠름). N분봉은 backend 단일. 일관성은 backend 합성을 기준으로 frontend D1→W 알고리즘 동기화. |
| **R4. universe 8GB 초과** | KOSPI 전체(2400 종목) 1년 진입 | universe 정책 v2 §5-4 명시 (MVP 200·v2 350·post-MVP 2400은 TimescaleDB 검토). 사용자 명시 컨펌. |
| **R5. CHECK constraint 위반** | 기존 코드의 잔존 INSERT (배치 잡 등) | V20 적용 전 grep + Flyway repeatable validation. 위반 시 명확한 SQL 에러 → 즉시 수정. |
| **R6. 사용자 차트 회귀** | M5 직접 응답 vs 합성 응답 차이 | InstrumentControllerTest 정정 + smoke scenario 추가. 사용자 dogfooding 1주 모니터링. |

---

## §7. 비범위 (post-MVP / v3 후속)

- **TimescaleDB 전환** — universe 확장 시 검토. 본 v2는 vanilla PostgreSQL.
- **W/MO/Y 백엔드 합성** — frontend candleAggregator 잔존으로 보류. 사용자 zoom UX 빠름이 우선.
- **틱 영속** — 정책 v2도 영속 X (Kafka 1h retention only). 틱 영속은 별도 트랙(고빈도 quant 진입 시).
- **압축** — PostgreSQL TOAST 자동 압축으로 충분. 명시 압축은 TimescaleDB 검토 시.
- **외국인·기관 매수 데이터** — T20 (종목 검색·메타) 이미 별도 트랙. candle 정책 무관.

---

## §8. 머지 후 검증 체크리스트 (사용자 dogfooding)

- [ ] `docker compose build api consumer pyworker frontend && up -d` → 9 컨테이너 healthy
- [ ] `psql -c "SELECT * FROM candle_v2_summary;"` → M1·D1 두 줄
- [ ] `curl http://localhost:8080/api/instruments/005930/candles?interval=M5&from=2026-05-01&to=2026-05-04` → 합성 OHLCV 응답 정상
- [ ] frontend localhost:3000/symbol/005930 → 차트 정상 (5분·30분·60분·일봉 토글 모두 정상)
- [ ] 장중 (09:00~15:00 KST): consumer 로그에 매분 정각 `TickToMinuteAggregator flushed N codes` 확인
- [ ] `curl -X POST http://localhost:8001/backfill/yahoo -d '{"codes":["005930"],"interval":"1m","days":30}'` → 202 + Kafka egress + DB INSERT 검증 (`SELECT count(*) FROM candle WHERE code='005930' AND interval='M1'` 갱신)
- [ ] `INSERT INTO candle (code, interval, ...) VALUES ('TEST','M5',...)` → CHECK 위반 거부

---

## §9. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-03 | 초기 spec | architect — 캔들 정책 v2 디스패치 준비 |
