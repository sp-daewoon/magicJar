# 캔들 데이터 정책

- 작성일: TBD
- 2026-05-11 갱신: **ADR 0057 KIS-only data source 정합** — Yahoo Finance 전면 폐기. 본 문서 §3·§4·§5·§6 매트릭스 KIS-only로 재정의.
- 2026-05-11 amend: ADR 0019 supersede 인용 제거 → **Accepted (Yahoo 의존 부분만 보류)**. 5m~1h은 **1m 합산 가공 방향 확정** (backlog §31 트랙 — 위치·source 결정 대기). credential 정책: 정보 수집은 prod 단일 채널 (모의/실전 분기는 주문 라우터 한정).
- 본 문서는 운영 가이드. 결정 본문은 **ADR 0057 (KIS OpenAPI 단일 데이터 출처)** + **ADR 0019 (Accepted, Yahoo 의존 부분만 보류)** + **ADR 0028 (Watchlist Ingestion)** + **ADR 0029 (당일 진행 중 캔들 Redis 캐시)** + **ADR 0048 (KIS WS 41 slot + polling fallback)** 참조.

## 1. 정책 한 줄 요약

> **KIS OpenAPI 단일 출처 (prod credential 단일 채널). DB 영속 = Watchlist 풀 내 종목의 1d·1wk·1mo·1y (KIS REST). 1m은 당일 한정 Redis only. 5m·15m·30m·90m·1h은 1m 합산 가공으로 처리 (방향 확정, 위치·source는 backlog §31). Yahoo Finance 폐기.**

## 2. 핵심 원칙

1. **KIS OpenAPI 단일 출처** — Yahoo Finance·네이버 금융 등 보조 출처 폐기 (ADR 0057, 사용자 결정 2026-05-11)
2. **정보 수집 = prod credential 단일 채널** — 모의/실전 분기는 주문 라우터 한정 (사용자 명시 정책 정정 2026-05-11)
3. **Watchlist 기반 universe** — Watchlist 풀 외 종목은 수집·저장 안 함 (ADR 0028)
4. **DB가 진실원** — 1d·1wk·1mo·1y 영속. Redis는 1m 당일 + 당일 진행 중 1d만
5. **LRU 폐기** — 풀 size가 자연 universe 한도
6. **5m·15m·30m·90m·1h = 1m 합산 가공 (방향 확정)** — 합산 위치(백엔드 vs 프론트) + source 시간 범위는 backlog §31 트랙에서 결정. 트랙 완료 전까지 "1m 합산 가공 예정" 표시

## 3. 데이터 매트릭스 (ADR 0057 정합)

| Interval | Source (KIS tr_id) | Cache | DB 영속 | TTL/만료 |
|---|---|---|---|---|
| **틱** | KIS WS `H0STCNT0` | Redis (휘발) | X | retention 1h |
| **1m (1분봉)** | KIS REST `FHKST03010200` (당일 한정) | Redis (당일) | X (당일 한정) | Redis: 다음날 00:00 evict |
| **5m / 15m / 30m / 90m / 1h** | **1m 합산 가공 (방향 확정 — backlog §31 트랙)** | — (위치 결정 대기) | — | — |
| **1d (일봉)** | KIS REST `FHKST03010100` (period='D') | Redis cache (옵션) | **DB 영속** | — |
| **1wk (주봉)** | KIS REST `FHKST03010100` (period='W') | Redis cache (옵션) | **DB 영속** | — |
| **1mo (월봉)** | KIS REST `FHKST03010100` (period='M') | Redis cache (옵션) | **DB 영속** | — |
| **1y (년봉)** | KIS REST `FHKST03010100` (period='Y') | Redis cache (옵션) | **DB 영속** | — |
| **당일 진행 중 일봉** | KIS REST + KIS WS tick 누적 | Redis only (휘발, 장 마감까지) | 장 마감 후 DB INSERT | Redis: 장 마감 + 30분 |

## 4. UI 버튼 (ADR 0057 정합)

```
분봉: [1m]                              ← KIS REST 당일 한정 (장중 한정 노출, 장외 시 "데이터 없음")
중간봉: [5m] [15m] [30m] [90m] [1h]    ← "1m 합산 가공 예정 (backlog §31 트랙)"
일봉: [1d] [1wk] [1mo] [1y]            ← KIS REST 전체 기간
```

자유 입력 input 폐기. 1d·1wk·1mo·1y·1m은 KIS가 직접 제공 → **합성 0**. 5m·15m·30m·90m·1h은 **1m 합산 가공으로 처리 결정** (방향 확정 — backlog §31 트랙에서 위치(백엔드 vs 프론트) + source 시간 범위 결정).

## 5. KIS REST 한도 (ADR 0037 + 0057 정합)

| Interval | KIS endpoint / tr_id | 한도 |
|---|---|---|
| 1m (당일) | `FHKST03010200` | 당일 한정 — 1회 호출당 30건 / 페이징 가능 |
| 1d·1wk·1mo·1y | `FHKST03010100` | 1회 호출당 100건 / 페이징 (FID_INPUT_DATE_1·FID_INPUT_DATE_2 범위) |
| 현재가 | `FHKST01010100` | 1회 호출당 1건 |

