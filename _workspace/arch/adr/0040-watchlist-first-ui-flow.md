# ADR 0022 — 관심종목 우선 UI 흐름 (Strategy Builder → 발굴 → Watchlist → 상세)

- 상태: Accepted
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0028(Watchlist Ingestion), ADR 0020(KIS Strategy Builder), ADR 0009(프론트엔드 SPA)

## 1. 컨텍스트

magicJar의 사용자 운영 흐름 1순위는 **관심종목(Watchlist) 등록·관리**. 사용자가 시스템을 처음 열었을 때 가장 먼저 만나는 화면이자, 모든 데이터 수집·차트·시그널·매매가 Watchlist 풀을 기준으로 동작 (ADR 0028 정합).

이전 정책은 종목 검색·차트가 entry point였으나, magicJar의 핵심은 **자동매매 시스템** — 사용자가 무작위 종목을 보는 것이 아니라 **전략으로 발굴 → 관심등록 → 모니터링 → 매매** 흐름이다.

## 2. 결정

### 2-1. UI 1순위 = 관심종목 등록 화면

| Path | 우선순위 |
|---|---|
| `/watchlist` | **1순위** — 진입 default |
| `/strategy-builder` | 2순위 — 종목 발굴 |
| `/symbol/{code}` | 3순위 — 상세 보기 (Watchlist에서 진입) |
| `/dashboard` | 4순위 — 잔고·주문·시그널 종합 |
| `/orders` · `/journal` · `/backtest` | 보조 |

### 2-2. 사용자 흐름 (1차 시나리오)

```
[1단계] Watchlist 진입 (/watchlist)
    └─ 비어있으면 "관심종목 등록하기" CTA
    └─ 기존 등록 종목 list (현재가·등락률·당일 차트 미니)

[2단계] 전략빌더로 종목 발굴 (/strategy-builder)
    └─ DSL 또는 GUI 블록 빌더 (ADR 0020·0022)
    └─ 실행 → 결과 종목 list
    └─ 결과 row마다 [관심등록] 버튼

[3단계] 관심등록
    └─ POST /api/watchlist/{code}
    └─ Kafka watchlist.changed.v1
    └─ consumer WatchlistIngestionWorker (ADR 0028)
        ├─ KIS WS subscribe
        ├─ Yahoo cold-start fetch (1m·5m·...·1mo, ADR 0004)
        └─ KIS 당일 분봉 fetch
    └─ Watchlist 화면 즉시 반영

[4단계] 상세 보기 (/symbol/{code})
    └─ Watchlist row 클릭 → 진입
    └─ 화면 구성:
        ├─ 헤더: 종목명·현재가·등락률·거래량
        ├─ 종목 상세: PER·PBR·시총·산업·테마 (KIS + DART)
        └─ 차트: LWC v4 (ADR 0027) — interval 버튼만 (ADR 0004)

[5단계 — 옵션] 매매·시그널·일지
    └─ Dashboard·Orders·Journal에서 Watchlist 종목 기반 행위
```

### 2-3. 트랙 우선순위 재정렬

이전 트랙 순서를 다음으로 재정렬:

| 우선 | 트랙 | 의존 |
|---|---|---|
| **T-W1** | Watchlist CRUD (UI + REST + DB) | — |
| T-W2 | Watchlist Ingestion Worker | T-W1 + ADR 0028 |
| T-S1 | Symbol Detail (현재가·종목상세·차트) | T-W2 + ADR 0004 |
| T-SB1 | Strategy Builder UI 골격 | T-W1 + ADR 0020 |
| T-SB2 | DSL 평가기 + 발굴 결과 → Watchlist 등록 | T-SB1 |
| T-O1 | Order (Watchlist 종목 매매) | T-S1 + ADR 0011 |
| T-J1 | Journal | T-O1 + ADR 0012 |

### 2-4. 신규 컴포넌트

| 위치 | 컴포넌트 | 책임 |
|---|---|---|
| `domain/.../market/watchlist/` | `Watchlist`·`WatchlistEntry`·`WatchlistRepositoryPort` | 도메인 모델 |
| `api/.../controller/WatchlistController.kt` | `WatchlistController` | REST CRUD (`/api/watchlist`) |
| `api/.../application/watchlist/` | `WatchlistService` | 등록·삭제·조회 + Kafka publish |
| `api/.../infrastructure/persistence/` | `WatchlistJpaAdapter` | DB 영속 |
| `frontend/src/pages/Watchlist/` | `WatchlistPage` | 관심종목 list + CTA |
| `frontend/src/pages/StrategyBuilder/` | `StrategyBuilderPage` | 발굴 → 관심등록 |
| `frontend/src/pages/SymbolDetail/` | `SymbolDetailPage` | 현재가 + 종목상세 + 차트 |
| `frontend/src/api/watchlist.ts` | API 클라이언트 | — |

### 2-5. DB 스키마 (Flyway)

```sql
CREATE TABLE watchlist (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    code VARCHAR(20) NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT NOW(),
    note TEXT,
    UNIQUE (user_id, code)
);
CREATE INDEX idx_watchlist_user ON watchlist(user_id);
```

`user_id`는 ADR 0010 (Multi-tenant) 정합.

### 2-6. Kafka 토픽

```
watchlist.changed.v1     producer: api    consumer: consumer(IngestionWorker)
                         payload: {userId, code, action: ADDED|REMOVED}
```

## 3. 결과

- **사용자 흐름 일관성** — 관심등록한 종목만 데이터·차트·시그널이 보임
- **데이터 수집 단위 = 사용자 의도** (ADR 0028 정합)
- **Rate Limit·디스크 자연 제한** — 풀 size가 universe
- **트랙 진입 순서 명확** — T-Watchlist가 모든 후속 트랙의 prerequisite

## 4. 참고

- ADR 0028 — Watchlist 기반 Ingestion Worker (본 UI 흐름의 데이터 수집 측)
- ADR 0004 — 캔들 저장 정책 (Watchlist 진입 시 cold-start)
- ADR 0020 — KIS Strategy Builder (전략빌더 발굴 흐름)
- ADR 0009 — 프론트엔드 SPA (React + LWC v4)
- ADR 0010 — Multi-tenant 구조 (user_id)
- `frontend/src/pages/Watchlist/` — 본 ADR UI 구현 진입점
