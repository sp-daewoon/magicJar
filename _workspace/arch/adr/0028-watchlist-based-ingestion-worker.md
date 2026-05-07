# ADR 0028 — Watchlist 기반 Ingestion Worker

- 상태: Accepted
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0019(캔들 저장 정책), ADR 0037(KIS Rate Limit), ADR 0040(관심종목 우선 UI 흐름), **ADR 0048(KIS WS 41 slot + priority eviction + polling fallback — 본 ADR §2-2 풀 size 한도의 1급 권위)**

## 1. 컨텍스트

magicJar의 모든 외부 시세 수집(KIS·Yahoo)은 **사용자가 등록한 관심종목(Watchlist) 풀**을 기준으로 한다. 풀 외 종목은 수집하지 않는다.

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
| **풀 변경 반영** | 추가 즉시 subscribe + Yahoo cold-start fetch / 삭제 즉시 unsubscribe |
| **풀 진입 시 cold-start** | Yahoo 분봉 매트릭스 max 한도 fetch + DB INSERT (1m=7일, 5m/15m/30m/90m=60일, 1h=730일, 1d 이상=Max) |
| **장중 당일 분봉** | KIS REST·WS → Redis 갱신 + DB INSERT (ADR 0029) |

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
    ├─ Yahoo cold-start fetch (1m·5m·15m·30m·90m·1h·1d·1wk·1mo) → DB INSERT
    └─ KIS 당일 분봉 fetch → Redis & DB
    ↓
사용자 차트·시그널·screener 즉시 활성
```

## 3. 결과

- **KIS REST·WS 부하 예측 가능** (풀 size 기반)
- **사용자 흐름 일관성** — 관심종목만 데이터 보임
- **디스크 한도 자연 제한** (universe = 풀 size)
- **Rate Limit·WS 한도 안에서 자연스러운 polling 주기** (1~5초)

## 4. 참고

- ADR 0004 — 캔들 저장 정책 (분봉 DB 영속, Yahoo max 한도 fetch)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시
- ADR 0037 — KIS API Rate Limit 강제 (초당 10건)
- ADR 0022 — 관심종목 우선 UI 흐름
- `domain/.../market/watchlist/` — port 정의
- `consumer/.../ingestion/WatchlistIngestionWorker.kt` — 본 ADR 구현체
