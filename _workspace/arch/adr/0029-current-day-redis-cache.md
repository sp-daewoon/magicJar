# ADR 0029 — 당일 진행 중 캔들 Redis 캐시

- 상태: Accepted
- 작성일: TBD
- 2026-05-11 갱신: **ADR 0057 (KIS-only data source) 정합** — §2-4 Yahoo 매트릭스 인용 정정, Yahoo Finance 캔들 fetch 폐기 명시. credential 정책: 정보 수집은 prod 단일 채널.
- 결정자: architect
- 관계: **ADR 0057(KIS-only data source)**, ADR 0019(Accepted — Yahoo 의존 부분만 보류), ADR 0028(Watchlist Ingestion)

## 1. 컨텍스트

당일 진행 중 캔들은 OHLCV가 장중 매 틱 변동:
- **당일 분봉**: 매 1분마다 closing 후 확정 — closing 전까지는 last bar의 high·low·close·volume이 갱신됨
- **당일 일봉**: 장 시작(09:00)부터 장 마감(15:30)까지 변동 — 종가 미확정

매 변경마다 DB UPDATE는 부담. 또한 다른 모듈(consumer·api·frontend)이 동시 read하므로 단일 휘발 캐시가 자연.

## 2. 결정

### 2-1. Redis 책임 (휘발 캐시)

| 캐시 키 | 데이터 | 갱신 주기 | TTL |
|---|---|---|---|
| `candle:current:{code}:1m` | 당일 진행 중 1분봉 (마지막 미확정 bar) | KIS WS tick 도달마다 | 1분 closing 시 evict |
| `candle:today:{code}:1d` | 당일 진행 중 일봉 (OHLCV) | KIS WS tick 도달마다 | 장 마감(15:30 KST) + 30분 후 evict |
| `candle:closed:{code}:1m:recent` | 최근 closing 분봉 (장중 차트 즉시 응답용, 100개) | 1분 closing 시 push | 장 마감 + 30분 |

### 2-2. DB 책임 (영속, ADR 0004 정합)

| 데이터 | INSERT 시점 |
|---|---|
| **closing 분봉 (1m·2m·5m·15m·30m·90m·1h)** | 매 분봉 closing 시 INSERT (consumer `TickToMinuteAggregator`) |
| **당일 일봉 확정** | 장 마감 후(16:00 KST) `DailyCloseFinalizer` cron INSERT |

### 2-3. 장중 → 장 마감 흐름

```
09:00 (장 시작)
  └─ Redis: candle:today:{code}:1d = {open=시초가, high=시초가, low=시초가, close=시초가, volume=0}
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

### 2-4. 폐기 정책

이전 정책의 다음 항목은 폐기:
- **LRU 캐시 정책 (10 종목 한도)** — Watchlist 풀이 자연 universe (ADR 0028)
- **자유 입력 분봉 (12m·45m 등)** — UI 매트릭스 버튼만 (ADR 0057 §2-2)
- **백엔드 합성 (M1 → N분봉)** — 5m·15m·30m·90m·1h은 **1m 합산 가공으로 처리 결정** (방향 확정 — backlog §31 트랙). 합산 위치(백엔드 vs 프론트)·source 시간 범위는 결정 대기
- **분봉 Redis ZSet 영속** — DB가 진실원 (ADR 0057)
- **Yahoo Finance 캔들 fetch** — 전면 폐기 (ADR 0057, 2026-05-11)

### 2-5. STOMP fanout

당일 분봉 closing 시 frontend 실시간 갱신:
```
/topic/market.candle.minute.{code}     - 1분봉 closing 이벤트
/topic/market.candle.tick.{code}       - 당일 분봉 진행 중 OHLCV (1초 throttle)
/topic/market.candle.daily.{code}      - 당일 일봉 진행 중 OHLCV (1초 throttle)
```

## 3. 결과

- **장중 OHLCV 매 틱 갱신** — Redis만 갱신, DB UPDATE 0
- **확정 데이터는 DB 영속** — 백테스트·룰 엔진은 DB만 read
- **컨테이너 재기동 시 깨끗** — Redis 휘발이라 stale 0. KIS WS 재연결로 복원
- **사용자 체감** — 차트·현재가 1초 이내 반영

## 4. 참고

- ADR 0004 — 캔들 저장 정책 (분봉·일봉 DB 영속)
- ADR 0028 — Watchlist 기반 Ingestion Worker
- ADR 0014 — 시세 데이터 Fallback 3-tier (장외 fallback)
- `consumer/.../market/TickToMinuteAggregator.kt` — 1분봉 closing 시 DB INSERT
- `consumer/.../scheduler/DailyCloseFinalizer.kt` — 장 마감 시 일봉 확정
- `api/.../infrastructure/cache/RedisCandleCache.kt` — Redis 어댑터