**Rate Limit (ADR 0037)**: 초당 10건 토큰 버킷 + 우선순위 큐 (사용자 path > Watchlist Ingestion > backfill).

**`FHKST03010230` (KIS 1분봉 과거 데이터)**: prod credential 단일 채널 호출. backlog §31의 source 옵션 2 후보 — 본 ADR 발효 시점에는 미사용. 채택 시 1m 장기 영속 + 5m·15m·30m·90m·1h 합산 source로 활용.

## 6. cold-start 흐름 (ADR 0057 §2-3 정합)

```
사용자 [관심등록 클릭] (frontend /watchlist)
    ↓
api POST /api/watchlists  (body: {code})
    ↓
DB watchlist INSERT + Kafka watchlist.changed.v1
    ↓
consumer WatchlistIngestionWorker (5초 polling LOCK, ADR 0028+0048)
    ├─ SubscriptionRouter.add(code, priority) — slot 1+30+10 분배 (ADR 0048)
    │    ├─ slot 여유 → KIS WS subscribe(code) — H0STCNT0 체결가
    │    └─ slot 가득 + priority 낮음 → WatchlistPollingWorker 진입 (5초 주기 KIS REST inquire-price)
    ├─ KIS REST 1d  (FHKST03010100 period='D', 전체 기간) → DB INSERT
    ├─ KIS REST 1wk (FHKST03010100 period='W', 전체 기간) → DB INSERT
    ├─ KIS REST 1mo (FHKST03010100 period='M', 전체 기간) → DB INSERT
    ├─ KIS REST 1y  (FHKST03010100 period='Y', 전체 기간) → DB INSERT
    └─ 1m 분봉 — 장중일 때만 KIS REST FHKST03010200 당일분 fetch + Redis only (prod credential)
        (5m~1h backfill 0 — backlog §31 트랙 완료 시 1m 합산 source로 활성화)
    ↓
사용자 /symbol/{code} 진입 시:
  - 1d·1wk·1mo·1y → 즉시 응답 (DB)
  - 1m → 장중일 때 Redis only / 장외일 때 "데이터 없음"
  - 5m·15m·30m·90m·1h → "1m 합산 가공 예정 (backlog §31 트랙)"
```

**WS 끊김 시 polling 자동 전환 (ADR 0048 §2-4)**:
- 10초 heartbeat 무신호 또는 onClose → 지수 백오프 1·2·5·10·30s 재연결 시도
- 30초 안에 재연결 못하면 모든 종목 5초 polling 활성 (KIS REST `FHKST01010100`)
- WS 복구 시 polling 즉시 stop + 모든 stream 재구독

## 7. 당일 진행 중 캔들 흐름 (ADR 0029)

```
09:00 (장 시작)
  └─ Redis: candle:today:{code}:1d = {open=시초가, high·low·close=시초가, volume=0}
       ↓
KIS WS tick 도달
  ├─ candle:current:{code}:1m 갱신 (current bar OHLCV)
  └─ candle:today:{code}:1d 갱신 (high·low·close·volume 누적)
       ↓
1분 closing
  ├─ candle:current → candle table INSERT (interval='1m')
  ├─ candle:current evict
  ├─ Redis publish market.candle.minute.closed.v1 → STOMP fanout
  └─ 새 1분봉 candle:current 시작
       ↓
15:30 (장 마감)
       ↓
16:00 DailyCloseFinalizer (Spring @Scheduled)
  ├─ Redis candle:today:{code}:1d → candle table INSERT (interval='1d')
  ├─ KIS REST FHKST03010100 종가 재검증
  └─ Redis candle:today evict (30분 후)
다음 영업일 09:00 → 새 당일봉 Redis 키 생성
```

## 8. 신규 컴포넌트

| 위치 | 컴포넌트 | 책임 |
|---|---|---|
| `consumer/.../ingestion/WatchlistIngestionWorker.kt` | Watchlist polling 1~5초 | KIS WS sub + KIS REST cold-start 트리거 (ADR 0057 정합) |
| `consumer/.../market/TickToMinuteAggregator.kt` | tick → 1m closing | Redis 갱신 + closing 시 evict (DB INSERT 0 — 1m은 당일 한정) |
| `consumer/.../scheduler/DailyCloseFinalizer.kt` | 장 마감 일봉 확정 | Redis 당일 D1 → DB INSERT (16:00 KST) |
| `consumer/.../infrastructure/kis/KisRestCandleAdapter.kt` | KIS REST 캔들 fetch | 1d·1wk·1mo·1y 전체 기간 backfill + 1m 당일분 |
| `api/.../infrastructure/cache/RedisCandleCache.kt` | Redis 어댑터 | 당일 진행 중 캔들 read/write |
| `api/.../controller/WatchlistController.kt` | Watchlist REST | 등록·조회·삭제 |
| `frontend/src/pages/Watchlist/` | UI 1순위 | 관심종목 등록 화면 (ADR 0040) |

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
