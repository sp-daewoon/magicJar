# ADR 0019 — 캔들 저장 정책 (Watchlist 기반 + Yahoo 분봉 DB 영속)

- 상태: **Superseded by ADR 0057, 2026-05-11** — Yahoo Finance 전면 폐기 + KIS OpenAPI 단일 출처 정책으로 전환 (사용자 dogfooding 결함 root cause = Yahoo v8 chart endpoint가 `interval=1d&range=max` 조합에서 monthly downsample 응답 반환 → 일봉 위치에 월봉 INSERT). 사용자 명시 "야후꺼 쓰지 말라니깐" / "kis에 있는 데이터만 사용하라고" (2026-05-11). 본 ADR §2-1 매트릭스 + §2-3 cold-start + §2-5 합성 폐기 모두 ADR 0057로 재정의됨.
- 작성일: TBD
- 결정자: architect
- 관계: **ADR 0057 (KIS OpenAPI 단일 데이터 출처) — supersede 후속** / ADR 0028(Watchlist Ingestion Worker), ADR 0029(당일 진행 중 캔들 Redis 캐시), ADR 0037(KIS Rate Limit)

## 1. 컨텍스트

magicJar는 다음 4 종류의 캔들을 다룬다:

1. **틱**: KIS WS 실시간
2. **분봉 (1m·2m·5m·15m·30m·90m·1h)**: 사용자 차트·DSL·screener·signal·backtest의 base
3. **일봉 이상 (1d·1wk·1mo·1y)**: 룰 엔진·백테스트의 long-term base
4. **당일 진행 중 캔들**: 장중 OHLCV가 매 틱 변동 (분봉·일봉 모두)

Yahoo Finance interval별 한도가 명확 (ADR — `external-api-policy.md` §3):
- 1m → 7일 / 2m·5m·15m·30m·90m → 60일 / 1h → 730일 / 1d 이상 → 전체

KIS REST는 일봉/주봉/월봉/년봉 backfill 가능. 분봉은 당일분만 제공.

## 2. 결정

### 2-1. 데이터 영속 매트릭스

| Interval | Source | Cache | DB 영속 |
|---|---|---|---|
| **틱** | KIS WS `H0STCNT0` | Redis (휘발, retention 1h) | X |
| **1m / 2m / 5m / 15m / 30m / 90m / 1h** | **Yahoo** (Watchlist 진입 시 max 한도 fetch) | — | **DB 영속** |
| **당일 진행 중 분봉** | **KIS REST·WS** | **Redis & DB** | DB INSERT (분 단위 closing 시) |
| **1d / 1wk / 1mo / 1y** | **KIS REST** `FHKST03010100` | Redis cache (옵션) | **DB 영속** |
| **당일 진행 중 일봉** | KIS REST | Redis only (휘발, 장 마감까지) | 장 마감 후 DB INSERT |

### 2-2. UI 노출 interval (Yahoo 매트릭스 정합)

자유 입력 폐기. 버튼만:

```
분봉: [1m] [2m] [5m] [15m] [30m] [90m] [1h]
일봉: [1d] [1wk] [1mo]
```

각 interval은 외부 source가 직접 제공 → **백엔드/프론트 합성 0**.

### 2-3. cold-start 흐름

사용자가 Watchlist에 종목 등록 시 (ADR 0028 — `WatchlistIngestionWorker`):

```
0초:    api POST /api/watchlists  (body: {code})
        → DB watchlist INSERT
        → Kafka watchlist.changed.v1
1~3초:  consumer WatchlistIngestionWorker
        ├─ KIS WS subscribe(code) (틱 + 당일 분봉)
        ├─ Yahoo backfill 1m (7일) → DB INSERT
        ├─ Yahoo backfill 5m·15m·30m·90m (60일) → DB INSERT
        ├─ Yahoo backfill 1h (730일) → DB INSERT
        ├─ Yahoo backfill 1d (Max) → DB INSERT (또는 KIS REST가 1차)
        └─ KIS REST 1d 영속 (전체 기간)
3+초:   사용자 /symbol/{code} 진입 시 모든 interval 즉시 응답
```

### 2-4. LRU 정책 폐기

Watchlist 풀이 자연 universe 한도 (ADR 0028 §2-2). 풀 size 30 초과 시 등록 시점 우선순위로 자동 unsub. **DB row LRU 폐기** — 풀 내 종목은 전 기간 보존.

### 2-5. 합성 폐기

이전 정책의 `CandleAggregator` (M1 → N분봉 백엔드 합성)·`candleAggregator.ts` (frontend) 모두 폐기. Yahoo가 직접 제공하는 interval만 노출.

### 2-6. CHECK constraint

`candle.interval`은 다음만 허용:
```sql
ALTER TABLE candle ADD CONSTRAINT candle_interval_chk
  CHECK (interval IN ('1m','2m','5m','15m','30m','90m','1h','1d','1wk','1mo','1y'));
```

## 3. 결과

- **DB 영속 단순** — 모든 interval이 DB의 단일 진실원
- **합성 코드 0** — 백엔드·프론트엔드 모두 합성 함수 부재
- **Watchlist 진입 시 1회 cold-start으로 모든 interval 채워짐** — 사용자 차트 즉시 표시
- **당일 분봉만 Redis & DB dual** (장중 변동 OHLCV) — 그 외는 DB only
- **Rate Limit 안전** (ADR 0037 정합) — Watchlist 풀이 자연 한도

## 4. 절충

- 분봉 영속 디스크 부담 — 풀 30종목 × 1m 7일 × 390 분봉/일 ≈ 82K rows (~12 MB) / 5m 60일 × 78 ≈ 17K rows / 1h 730일 × 6.5 ≈ 4.7K rows. 풀당 ≤ 100 MB
- 풀 100종목 확장 시 ≤ 1 GB — PostgreSQL 단일 인스턴스(ADR 0006) 한도 안
- 컨테이너 재기동 시 분봉·일봉 모두 DB 영속이라 stale 0. Redis는 당일 진행 중만 휘발

## 5. 참고

- ADR 0028 — Watchlist 기반 Ingestion Worker (본 정책의 trigger)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시
- ADR 0037 — KIS API Rate Limit 강제
- ADR 0018 — Corp Action raw + adjusted (수정 주가 정합)
- `external-api-policy.md` §3 — Yahoo Finance 한도
- `docs/architecture/candle-data-policy-v2.md` — 운영 가이드
