# ADR 0028 — Watchlist 기반 Ingestion Worker

- 상태: Accepted
- 작성일: TBD
- 2026-05-11 갱신: **ADR 0057 (KIS-only data source) 정합** — §2-1·§2-3 Yahoo cold-start 인용을 KIS REST cold-start으로 재정의. 5m·15m·30m·90m·1h cold-start은 backlog §31 의제 처리 전까지 0.
- 결정자: architect
- 관계: **ADR 0057(KIS-only data source) — cold-start KIS REST 단일 출처**, ADR 0019(Superseded by 0057), ADR 0037(KIS Rate Limit), ADR 0040(관심종목 우선 UI 흐름), **ADR 0048(KIS WS 41 slot + priority eviction + polling fallback — 본 ADR §2-2 풀 size 한도의 1급 권위)**

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
| **풀 변경 반영** | 추가 즉시 subscribe + **KIS REST cold-start fetch** (ADR 0057) / 삭제 즉시 unsubscribe |
| **풀 진입 시 cold-start** | **KIS REST `FHKST03010100`** 1d·1wk·1mo·1y 전체 기간 fetch + DB INSERT (ADR 0057 §2-3). 5m·15m·30m·90m·1h은 backlog §31 의제 처리 전까지 0건 |
| **장중 당일 분봉** | KIS REST `FHKST03010200` + KIS WS → Redis 갱신. 1m은 당일 한정 Redis only (DB INSERT 0). 일봉은 장 마감 후 DB INSERT (ADR 0029) |

### 2-2. 풀 size 한도

| 단계 | 풀 size | 비고 |
|---|---|---|
| MVP | ≤ 30 종목 | KIS WS 동시 구독 한도 정합 |
| 베이스라인 | ≤ 100 종목 | 풀 분할 + WS 다중 연결 (post-MVP) |
| post-MVP | 무제한 | DB·Rate Limit·WS 한도 검토 후 |

KIS WS 41건 한도 안에서 자연 제약. 풀 size 30 초과 시 우선순위(최근 진입·시그널 활성)로 자동 sub 토글.

### 2-3. Watchlist 변경 → Ingestion 동기화

```
사용자 [관심등록 클릭] (frontend /watchlist)
    ↓
api POST /api/watchlists  (body: {code})
    ↓
DB INSERT watchlist + Kafka publish watchlist.changed.v1
    ↓
consumer WatchlistIngestionWorker
    ├─ KIS WS subscribe(code)
    ├─ KIS REST cold-start fetch (ADR 0057):
    │     - 1d·1wk·1mo·1y (FHKST03010100, 전체 기간) → DB INSERT
    │     - 5m·15m·30m·90m·1h → 0건 (backlog §31 의제 처리 전)
    └─ 1m 당일분 (FHKST03010200) → Redis only (DB INSERT 0)
    ↓
사용자 차트·시그널·screener 즉시 활성 (1d·1wk·1mo·1y 한정. 1m은 장중·Redis. 5m~1h는 "데이터 없음")
```

## 3. 결과

- **KIS REST·WS 부하 예측 가능** (풀 size 기반)
- **사용자 흐름 일관성** — 관심종목만 데이터 보임
- **디스크 한도 자연 제한** (universe = 풀 size)
- **Rate Limit·WS 한도 안에서 자연스러운 polling 주기** (1~5초)

## 4. 참고

- **ADR 0057 — KIS OpenAPI 단일 데이터 출처 (cold-start KIS REST 단일)** ← 본 ADR §2-1·§2-3 권위 출처
- ADR 0019 — 캔들 저장 정책 (Superseded by 0057)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시
- ADR 0037 — KIS API Rate Limit 강제 (초당 10건)
- ADR 0040 — 관심종목 우선 UI 흐름 (`docs/architecture/master-architecture.md` §B.A)
- `domain/.../market/watchlist/` — port 정의
- `consumer/.../ingestion/WatchlistIngestionWorker.kt` — 본 ADR 구현체
- `_workspace/backlog.md` §31 — 5m·15m·30m·90m·1h cold-start 의제 (옵션 A·B·C 결정 대기)
