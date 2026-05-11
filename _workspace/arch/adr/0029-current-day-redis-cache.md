# ADR 0029 — 당일 진행 중 캔들 Redis 캐시

- 상태: Accepted
- 작성일: TBD
- 2026-05-11 갱신: **ADR 0057 (KIS-only data source) 정합** — §2-4 Yahoo 매트릭스 인용 정정, Yahoo Finance 캔들 fetch 폐기 명시. credential 정책: 정보 수집은 prod 단일 채널.
- 2026-05-12 갱신 (v3.2.3 doc cut — ADR 0062·0063 Accepted 재통합): **§2-1 Redis 키 매트릭스에 5m·15m·30m·90m·1h boundary 키 5종 추가** (`candle:current:{code}:5m~1h`) + **§2-3 DailyCloseFinalizer 20:00 KST 확장** (NXT 마감 — 16:00 → 20:00, PR #303 구현) + **`flushAggregations()` 호출 추가** (watchlist 전 종목 당일 1m → 5m·15m·30m·90m·1h 합산 갱신, PR #303). boundary fanout/STOMP 추가 매트릭스는 v3.3.0 §34 sub-agent 작업 가이드로 분리 (modify-shared 충돌 회피).
- 결정자: architect
- 관계: **ADR 0057(KIS-only data source)**, **ADR 0062(5m~1h 합산 가공 백엔드 precomputed — 2026-05-11 Accepted)**, **ADR 0063(NXT/통합거래 cover — 2026-05-11 Accepted)**, ADR 0019(Accepted, 2026-05-12 §2 재통합), ADR 0028(Watchlist Ingestion — cold-start AggregateBackfillRunner 호출)

## 1. 컨텍스트

당일 진행 중 캔들은 OHLCV가 장중 매 틱 변동:
- **당일 분봉**: 매 1분마다 closing 후 확정 — closing 전까지는 last bar의 high·low·close·volume이 갱신됨
- **당일 일봉**: 장 시작(09:00)부터 장 마감(15:30)까지 변동 — 종가 미확정

매 변경마다 DB UPDATE는 부담. 또한 다른 모듈(consumer·api·frontend)이 동시 read하므로 단일 휘발 캐시가 자연.

## 2. 결정

### 2-1. Redis 책임 (휘발 캐시)

> **2026-05-12 갱신 (ADR 0062 §4-4 키 매트릭스 5종 추가)**: 5m·15m·30m·90m·1h boundary 캐시 키 5건 신규. boundary 마감 시 `TickToMinuteAggregator` 합산 → Redis 갱신 → 마감 시 evict + DB INSERT. v3.3.0 §34 sub-agent 작업 가이드(boundary fanout/STOMP)는 별도 사이클로 분리.

| 캐시 키 | 데이터 | 갱신 주기 | TTL |
|---|---|---|---|
| `candle:current:{code}:1m` | 당일 진행 중 1분봉 (마지막 미확정 bar) | KIS WS tick 도달마다 | 1분 closing 시 evict |
| **`candle:current:{code}:5m`** (v3.3.0 §34) | **당일 진행 중 5분봉 (NXT cover 144 bar/일)** | **5분 boundary 마감 시 1m 5건 합산** | **5분 closing 시 evict + DB INSERT** |
| **`candle:current:{code}:15m`** (v3.3.0 §34) | **당일 진행 중 15분봉 (NXT cover 48 bar/일)** | **15분 boundary 마감 시 1m 15건 합산** | **15분 closing 시 evict + DB INSERT** |
| **`candle:current:{code}:30m`** (v3.3.0 §34) | **당일 진행 중 30분봉 (NXT cover 24 bar/일)** | **30분 boundary 마감 시 1m 30건 합산** | **30분 closing 시 evict + DB INSERT** |
| **`candle:current:{code}:90m`** (v3.3.0 §34) | **당일 진행 중 90분봉 (NXT cover 8 bar/일)** | **90분 boundary 마감 시 1m 90건 합산** | **90분 closing 시 evict + DB INSERT** |
| **`candle:current:{code}:1h`** (v3.3.0 §34) | **당일 진행 중 60분봉 (NXT cover 12 bar/일)** | **60분 boundary 마감 시 1m 60건 합산** | **1시간 closing 시 evict + DB INSERT** |
| `candle:today:{code}:1d` | 당일 진행 중 일봉 (OHLCV) | KIS WS tick 도달마다 | **장 마감(20:00 KST — NXT 통합) + 30분 후 evict** |
| `candle:closed:{code}:1m:recent` | 최근 closing 분봉 (장중 차트 즉시 응답용, 100개) | 1분 closing 시 push | **장 마감(20:00 KST) + 30분 후 evict** |

**5m~1h boundary 매트릭스 (NXT cover 08:00~20:00 KST, ADR 0063 정합)**:
- 5m: 08:00·08:05·…·19:55 (144 bar/일)
- 15m: 08:00·08:15·…·19:45 (48 bar/일)
- 30m: 08:00·08:30·…·19:30 (24 bar/일)
- 90m: 08:00·09:30·11:00·12:30·14:00·15:30·17:00·18:30 (8 bar/일)
- 1h: 08:00·09:00·…·19:00 (12 bar/일)

**v3.3.0 §34 작업 가이드 — boundary fanout/STOMP 추가 정합 매트릭스 (별도 사이클)**:
- `TickToMinuteAggregator` boundary 검증 5종 확장 (08:00~20:00 cover)
- STOMP 토픽 `/topic/market.candle.{interval}.{code}` (5m·15m·30m·90m·1h) 추가
- `market.candle.minute.closed.v1` Kafka 이벤트 정합 — interval 필드 5종 확장
- frontend `useSymbolCandles.ts` interval 5종 boundary 마감 후 차트 갱신 hook

본 ADR 0029 §2-1 갱신은 **Redis 키 매트릭스 정의만**. boundary 마감 코드 트리거·STOMP fanout은 v3.3.0 §34 sub-agent 디스패치 매트릭스에서 별도 처리.

### 2-2. DB 책임 (영속, ADR 0004 정합)

> **2026-05-12 갱신 (ADR 0062·0063 Accepted 재통합)**: INSERT 시점 — 1m·5m·15m·30m·90m·1h boundary closing 시 (5m~1h은 백엔드 precomputed 합산) + 당일 일봉 확정 시각 20:00 KST (NXT 마감, PR #303 구현).

| 데이터 | INSERT 시점 |
|---|---|
| **closing 1m 분봉** | 매 1분 boundary closing 시 INSERT (consumer `TickToMinuteAggregator`, NXT cover 08:00~20:00 KST) |
| **closing 5m·15m·30m·90m·1h 분봉 (백엔드 precomputed 합산)** | 매 boundary closing 시 INSERT (ADR 0062 §3 권장안 — `TickToMinuteAggregator` 확장 또는 별도 boundary trigger, v3.3.0 §34 sub-agent 작업) |
| **당일 일봉 확정** | 장 마감 후(**20:00 KST — NXT 통합**, ADR 0063) `DailyCloseFinalizer` cron INSERT (PR #303 — 16:00 → 20:00 갱신) |
| **`flushAggregations()` 갱신** | 20:00 KST `DailyCloseFinalizer.flushAggregations()` (PR #303 신규) — watchlist 전 종목 당일 1m 전량 → 5m·15m·30m·90m·1h 합산 갱신. cold-start 합산 이후 장중 누적분 반영 |

### 2-3. 장중 → 장 마감 흐름

> **2026-05-12 갱신 (ADR 0062·0063 Accepted 재통합)**: NXT 통합 시간대 08:00~20:00 KST cover + 5m~1h boundary 마감 흐름 추가 + DailyCloseFinalizer 20:00 KST 확장 + `flushAggregations()` 호출. PR #303 구현 인용.

```
08:00 (NXT pre-hour 시작 — KOSPI 200 종목)
  └─ Redis: candle:today:{code}:1d = {open=시초가, high=시초가, low=시초가, close=시초가, volume=0}
       ↓
09:00 (KRX 정규장 시작)
  └─ NXT 1m 누적분 + KRX 1m 누적분 통합 (UN 매트릭스, ADR 0063)
       ↓
KIS WS tick 도달 (08:00~20:00 KST NXT cover)
  ├─ candle:current:{code}:1m 갱신 (current bar OHLCV)
  └─ candle:today:{code}:1d 갱신 (high·low·close·volume 누적)
       ↓
1분 boundary closing
  ├─ candle:current:{code}:1m → candle table INSERT (interval='1m')
  ├─ candle:current:{code}:1m evict
  ├─ Redis publish market.candle.minute.closed.v1 → STOMP fanout (1m)
  └─ 새 1분봉 candle:current 시작
       ↓
5m·15m·30m·90m·1h boundary closing (v3.3.0 §34 sub-agent 작업)
  ├─ 직전 1m N건 fetch → CandleAggregator.aggregate(1m, target)
  ├─ candle:current:{code}:{target} 갱신
  ├─ candle table INSERT (interval=target)
  ├─ candle:current:{code}:{target} evict
  └─ Redis publish market.candle.{target}.closed.v1 → STOMP fanout
       ↓
15:30 (KRX 정규장 마감)
       ↓
15:30~20:00 (NXT after-hour 거래)
  └─ KIS WS tick 계속 도달 — 1m·5m~1h 동일 흐름
       ↓
20:00 (NXT 마감)
       ↓
20:00 DailyCloseFinalizer (Spring @Scheduled — ADR 0063 NXT 마감 정합, PR #303)
  ├─ Redis candle:today:{code}:1d → candle table INSERT (interval='1d')
  ├─ KIS REST FHKST03010100 종가 재검증
  ├─ flushAggregations() — watchlist 전 종목 1m → 5m·15m·30m·90m·1h 합산 갱신 (PR #303)
  │   ├─ AggregateBackfillRunner.backfill(code) (당일 누적분 반영)
  │   └─ ON CONFLICT DO NOTHING (멱등)
  └─ Redis candle:today evict (30분 후)
다음 영업일 08:00 → 새 당일봉 Redis 키 생성 (NXT pre-hour)
```

### 2-4. 폐기 정책

> **2026-05-12 갱신 (ADR 0062 §3 권장안 채택)**: "백엔드 합성 폐기" 항목은 **revoke** 상태 — CandleAggregator 도메인 본체 부활 + 백엔드 precomputed 채택 (PR #302 D 구현).

이전 정책의 다음 항목은 폐기:
- **LRU 캐시 정책 (10 종목 한도)** — Watchlist 풀이 자연 universe (ADR 0028)
- **자유 입력 분봉 (12m·45m 등)** — UI 매트릭스 버튼만 (ADR 0057 §2-2)
- ~~**백엔드 합성 (M1 → N분봉)**~~ — **revoke (ADR 0062 Accepted, 2026-05-11)**: 5m·15m·30m·90m·1h은 **백엔드 precomputed 합산** (`CandleAggregator` 도메인 본체 부활, `AggregateBackfillRunner` cold-start trigger). MCP·룰엔진·백테스트 동일 source 정합
- **분봉 Redis ZSet 영속** — DB가 진실원 (ADR 0057)
- **Yahoo Finance 캔들 fetch** — 전면 폐기 (ADR 0057, 2026-05-11)

### 2-5. STOMP fanout

> **2026-05-12 갱신 (ADR 0062·0063 Accepted)**: 본 §2-5는 1m·daily만 정의. **5m·15m·30m·90m·1h boundary fanout 토픽 추가 매트릭스는 v3.3.0 §34 sub-agent 작업 가이드**로 분리 (modify-shared 충돌 회피).

당일 분봉 closing 시 frontend 실시간 갱신:
```
/topic/market.candle.minute.{code}     - 1분봉 closing 이벤트
/topic/market.candle.tick.{code}       - 당일 분봉 진행 중 OHLCV (1초 throttle)
/topic/market.candle.daily.{code}      - 당일 일봉 진행 중 OHLCV (1초 throttle)
```

**v3.3.0 §34 작업 가이드 — boundary fanout 토픽 5종 추가 (별도 사이클)**:
```
/topic/market.candle.5m.{code}         - 5m boundary closing 이벤트 (08:00~19:55 KST)
/topic/market.candle.15m.{code}        - 15m boundary closing 이벤트 (08:00~19:45 KST)
/topic/market.candle.30m.{code}        - 30m boundary closing 이벤트 (08:00~19:30 KST)
/topic/market.candle.90m.{code}        - 90m boundary closing 이벤트 (08:00·09:30·11:00·12:30·14:00·15:30·17:00·18:30)
/topic/market.candle.1h.{code}         - 1h boundary closing 이벤트 (08:00~19:00 KST)
```

## 3. 결과

- **장중 OHLCV 매 틱 갱신** — Redis만 갱신, DB UPDATE 0
- **확정 데이터는 DB 영속** — 백테스트·룰 엔진은 DB만 read
- **컨테이너 재기동 시 깨끗** — Redis 휘발이라 stale 0. KIS WS 재연결로 복원
- **사용자 체감** — 차트·현재가 1초 이내 반영

## 4. 참고

- **ADR 0057** — KIS OpenAPI 단일 데이터 출처
- **ADR 0062** — 5m~1h 합산 가공 백엔드 precomputed (2026-05-11 Accepted) ← `flushAggregations` + boundary 키 5종 권위
- **ADR 0063** — NXT/통합거래 매트릭스 cover (2026-05-11 Accepted) ← 20:00 KST 마감 권위
- ADR 0004 — 캔들 저장 정책 (분봉·일봉 DB 영속)
- ADR 0028 — Watchlist 기반 Ingestion Worker (cold-start AggregateBackfillRunner trigger)
- ADR 0014 — 시세 데이터 Fallback 3-tier (장외 fallback)
- ADR 0019 — 캔들 저장 정책 (Accepted, 2026-05-12 §2 재통합)
- `consumer/.../market/TickToMinuteAggregator.kt` — 1분봉 closing 시 DB INSERT (v3.3.0 §34에서 5m~1h boundary 확장)
- `consumer/.../application/market/DailyCloseFinalizer.kt` — **장 마감 20:00 KST + flushAggregations** (PR #303)
- `consumer/.../aggregation/AggregateBackfillRunner.kt` — ADR 0062 §3 권장안 합산 trigger (PR #303)
- `domain/.../market/CandleAggregator.kt` — Pure Kotlin 합산 도메인 본체 (PR #302 D)
- `api/.../infrastructure/cache/RedisCandleCache.kt` — Redis 어댑터
- PR #303 — DailyCloseFinalizer 20:00 KST + flushAggregations + AggregateBackfillRunner
- PR #304 — KOSDAQ market_div J 분기 + halt/liquidation skip
