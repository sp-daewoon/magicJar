# ADR 0028 — Watchlist 기반 Ingestion Worker

- 상태: Accepted
- 작성일: TBD
- 2026-05-11 갱신: **ADR 0057 (KIS-only data source) 정합** — §2-1·§2-3 Yahoo cold-start 인용을 KIS REST cold-start으로 재정의. 5m·15m·30m·90m·1h cold-start은 backlog §31 트랙(1m 합산 가공 방향 확정) 완료 전까지 0. credential 정책: 정보 수집은 prod 단일 채널 (모의/실전 분기는 주문 라운터 한정).
- 2026-05-12 갱신 (v3.2.3 doc cut — ADR 0062·0063 Accepted 재통합): **§2-1 cold-start 분기 5m·15m·30m·90m·1h 합산 트랙 활성화** (ADR 0062 §3 권장안 A-1 백엔드 precomputed 채택), **§2-3 흐름에 KOSDAQ market 분기·halt/liquidation skip·AggregateBackfillRunner 호출 명시** (PR #303·#304 구현), `ColdStartExecutor.KIS_COLD_START_INTERVALS` 정합 (5종 — 1m·1d·1wk·1mo·1y).
- 결정자: architect
- 관계: **ADR 0057(KIS-only data source) — cold-start KIS REST 단일 출처**, **ADR 0062(5m~1h 합산 가공 백엔드 precomputed — 2026-05-11 Accepted)**, **ADR 0063(NXT/통합거래 cover — 2026-05-11 Accepted)**, ADR 0019(Accepted — Yahoo 의존 부분만 보류, 2026-05-12 §2 NXT/합산 5종 재통합), ADR 0029(당일 진행 중 캔들 Redis 캐시 — flushAggregations + boundary 5종 키), ADR 0037(KIS Rate Limit), ADR 0040(관심종목 우선 UI 흐름), **ADR 0048(KIS WS 41 slot + priority eviction + polling fallback — 본 ADR §2-2 풀 size 한도의 1급 권위)**

## 1. 컨텍스트

magicJar의 모든 외부 시세 수집은 **사용자가 등록한 관심종목(Watchlist) 풀**을 기준으로 한다. 풀 외 종목은 수집하지 않는다. 2026-05-11 ADR 0057 발효 이후 **데이터 출처 = KIS OpenAPI 단일**.

근거:
- KIS Rate Limit (초당 10건, ADR 0037) 안에서 의미 있는 universe = 사용자가 실제 보는 종목
- 디스크 한도 — 분봉 영속이 universe size에 비례 (ADR 0004)
- 사용자 흐름 일관성 — 관심종목만 데이터·차트·시그널이 보임

## 2. 결정

### 2-1. WatchlistIngestionWorker (consumer 모듈 신규)

| 항목 | 정책 |
|---|---|
| **위치** | `consumer/.../ingestion/WatchlistIngestionWorker.kt` |
| **polling 주기** | **1~5초** (사용자 path 우선순위에 따라 동적) |
| **KIS WS subscribe 종목** | = Watchlist 풀 (실시간 동기) |
| **KIS REST 호출** | `KisRateLimiter` (ADR 0037) 큐 통과 의무 |
| **풀 변경 반영** | 추가 즉시 subscribe + **KIS REST cold-start fetch + AggregateBackfillRunner 합산 INSERT** (ADR 0057·0062·0063, prod credential) / 삭제 즉시 unsubscribe |
| **풀 진입 시 cold-start (5종 + 합산 5종)** | `ColdStartExecutor.KIS_COLD_START_INTERVALS = [1m·1d·1wk·1mo·1y]` (PR #303 구현) — KIS REST `FHKST03010230` (1m, 30 거래일) + `FHKST03010100` (1d·1wk·1mo·1y, prod credential 단일 채널). **1m 적재 직후 `AggregateBackfillRunner.backfill(code)` 호출 — 5m·15m·30m·90m·1h 일괄 합산 INSERT** (ADR 0062 §3 권장안 A-1 백엔드 precomputed 채택). 합산 결과는 `audit_trail.detail_json.aggregateInserted` 필드 기록 |
| **halt/liquidation skip** | `instrument.tradingHalt = true` 또는 `liquidation = true` 종목 → `KIS_COLD_START_SKIPPED` audit + early return (PR #304 구현 — `ColdStartExecutor.runColdStart` early exit). KIS API 외부 결함 (응답 0건) 재시도 무의미 |
| **market 분기** | KOSPI → `fid_cond_mrkt_div_code="UN"` (NXT 통합 cover 08:00~20:00 KST, ADR 0063) / KOSDAQ → `"J"` (NXT 미상장 — UN 빈 응답 회피, PR #304) / `UNKNOWN/기타` → `"J"` (안전 기본값) |
| **장중 당일 분봉** | KIS REST `FHKST03010200` + KIS WS → Redis 갱신 (prod credential). 1m은 당일 한정 Redis & DB. 5m·15m·30m·90m·1h은 boundary 마감 시 `TickToMinuteAggregator` 합산 → Redis 갱신 (ADR 0029 §2-1 키 5종). 일봉은 장 마감(20:00 KST) 후 DB INSERT (`DailyCloseFinalizer`, ADR 0029) |

### 2-2. 풀 size 한도

| 단계 | 풀 size | 비고 |
|---|---|---|
| MVP | ≤ 30 종목 | KIS WS 동시 구독 한도 정합 |
| 베이스라인 | ≤ 100 종목 | 풀 분할 + WS 다중 연결 (post-MVP) |
| post-MVP | 무제한 | DB·Rate Limit·WS 한도 검토 후 |

KIS WS 41건 한도 안에서 자연 제약. 풀 size 30 초과 시 우선순위(최근 진입·시그널 활성)로 자동 sub 토글.

### 2-3. Watchlist 변경 → Ingestion 동기화

> **2026-05-12 갱신 (ADR 0062·0063 Accepted 재통합)**: 흐름 갱신 — KOSDAQ market 분기 + halt/liquidation skip + AggregateBackfillRunner 호출 + 1m FHKST03010230 30 거래일 페이지네이션 명시. PR #303·#304 구현 결과 인용.

```
사용자 [관심등록 클릭] (frontend /watchlist)
    ↓
api POST /api/watchlists  (body: {code})
    ↓
DB INSERT watchlist + Kafka publish watchlist.changed.v1
    ↓
consumer WatchlistChangedKafkaListener → ColdStartExecutor.submit(code) (PR #303 분기)
    ├─ instrument 조회 (halt/liquidation 검사 — PR #304)
    │     ├─ tradingHalt=true 또는 liquidation=true
    │     │   └─ KIS_COLD_START_SKIPPED audit + early return (재시도 무의미)
    │     └─ 정상 → 진행
    ├─ market 분기 → fid_cond_mrkt_div_code 결정 (PR #304):
    │     ├─ KOSPI → "UN" (NXT 통합 08:00~20:00 KST, ADR 0063)
    │     └─ KOSDAQ → "J" (NXT 미상장 → UN 빈 응답 회피)
    ├─ KIS WS subscribe(code) (prod credential, H0STCNT0 체결가 + 분봉)
    ├─ KIS_COLD_START_INTERVALS 5종 fetch (prod credential 단일 채널):
    │     ├─ 1m  : FHKST03010230 — 30 거래일 페이지네이션 → DB INSERT
    │     ├─ 1d  : FHKST03010100 (period='D') — 200건 → DB INSERT
    │     ├─ 1wk : FHKST03010100 (period='W') — 200건 → DB INSERT
    │     ├─ 1mo : FHKST03010100 (period='M') — 120건 → DB INSERT
    │     └─ 1y  : FHKST03010100 (period='Y') — 10건 → DB INSERT
    ├─ AggregateBackfillRunner.backfill(code) (ADR 0062 §3 권장안 A-1):
    │     ├─ 1m 전량 fetch (CandleRepository.findByCodeAndInterval)
    │     ├─ CandleAggregator.aggregate(1m, target) × 5종 in-memory 합산
    │     │   ├─ 5m boundary (144 bar/일 NXT cover)
    │     │   ├─ 15m boundary (48 bar/일)
    │     │   ├─ 30m boundary (24 bar/일)
    │     │   ├─ 90m boundary (8 bar/일)
    │     │   └─ 1h boundary (12 bar/일)
    │     └─ candle table ON CONFLICT DO NOTHING (멱등 INSERT)
    └─ audit_trail 저장:
          ├─ KIS_COLD_START_DONE (failCount=0)
          ├─ KIS_COLD_START_PARTIAL_FAIL (success>0, fail>0)
          ├─ KIS_COLD_START_FAILED (success=0)
          └─ detail_json.aggregateInserted 필드 추가
    ↓
사용자 차트·시그널·screener 즉시 활성 (10종 interval — 1m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y)
```

**raw 출처 인용 (헌법 §3)**: `inquire_time_itemchartprice` (FHKST03010230, 1m 페이지네이션) + `inquire_daily_itemchartprice` (FHKST03010100, 1d·1wk·1mo·1y 100건/페이지). prod credential 단일 (`AccountType.REAL`).

## 3. 결과

- **KIS REST·WS 부하 예측 가능** (풀 size 기반)
- **사용자 흐름 일관성** — 관심종목만 데이터 보임
- **디스크 한도 자연 제한** (universe = 풀 size)
- **Rate Limit·WS 한도 안에서 자연스러운 polling 주기** (1~5초)

## 4. 참고

- **ADR 0057 — KIS OpenAPI 단일 데이터 출처 (cold-start KIS REST 단일)** ← 본 ADR §2-1·§2-3 권위 출처
- **ADR 0062 — 5m~1h 합산 가공 백엔드 precomputed (2026-05-11 Accepted)** ← `AggregateBackfillRunner` 호출 권위 인용
- **ADR 0063 — NXT/통합거래 매트릭스 cover (2026-05-11 Accepted)** ← market 분기 + 08:00~20:00 KST cover 권위 인용
- ADR 0019 — 캔들 저장 정책 (Accepted, 2026-05-12 §2 NXT/합산 5종 재통합)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (5m~1h boundary 키 5종 + 20:00 KST DailyCloseFinalizer)
- ADR 0037 — KIS API Rate Limit 강제 (초당 10건)
- ADR 0040 — 관심종목 우선 UI 흐름 (`docs/architecture/master-architecture.md` §B.A)
- ADR 0048 — KIS WS 41 slot + polling fallback (풀 size 자연 한도)
- `domain/.../market/watchlist/` — port 정의
- `domain/.../market/CandleAggregator.kt` — ADR 0062 §3 권장안 도메인 본체 (Pure Kotlin)
- `consumer/.../ingestion/ColdStartExecutor.kt` — 본 ADR §2-3 구현체 (PR #303·#304)
- `consumer/.../aggregation/AggregateBackfillRunner.kt` — ADR 0062 §3 권장안 합산 trigger (PR #303)
- `consumer/.../kafka/WatchlistChangedKafkaListener.kt` — Kafka consumer 진입점
- PR #303 — `AggregateBackfillRunner` + `ColdStartExecutor` 합산 확장
- PR #304 — KOSDAQ market_div J 분기 + halt/liquidation skip
