# 캔들 데이터 정책

- 작성일: TBD
- 본 문서는 운영 가이드. 결정 본문은 **ADR 0019 (캔들 저장 정책)** + **ADR 0028 (Watchlist Ingestion)** + **ADR 0029 (당일 진행 중 캔들 Redis 캐시)** + **ADR 0048 (KIS WS 41 slot + polling fallback)** 참조.

## 1. 정책 한 줄 요약

> **DB 영속 = Watchlist 풀 내 종목의 모든 interval (Yahoo + KIS). Redis = 당일 진행 중 캔들 휘발 캐시. 합성·LRU·자유 입력 모두 폐기.**

## 2. 핵심 원칙

1. **Watchlist 기반 universe** — Watchlist 풀 외 종목은 수집·저장 안 함 (ADR 0028)
2. **Yahoo 매트릭스 그대로 UI 노출** — 자유 입력(12m·45m 등) 폐기, 합성 폐기
3. **DB가 진실원** — 모든 interval 영속. Redis는 당일 진행 중만
4. **LRU 폐기** — 풀 size가 자연 universe 한도

## 3. 데이터 매트릭스

| Interval | Source | Cache | DB 영속 | TTL/만료 |
|---|---|---|---|---|
| **틱** | KIS WS `H0STCNT0` | Redis (휘발) | X | retention 1h |
| **1m / 2m / 5m / 15m / 30m / 90m / 1h** | **Yahoo** (Watchlist 진입 시 max 한도) | — | **DB 영속** | — |
| **당일 진행 중 분봉** | **KIS REST·WS** | **Redis & DB** | DB INSERT (분봉 closing 시) | Redis: closing 시 evict |
| **1d / 1wk / 1mo / 1y** | **KIS REST** | Redis cache (옵션) | **DB 영속** | — |
| **당일 진행 중 일봉** | KIS REST | Redis only | 장 마감 후(16:00 KST) DB INSERT | Redis: 장 마감 + 30분 |

## 4. UI 버튼 (Yahoo 매트릭스 정합)

```
분봉: [1m] [2m] [5m] [15m] [30m] [90m] [1h]
일봉: [1d] [1wk] [1mo]
```

자유 입력 input 폐기. 각 interval은 외부 source가 직접 제공 → 백엔드/프론트 합성 0.

## 5. Yahoo Finance 한도 (ADR 0019 정합)

| Interval | 최대 과거 |
|---|---|
| 1m | 최근 **7일** |
| 2m / 5m / 15m / 30m / 90m | 최근 **60일** |
| 1h | 최근 **730일 (~2년)** |
| 1d 이상 | 전체 (Max) |

## 6. cold-start 흐름

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
    ├─ Yahoo backfill 1m  (7일 max)   → DB INSERT
    ├─ Yahoo backfill 5m·15m·30m·90m (60일 max) → DB INSERT
    ├─ Yahoo backfill 1h  (730일 max) → DB INSERT
    ├─ Yahoo backfill 1d  (Max)       → DB INSERT
    └─ KIS REST 1wk·1mo·1y → DB INSERT (전체 기간)
    ↓
사용자 /symbol/{code} 진입 시 모든 interval 즉시 응답
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
| `consumer/.../ingestion/WatchlistIngestionWorker.kt` | Watchlist polling 1~5초 | KIS WS sub + Yahoo cold-start 트리거 |
| `consumer/.../market/TickToMinuteAggregator.kt` | tick → 1m closing | Redis 갱신 + closing 시 DB INSERT |
| `consumer/.../scheduler/DailyCloseFinalizer.kt` | 장 마감 일봉 확정 | Redis 당일 D1 → DB INSERT (16:00 KST) |
| `api/.../infrastructure/yahoo/YahooFinanceClient.kt` | Yahoo v8 chart | 분봉 max 한도 fetch + Jackson 매핑 |
| `api/.../infrastructure/cache/RedisCandleCache.kt` | Redis 어댑터 | 당일 진행 중 캔들 read/write |
| `api/.../controller/WatchlistController.kt` | Watchlist REST | 등록·조회·삭제 |
| `frontend/src/pages/Watchlist/` | UI 1순위 | 관심종목 등록 화면 (ADR 0040) |

## 9. 폐기 항목 (이전 정책 대비)

- ❌ `CandleAggregator` (백엔드 M1 → N분봉 합성)
- ❌ `candleAggregator.ts` (frontend M1 → N분봉 합성)
- ❌ 자유 입력 input (12m·45m 등)
- ❌ 분봉 LRU 1년 정책
- ❌ 사용자 차트 LRU 10 종목 정책
- ❌ 분봉 Redis ZSet 영속 (DB가 진실원)
- ❌ CHECK constraint `interval IN ('M1','D1')` (모든 interval 영속 허용)

## 10. Universe 사이즈 estimate

| 풀 size | 분봉 영속 | 일봉 영속 | DB 합계 |
|---|---|---|---|
| 30 종목 (MVP) | ~ 100 MB (1m 7일 + 5m 60일 + 1h 730일) | ~ 30 MB (1d 10년) | **≤ 200 MB** |
| 100 종목 | ~ 350 MB | ~ 100 MB | **≤ 500 MB** |
| 500 종목 (post-MVP) | ~ 1.7 GB | ~ 500 MB | **≤ 2.5 GB** |

8GB host 한도 안에서 universe 500 종목까지 안전.
