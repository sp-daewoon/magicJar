# 캔들 데이터 정책

- 작성일: TBD
- 2026-05-11 갱신: **ADR 0057 KIS-only data source 정합** — Yahoo Finance 전면 폐기. 본 문서 §3·§4·§5·§6 매트릭스 KIS-only로 재정의.
- 2026-05-11 amend: ADR 0019 supersede 인용 제거 → **Accepted (Yahoo 의존 부분만 보류)**. 5m~1h은 **1m 합산 가공 방향 확정** (backlog §31 트랙 — 위치·source 결정 대기). credential 정책: 정보 수집은 prod 단일 채널 (모의/실전 분기는 주문 라우터 한정).
- 2026-05-11 amend: **ADR 0062 plan 발행** — 5m·15m·30m·90m·1h 합산 가공 위치(권장안 A-1 백엔드 precomputed) + source 시간 범위(권장안 옵션 2 과거 포함 30 거래일) + IntervalToggle 5종 활성화(15m·90m 추가) plan 발행. 사용자 결정 후 본 문서 매트릭스 5m~1h 행을 "1m 합산 가공 예정" → "1m 합산 가공 (백엔드 precomputed)" 갱신 예정.
- 2026-05-12 갱신 (v3.2.3 doc cut — backlog §35): **§3 매트릭스 5m~1h 행 "1m 합산 가공 (백엔드 precomputed)" 갱신** (ADR 0062 Accepted 2026-05-11) + **§3·§6 매트릭스에 NXT 통합 시각 cover 08:00~20:00 KST 통합** (ADR 0063 Accepted) + **§4 IntervalToggle 5종 활성화 갱신** + **§6 cold-start 흐름 AggregateBackfillRunner 호출 인용** (PR #303) + **§7 당일 진행 중 캔들 흐름 20:00 KST DailyCloseFinalizer 갱신** (PR #303) + **mock 합성 금지 정합** (`feedback_no_mock_data.md` 인용).
- 2026-05-20 갱신 (ADR 0091): **KOSPI=UN/KOSDAQ=J market-level 분기 폐기**. NXT 후보 종목(`is_kospi200` 또는 `is_kosdaq150`)은 `UN` 우선 + `J` fallback, 그 외는 `J` 기본값.
- 2026-05-31 갱신: **3m·4h·12h 합산 interval 정규 편입 + 사용자 재수집(recollect) path 추가**. 스크리닝은 야간 전용이 아니라 사용자가 언제든 실행하는 기능이며, 1차 broad screening 기본 기준봉은 1d, 2차/사용자 선택 기준봉은 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y 중 선택 가능. 잘못 모인 캔들은 mock 보정 없이 DB row 삭제 후 KIS 원천 재수집, 합산봉은 1m 재수집 후 `CandleAggregator` 재생성.
- 본 문서는 운영 가이드. 결정 본문은 **ADR 0057 (KIS OpenAPI 단일 데이터 출처)** + **ADR 0062 (합산 interval 백엔드 precomputed)** + **ADR 0063 (NXT/통합거래 cover)** + **ADR 0019 (Accepted, 2026-05-12 §2 재통합)** + **ADR 0028 (Watchlist Ingestion)** + **ADR 0029 (당일 진행 중 캔들 Redis 캐시)** + **ADR 0048 (KIS WS 41 slot + polling fallback)** 참조.

## 1. 정책 한 줄 요약

> **KIS OpenAPI 단일 출처 (prod credential 단일 채널). NXT 통합 cover 08:00~20:00 KST (`UN`은 종목 단위 후보만, `J` fallback). DB 영속 = Watchlist 풀 내 종목의 1m(30 거래일) + 3m·5m·15m·30m·90m·1h·4h·12h(백엔드 precomputed 합산) + 1d·1wk·1mo·1y. Yahoo Finance 폐기. Mock 합성 금지 (`feedback_no_mock_data.md`).**

## 2. 핵심 원칙

1. **KIS OpenAPI 단일 출처** — Yahoo Finance·네이버 금융 등 보조 출처 폐기 (ADR 0057, 사용자 결정 2026-05-11)
2. **정보 수집 = prod credential 단일 채널** — 모의/실전 분기는 주문 라우터 한정 (사용자 명시 정책 정정 2026-05-11)
3. **Watchlist 기반 universe** — Watchlist 풀 외 종목은 수집·저장 안 함 (ADR 0028)
4. **DB가 진실원** — 1m(30 거래일) + 3m·5m·15m·30m·90m·1h·4h·12h(백엔드 precomputed) + 1d·1wk·1mo·1y 영속. Redis는 당일 진행 중 OHLCV만
5. **LRU 폐기** — 풀 size가 자연 universe 한도
6. **3m·5m·15m·30m·90m·1h·4h·12h = 백엔드 precomputed 합산 (ADR 0062 §3 권장안 A-1 확장)** — `CandleAggregator` 도메인 본체(Pure Kotlin) + `AggregateBackfillRunner` cold-start trigger. MCP·룰엔진·백테스트·스크리너 동일 source 정합
7. **NXT 통합 cover 08:00~20:00 KST (ADR 0063·0091)** — `fid_cond_mrkt_div_code` 분기 (`is_kospi200`/`is_kosdaq150` 후보는 `UN` 우선 + `J` fallback, 그 외 `J`). 1m 베이스 720 bar/일 (NXT pre/after-hour 포함). `MIN_1M_CANDLE_THRESHOLD = 700L`
8. **Mock 합성 금지** — backend 0건/에러 시 "데이터 없음" 명시 표시 (사용자 메모리 `feedback_no_mock_data.md` — 2026-05-11 dogfooding 사용자 결정)

## 3. 데이터 매트릭스 (ADR 0057·0062·0063 정합)

> **2026-05-12 갱신 (v3.2.3 doc cut)**: 5m~1h 행 "백엔드 precomputed 합산" 채택안 명시 + NXT cover 시각 통합 매트릭스 + KOSPI/KOSDAQ market 분기 명시.

| Interval | Source (KIS tr_id) | Cache | DB 영속 | 시각 cover | TTL/만료 |
|---|---|---|---|---|---|
| **틱** | KIS WS `H0STCNT0` | Redis (휘발) | X | NXT 통합 08:00~20:00 KST | retention 1h |
| **1m (1분봉) — 당일** | KIS REST `FHKST03010200` (NXT 후보=`UN`→`J`, 그 외=`J`) | Redis (당일) | DB INSERT (분 단위 closing) | NXT 통합 08:00~20:00 KST (720 bar/일) | Redis: 다음날 00:00 evict |
| **1m (1분봉) — 30 거래일 페이지네이션** | KIS REST `FHKST03010230` (NXT 후보=`UN`→`J`, 그 외=`J` — ADR 0091) | Redis (당일) | **DB 영속 (30 거래일)** | NXT 통합 08:00~20:00 KST | — |
| **3m·5m·15m·30m·90m·1h·4h·12h** | **백엔드 precomputed 합산 (ADR 0062 §3 권장안 A-1 확장)** — `CandleAggregator` 도메인 본체 + `AggregateBackfillRunner` cold-start trigger | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일)** | KRX 정규장 09:00~15:30 KST boundary 정렬 | Redis: 마감 시 evict + DB INSERT |
| **1d (일봉)** | KIS REST `FHKST03010100` (period='D', NXT 후보=`UN`→`J`, 그 외=`J`) | Redis cache (옵션) | **DB 영속** | NXT 종가 통합 (20:00 KST 마감 기준) | — |
| **1wk (주봉)** | KIS REST `FHKST03010100` (period='W', NXT 후보=`UN`→`J`, 그 외=`J`) | Redis cache (옵션) | **DB 영속** | — | — |
| **1mo (월봉)** | KIS REST `FHKST03010100` (period='M', NXT 후보=`UN`→`J`, 그 외=`J`) | Redis cache (옵션) | **DB 영속** | — | — |
| **1y (년봉)** | KIS REST `FHKST03010100` (period='Y', NXT 후보=`UN`→`J`, 그 외=`J`) | Redis cache (옵션) | **DB 영속** | — | — |
| **당일 진행 중 일봉** | KIS REST + KIS WS tick 누적 | Redis only (휘발, 장 마감까지) | **20:00 KST 후 DB INSERT (`DailyCloseFinalizer` PR #303)** | NXT 마감 20:00 KST | Redis: 장 마감(20:00) + 30분 |

### 3-A. 합산 interval source 매트릭스 (CandleAggregator precomputed 30 거래일)

| Interval | 1m 합산 단위 | NXT cover bar/일 | 30 거래일 row 추정 |
|---|---|---|---|
| 3m | 3건 | 130 (09:00~15:27) | ~3,900 / 종목 |
| 5m | 5건 | 78 (09:00~15:25) | ~2,340 / 종목 |
| 15m | 15건 | 26 (09:00~15:15) | ~780 / 종목 |
| 30m | 30건 | 13 (09:00~15:00) | ~390 / 종목 |
| 90m | 90건 | 5 (09:00·10:30·12:00·13:30·15:00) | ~150 / 종목 |
| 1h | 60건 | 7 (09:00~15:00) | ~210 / 종목 |
| 4h | 240건 | 2 (09:00·13:00) | ~60 / 종목 |
| 12h | 390건 | 1 (09:00) | ~30 / 종목 |
| **합계 합산 8종** | — | — | **~7.9K rows / 종목** |

풀 30종목 × ~7K = ~210K rows. 1m 베이스 (720/일 × 30 × 30종목 ≈ 648K rows) 대비 ~32% 추가 (ADR 0062 §9-3 정합).

## 4. UI 버튼 (ADR 0057·0062 정합)

> **2026-05-31 갱신**: 3m·4h·12h까지 포함한 합산 interval 전체를 IntervalToggle과 스크리너 기준봉 선택지에 노출.

```
분봉: [1m] [3m] [5m] [15m] [30m] [90m] [1h] [4h] [12h]  ← 1m 직접 수집 + 백엔드 precomputed 합산
일봉: [1d] [1wk] [1mo] [1y]                              ← KIS REST 전체 기간
```

1d·1wk·1mo·1y·1m은 KIS가 직접 제공 → **합성 0**. 3m·5m·15m·30m·90m·1h·4h·12h은 **백엔드 precomputed 합산** (CandleAggregator 도메인 본체). 모든 클라이언트(REST API·MCP·룰엔진·백테스트·스크리너) 동일 source 참조.

차트에는 일반 갱신(upsert)과 별도로 재수집 버튼을 둔다. 재수집은 사용자가 확인한 경우에만 현재 선택 interval row를 삭제하고 KIS 원천을 다시 수집한다. 합산봉 재수집은 1m 원천을 함께 재수집한 뒤 합산봉을 다시 생성한다.

## 5. KIS REST 한도 (ADR 0037 + 0057 정합)

| Interval | KIS endpoint / tr_id | 한도 |
|---|---|---|
| 1m (당일) | `FHKST03010200` | 당일 한정 — 1회 호출당 30건 / 페이징 가능 |
| 1d·1wk·1mo·1y | `FHKST03010100` | 1회 호출당 100건 / 페이징 (FID_INPUT_DATE_1·FID_INPUT_DATE_2 범위) |
| 현재가 | `FHKST01010100` | 1회 호출당 1건 |

**Rate Limit (ADR 0037)**: 초당 10건 토큰 버킷 + 우선순위 큐 (사용자 path > Watchlist Ingestion > backfill).

**`FHKST03010230` (KIS 1분봉 과거 데이터)**: prod credential 단일 채널 호출. 1m 30거래일 영속 + 3m·5m·15m·30m·90m·1h·4h·12h 합산 source로 활용.

## 6. cold-start 흐름 (ADR 0028 §2-3 + ADR 0062·0063 정합)

> **2026-05-12 갱신 (v3.2.3 doc cut — PR #303·#304 구현 인용)**: halt/liquidation skip + market 분기 + AggregateBackfillRunner 호출 + 1m 30 거래일 페이지네이션 명시.

```
사용자 [관심등록 클릭] (frontend /watchlist)
    ↓
api POST /api/watchlists  (body: {code})
    ↓
DB watchlist INSERT + Kafka watchlist.changed.v1
    ↓
consumer WatchlistChangedKafkaListener → ColdStartExecutor.submit(code) (PR #303·#304 분기)
    ├─ instrument halt/liquidation 검사 (PR #304):
    │    └─ tradingHalt=true 또는 liquidation=true → KIS_COLD_START_SKIPPED audit + return
    ├─ 종목 단위 NXT 후보 분기 → fid_cond_mrkt_div_code 결정 (ADR 0091):
    │    ├─ is_kospi200=true 또는 is_kosdaq150=true → "UN" 우선, 0건이면 "J" fallback
    │    └─ 그 외 / UNKNOWN → "J" (market 이름만으로 UN 선택 금지)
    ├─ SubscriptionRouter.add(code, priority) — slot 1+30+10 분배 (ADR 0048)
    │    ├─ slot 여유 → KIS WS subscribe(code) — H0STCNT0 체결가
    │    └─ slot 가득 + priority 낮음 → WatchlistPollingWorker 진입 (5초 주기 KIS REST inquire-price)
    ├─ KIS_COLD_START_INTERVALS 5종 fetch (prod credential 단일 채널):
    │    ├─ 1m  : FHKST03010230 — 30 거래일 페이지네이션 → DB INSERT (~10K rows / 종목)
    │    ├─ 1d  : FHKST03010100 period='D' → DB INSERT (200건)
    │    ├─ 1wk : FHKST03010100 period='W' → DB INSERT (200건)
    │    ├─ 1mo : FHKST03010100 period='M' → DB INSERT (120건)
    │    └─ 1y  : FHKST03010100 period='Y' → DB INSERT (10건)
    └─ AggregateBackfillRunner.backfill(code) (ADR 0062 §3 권장안):
         ├─ 1m 전량 fetch (~10K rows)
         ├─ CandleAggregator.aggregate(1m, target) × 8종
         ├─ 3m·5m·15m·30m·90m·1h·4h·12h DB INSERT (ON CONFLICT DO NOTHING)
         └─ audit_trail.detail_json.aggregateInserted 필드 기록
    ↓
사용자 /symbol/{code} 진입 시:
  - 1d·1wk·1mo·1y → 즉시 응답 (DB)
  - 1m → 30 거래일 cover (DB) + 당일 추가분 (Redis)
  - 3m·5m·15m·30m·90m·1h·4h·12h → 즉시 응답 (DB, 백엔드 precomputed 합산 — ADR 0062 채택)
```

**WS 끊김 시 polling 자동 전환 (ADR 0048 §2-4)**:
- 10초 heartbeat 무신호 또는 onClose → 지수 백오프 1·2·5·10·30s 재연결 시도
- 30초 안에 재연결 못하면 모든 종목 5초 polling 활성 (KIS REST `FHKST01010100`)
- WS 복구 시 polling 즉시 stop + 모든 stream 재구독

## 7. 당일 진행 중 캔들 흐름 (ADR 0029·0062·0063 정합)

> **2026-05-12 갱신**: NXT 08:00 시작 + 20:00 DailyCloseFinalizer + flushAggregations 흐름 추가 (PR #303 구현 인용).

```
08:00 (NXT pre-hour 시작 — KOSPI 200 종목)
  └─ Redis: candle:today:{code}:1d = {open=시초가, high·low·close=시초가, volume=0}
       ↓
KIS WS tick 도달 (08:00~20:00 KST NXT cover)
  ├─ candle:current:{code}:1m 갱신 (current bar OHLCV)
  └─ candle:today:{code}:1d 갱신 (high·low·close·volume 누적)
       ↓
1분 boundary closing
  ├─ candle:current:{code}:1m → candle table INSERT (interval='1m')
  ├─ candle:current:{code}:1m evict
  ├─ Redis publish market.candle.minute.closed.v1 → STOMP fanout
  └─ 새 1분봉 candle:current 시작
       ↓
3m·5m·15m·30m·90m·1h·4h·12h boundary closing (`TickToMinuteAggregator` 확장)
  ├─ 직전 1m N건 fetch → CandleAggregator.aggregate(1m, target)
  ├─ candle:current:{code}:{target} 갱신
  ├─ candle table INSERT (interval=target)
  └─ Redis publish market.candle.{target}.closed.v1 → STOMP fanout
       ↓
15:30 (KRX 정규장 마감)
       ↓
15:30~20:00 (NXT after-hour 거래)
       ↓
20:00 DailyCloseFinalizer (Spring @Scheduled, ADR 0063 NXT 마감 정합 — PR #303)
  ├─ Redis candle:today:{code}:1d → candle table INSERT (interval='1d')
  ├─ KIS REST FHKST03010100 종가 재검증
  ├─ flushAggregations() — watchlist 전 종목 1m → 3m·5m·15m·30m·90m·1h·4h·12h 합산 갱신
  │   └─ AggregateBackfillRunner.backfill(code) × watchlist 전 종목 (당일 누적분 반영)
  └─ Redis candle:today evict (30분 후)
다음 영업일 08:00 → 새 당일봉 Redis 키 생성 (NXT pre-hour)
```

## 8. 신규 컴포넌트

> **2026-05-12 갱신**: AggregateBackfillRunner + CandleAggregator + ColdStartExecutor 행 추가 (PR #303·#304 구현 인용).

| 위치 | 컴포넌트 | 책임 |
|---|---|---|
| `consumer/.../kafka/WatchlistChangedKafkaListener.kt` | Kafka 진입점 | watchlist.changed.v1 수신 → ColdStartExecutor.submit(code) |
| `consumer/.../ingestion/ColdStartExecutor.kt` | KIS cold-start 5종 + halt skip + AggregateBackfillRunner 호출 (PR #303·#304) | KIS_COLD_START_INTERVALS 5종 fetch + audit 발행 |
| `consumer/.../aggregation/AggregateBackfillRunner.kt` | 1m 합산 → 3m·5m·15m·30m·90m·1h·4h·12h DB INSERT | cold-start trigger + 20:00 flushAggregations trigger |
| `consumer/.../aggregation/AggregateAdminController.kt` | admin 수동 트리거 (PR #303 신규, port 8082) | `POST /admin/aggregate/{code}` + `POST /admin/aggregate/all` |
| `consumer/.../market/TickToMinuteAggregator.kt` | tick → 1m closing | Redis 갱신 + closing 시 evict + DB INSERT + 합산 boundary 검사 |
| `consumer/.../application/market/DailyCloseFinalizer.kt` | **20:00 KST 일봉 확정 + flushAggregations** | Redis 당일 D1 → DB INSERT + watchlist 전 종목 합산봉 갱신 |
| `consumer/.../bootstrap/CandleBootstrapRunner.kt` | 부팅 시 watchlist 1m·합산봉 검사 | 1m 충분하나 합산 interval 중 누락이 있으면 AggregateBackfillRunner 자동 호출 |
| `consumer/.../infrastructure/kis/KisRestCandleAdapter.kt` | KIS REST 캔들 fetch (종목 단위 NXT 후보 `UN` 우선 + `J` fallback — ADR 0091) | 1d·1wk·1mo·1y 전체 기간 backfill + 1m 30 거래일 페이지네이션 (FHKST03010230) + 1m 당일분 (FHKST03010200) |
| `domain/.../market/CandleAggregator.kt` | **3m·5m·15m·30m·90m·1h·4h·12h 합산 도메인 본체** Pure Kotlin, Spring/JPA 의존 0 | OHLCV 합산 + boundary 정렬 |
| `api/.../service/CandleRefreshService.kt` | 사용자 재수집(recollect) path | 선택 interval 삭제 → KIS 재수집 → 합산봉은 1m 기반 재생성 |
| `api/.../infrastructure/cache/RedisCandleCache.kt` | Redis 어댑터 | 당일 진행 중 캔들 read/write |
| `api/.../controller/WatchlistController.kt` | Watchlist REST | 등록·조회·삭제 |
| `frontend/src/pages/Watchlist/` | UI 1순위 | 관심종목 등록 화면 (ADR 0040) |
| `frontend/src/pages/Symbol/IntervalToggle.tsx` | **IntervalToggle 합산 interval 활성화** | 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y 선택 |
| `frontend/src/pages/Symbol/SymbolChart.tsx` | 캔들 갱신/재수집 UI | 일반 갱신은 upsert, 재수집은 확인 후 현재 interval 초기화 + 재수집 |

**폐기 컴포넌트 (ADR 0057 정합)**:
- ~~`consumer/.../infrastructure/yahoo/YahooCandleFetcherAdapter.kt`~~ — `@Deprecated` 마킹 (별도 market-data sub-agent PR), v3.3.0 minor에서 실제 삭제
- ~~`api/.../infrastructure/yahoo/YahooFinanceClient.kt`~~ — 동일

## 9. 폐기 항목 (이전 정책 대비)

- ❌ Yahoo Finance 캔들 fetch 일체 (ADR 0057 — 2026-05-11 사용자 결정)
- ❌ `YahooCandleFetcherAdapter` (별도 PR에서 `@Deprecated`)
- ⏳ `CandleAggregator` (백엔드 M1 → N분봉 합성) — **부활 방향 확정** (backlog §31 트랙 — 위치 옵션 A 채택 시 backend 신설)
- ⏳ `candleAggregator.ts` (frontend M1 → N분봉 합성) — **부활 방향 확정** (backlog §31 트랙 — 위치 옵션 B 채택 시 frontend 신설)
- ❌ 자유 입력 input (12m·45m 등)
- ❌ 분봉 LRU 1년 정책
- ❌ 사용자 차트 LRU 10 종목 정책
- ❌ 분봉 Redis ZSet 영속 (DB가 진실원)
- ❌ CHECK constraint `interval IN ('M1','D1')` (1m·1d·1wk·1mo·1y 영속 허용)

## 10. Universe 사이즈 estimate (ADR 0057 정합)

| 풀 size | 1m DB | 1d·1wk·1mo·1y DB | DB 합계 |
|---|---|---|---|
| 30 종목 (MVP) | 0 (당일 한정 Redis only) | ~ 30 MB (1d 10년 + 1wk/1mo/1y) | **≤ 50 MB** |
| 100 종목 | 0 | ~ 100 MB | **≤ 150 MB** |
| 500 종목 (post-MVP) | 0 | ~ 500 MB | **≤ 750 MB** |

ADR 0019 §2 매트릭스의 Yahoo 의존 부분이 보류된 현시점 대비 약 **4배 디스크 감소** (Yahoo 1m 7일 + 5m·15m·30m·90m·1h 60~730일 영속이 제거됨). 8GB host 한도 안에서 universe 1000 종목 이상 가능.

**backlog §31 결정 후 변동**: 합산 위치(백엔드 vs 프론트) + source 시간 범위(당일 한정 vs `FHKST03010230` 과거 포함) 채택안에 따라 디스크 영향 차이 — 백엔드 합산 + `FHKST03010230` 도입 시 1m 장기 영속이 추가되어 디스크 증가, 프론트 합산 + 당일 한정 시 현재 수준 유지.
