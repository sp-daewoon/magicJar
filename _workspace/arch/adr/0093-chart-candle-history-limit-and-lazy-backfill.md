# ADR 0093: 차트 캔들 히스토리 limit 정책 + Lazy Backfill + 사용자 입력 봉수

- 상태: Proposed
- 작성일: 2026-05-20
- 결정자: architect (협의: chart-curator, frontend, backend-core)
- 영역: 차트 데이터 흐름 + REST endpoint 시그니처 + 사용자 UI
- 관련: ADR 0019(캔들 저장 정책) · ADR 0025(LWC 확장) · ADR 0028(Ingestion Worker) · ADR 0029(당일 캔들 Redis) · ADR 0037(KIS Rate Limit) · ADR 0085(chart-curator single owner) · ADR 0086(데이터 햇징 룰 14건) · ADR 0091(NXT resolver)

## §1. 맥락 (Context)

### §1-1. 사용자 결함 보고 (2026-05-20 KST)

사용자(최고 관리자) 명시 — "차트에서 보이는 캔들이 너무 적다. 일반 증권앱은 과거로 한참 스크롤 가능한데 magicJar는 막힌다." 코덱스 협업 답변 정합 검토 결과 4개 의제:

1. 사용자 입력 (300/600/1200/3000/직접입력) — "차트 조회 봉수 / 캔들 개수 / 데이터 개수 / 과거 봉 로딩 개수" 일반 증권앱 표준 명칭
2. 과거 봉 lazy backfill (왼쪽 스크롤 시 200개 추가 fetch)
3. timeframe별 최대치
4. 실시간 당일 캔들 merge

### §1-2. 코드 evidence — 현재 600봉 cap

| 위치 | 상수 | 산정 |
|------|------|------|
| `api/service/CandleRefreshService.kt:87` | `DAILY_MAX_PAGES = 6` | × KIS 100건 = **600봉** (사용자 수동 trigger path) |
| `consumer/infrastructure/kis/KisRestCandleAdapter.kt:119` | `MAX_PAGES = 6` | × KIS 100건 = **600봉** (consumer cursor pagination — 정기 ingestion·backfill) |
| 코드 주석 cover 산정 | D 약 2.5년 / W 약 11년 / M 약 50년 / Y 약 600년 |

→ 사용자가 일봉 차트에서 좌측 스크롤 시 **최대 2.5년치만 보임**. 일반 증권앱(키움·KB·NH)은 통상 10년 이상 cover. 사용자 dogfooding 결함 확정.

### §1-3. LWC lazy backfill 패턴 — 이미 부분 존재

`frontend/src/pages/Symbol/LightweightChart.tsx:329·424` — `subscribeVisibleLogicalRangeChange` 구독 + `fetchNextPage(before=oldest_ts)` 200개씩 호출 + 2단 가드(setData 후 1초 cooldown + range.from 단조감소 검증, backlog §30 fix evidence).

`api/controller/SymbolController.kt:116~135` — `GET /api/symbols/{code}/candles` endpoint는 이미 `before: String?` + `limit: Int (default 200, max 500)` 파라미터 보유.

→ **API 시그니처는 부분 완비**. 결함은:
- (a) DB candle 모수가 600봉 이하라서 `before` cursor를 600봉 시점 이전으로 보내도 응답 0건 (DB 자체에 데이터 없음)
- (b) `limit` cap 500은 일반 증권앱 표준(3000+)에 미달
- (c) LWC lazy backfill 실작동 dogfooding 검증 미실시 (코드는 있으나 실 작동률 0% 의심 — chart-curator 영역)
- (d) 사용자가 봉수를 선택할 UI 부재

## §2. 검토한 대안 (Options)

| 대안 | 장점 | 단점 |
|------|------|------|
| **A. 단순 limit 확장** — `MAX_PAGES = 6 → 60`(6000봉 cap), `endpoint limit cap 500 → 5000` | 변경량 최소(상수 2건 + cap 1건) | KIS 호출 6 → 60건/페이지 = 0.6초 → 6초 (ADR 0037 초당 10건 한도 60% 점유). 사용자 진입마다 6초 cold start 사용자 인지 비용 ↑. 대량 데이터 메모리·STOMP fanout 부하 ↑. |
| **B. Lazy backfill 도입 + 사용자 입력 + timeframe 매트릭스** | 초기 진입 빠름(200~600봉 → 1초 이내). 사용자가 필요 시점에만 과거 fetch. KIS 호출 분산(rate limit 영향 최소). 일반 증권앱 UX 표준 정합. | LWC `subscribeVisibleLogicalRangeChange` 가드 강화 필요(false trigger 영구 차단). 사용자 입력 UI 신설. timeframe별 cap 매트릭스 결정 의무. |
| **C. 백그라운드 prefetch** — 차트 진입 시 백그라운드로 timeframe별 max까지 자동 backfill | 사용자 인지 비용 0(투명 prefetch) | KIS 한도 무차별 점유(사용자가 안 본 종목도 자동 backfill). 자동 backfill 워커(ADR 0086 §14 룰)와 책임 중복. Watchlist 외 일반 조회 종목까지 cover하면 ADR 0019 §캔들 저장 정책 위배. |
| **D. 무한 스크롤 + 서버 streaming** | 일반 chart lib 표준 패턴 | LWC v4 미지원(setData 단일 array 기반). 도입 시 차트 lib 교체 비용 ↑↑. |

## §3. 결정 (Decision)

**대안 B 채택** — Lazy backfill 도입 + 사용자 입력 + timeframe 매트릭스 통합.

### §3-1. timeframe별 default + max 매트릭스 (LOCK)

| Interval | Default (초기 진입) | User 입력 옵션 | Max cap (lazy backfill 누적 한도) | KIS cover 기간 (max 기준) |
|----------|---------------------|------------------|-----------------------------------|----------------------------|
| `1m` | 600 (현행 유지) | 600 / 1200 / 2400 / 직접입력 | **4800** | 약 13 거래일 (분봉 distinct KIS 한도 30 거래일 LOCK — ADR 0019 §분봉 정책) |
| `5m`·`15m`·`30m`·`1h`·`90m` | 480 (현행 합산 가공 폭) | 480 / 960 / 1920 / 직접입력 | **3840** | 5m=약 40 거래일 / 1h=약 320 거래일 (합산 가공 — ADR 0062) |
| `1d` | 300 (1년) | 300 / 600 / 1200 / 3000 / 직접입력 | **5000** | 약 20년 |
| `1wk` | 200 (4년) | 200 / 400 / 800 / 직접입력 | **2000** | 약 38년 |
| `1mo` | 120 (10년) | 120 / 240 / 600 / 직접입력 | **1200** | 약 100년 |
| `1y` | 50 (50년) | 50 / 100 / 직접입력 | **600** | 약 600년 |

**Default 산정 원칙**: 사용자가 차트를 처음 열었을 때 "최근 1년치 일봉"이 보이는 것이 일반 증권앱 표준 + 초기 fetch 1초 이내 LOCK (KIS 3 페이지 이내).

**Max cap 산정 원칙**: 사용자가 lazy backfill로 끝까지 스크롤해도 위 수치 이상은 자동 종료(무한 페이지네이션 차단 — ADR 0086 §14 함정 가드 정합).

### §3-2. lazy backfill 정책 (LWC `subscribeVisibleLogicalRangeChange`)

- **초기 진입**: default 봉수 단일 fetch (`GET /api/symbols/{code}/candles?interval={i}&limit={default}`)
- **사용자 좌측 스크롤 끝 도달** (`range.from < 1` + 단조감소 + setData 후 1초 경과 — 기존 backlog §30 fix 가드 LOCK 유지)
- → `fetchNextPage(before=oldestTs, limit=200)` 호출
- 응답 candles.length === 0 또는 누적 ≥ max cap 시 lazy backfill 자동 종료 (LWC 상태 `hasMoreHistory=false` LOCK)
- 사용자가 max cap에 도달했음을 차트 좌측 상단 toast 1회 (`"과거 데이터 끝(최대 {max}봉)"` — 운영자 cognitive load 0 LOCK)

### §3-3. 사용자 입력 UI (frontend 영역)

`SymbolChart` 헤더 또는 timeframe selector 옆 신규 select component:

```
[봉수: 300 ▼]
   ├ 300 (기본)
   ├ 600
   ├ 1200
   ├ 3000
   └ 직접입력 → 모달 input (max cap 검증)
```

- 선택 변경 시 `setData` 재호출 (intermediate fetch — `GET /candles?limit={selected}`)
- localStorage 영속 (사용자별 선호 봉수 — 종목 단위 분리 X, 전역 1개)
- 직접입력 max 검증은 frontend(timeframe별 cap 매트릭스 정합) — 위반 시 toast "최대 {max}봉까지 가능" + cap으로 clamp

### §3-4. 실시간 당일 캔들 merge (현행 LOCK)

ADR 0029(당일 진행 중 캔들 Redis 캐시) + `CandleQueryService.getCandles()` Redis current·today 합류 path 그대로 유지. lazy backfill은 과거 candle 영역만 책임 → 실시간 merge와 책임 분리.

## §4. 모듈 책임 분담 (LOCK)

| 영역 | 책임 | 변경 매트릭스 |
|------|------|----------------|
| **architect** | 본 ADR + 봉수 매트릭스 결정 + ADR 0085·0086 정합 검증 | ADR 0093 발행 + ADR README + backlog §81 + master-architecture §10·§13 |
| **chart-curator** (single owner — ADR 0085 §책임 영역) | spec 발행(`_workspace/specs/2026-05-20-chart-candle-history-limit-spec.md`) + LWC lazy backfill 실작동 dogfooding 검증 evidence + 가드 강화(false trigger 영구 차단) + max cap 도달 toast | spec.md + 후속 PR(LWC + admin 검증) |
| **backend-core** | `application.yml` property 화(timeframe별 매트릭스) + endpoint `limit` cap 500 → 5000 확장 + `MAX_PAGES` 상수 property 외부화 | api/consumer 양 모듈 Kotlin ~50 LOC |
| **frontend** | 봉수 input UI(select + 직접입력 모달) + localStorage 영속 + clamp 검증 | React ~80 LOC + Tailwind |
| **market-data** | KIS Rate Limit ADR 0037 정합 검증 (60 페이지 호출 시 ADR 0037 §2-2 backoff 매트릭스 발동 path) — Phase 2 신규 필요 시 별도 발화 | (없음 — ADR 0037 Phase 1·2 baseline LOCK 활용) |

**chart-curator 1차 lens 의무** (ADR 0087 페르소나 매트릭스 정합 — 자본 path 직결 차트 영역 = 보수 lens 우선): backend-core 4 PR + frontend 1 PR + chart-curator 1 PR 모두 chart-curator 🔴/🟡/🔵 검토 후 머지.

## §5. ADR 0037 Rate Limit 정합 (LOCK)

| Scenario | KIS 호출 | ADR 0037 한도 영향 |
|----------|----------|---------------------|
| 사용자 진입 default 일봉 300봉 | 3 페이지 = 0.3초 | 30% 일시 점유(허용) |
| 사용자가 봉수 3000 선택 | 30 페이지 = 3초 | 300% 누적 → ADR 0037 Bucket4j(spec PR #564) **acquire blocking polling 0.1초 단위 회복**으로 다른 사용자 path 영향 미미 |
| Lazy backfill 200개씩 추가 | 페이지당 2회 = 0.2초 | 사용자가 천천히 스크롤 → KIS 호출 자연 분산 |
| Max cap 1d=5000봉 누적 | 누적 50 페이지 = 5초 | 사용자 1명 5초 점유 — 다른 종목 진입 사용자에 영향 미미(USER_PATH priority — ADR 0037 §2-1 LOCK) |

→ **추가 backoff·throttle 정책 신설 0** — ADR 0037 baseline으로 충분 cover. 단, **5000봉 일괄 호출 시점은 chart-curator dogfooding evidence 의무**(P95 latency + 사용자 인지 비용 측정).

## §6. 영향 (Consequences)

### §6-1. 긍정
- 일반 증권앱 표준 UX 정합 (3000봉+ cover)
- 사용자 봉수 선택권 부여 → 차트 사용성 ↑
- 초기 진입 latency 1초 이내 LOCK (default 300봉 = 3 페이지)
- Lazy backfill로 KIS 호출 자연 분산 → ADR 0037 한도 영향 최소
- 마이그레이션 0 (DB schema 변경 0, API 시그니처 add-only — `limit` cap 확장만)

### §6-2. 부정
- LWC `subscribeVisibleLogicalRangeChange` 가드 강화 필요(false trigger 추가 evidence 누적 시 chart-curator 보강 의무)
- 사용자 입력 봉수 5000 선택 시 5초 cold start (UX 인지 비용 — toast `"불러오는 중... (최대 {N}초)"` 안내 의무)
- DB candle row 누적 증가 (현행 600봉/종목 → 최대 5000봉/종목 — 8배). ADR 0019 §캔들 저장 정책 watchlist 기반 모수 산정 재검증 의무(chart-curator + market-data 합작)
- max cap 도달 사용자 인지 비용 — toast 1회로 cover (영구 banner 0)

### §6-3. 후속 과제 (post-ADR)
- chart-curator spec — LWC lazy backfill 실작동 dogfooding evidence + 가드 §11(false trigger 차단) 추가 검토
- backend-core PR — `application.yml` `magicjar.candle.max-pages-by-interval` property 신설 + `CandleRefreshService` + `KisRestCandleAdapter` 양 모듈 참조 합류
- frontend PR — 봉수 select UI + localStorage `magicjar.chart.preferred-bar-count` key
- DB row 누적 영향 monitoring — Phase 2 chart-curator 발화 시점 (별도 backlog 의제)

## §7. ADR 0085·0086 정합 검증 (LOCK)

- **ADR 0085 §책임 영역** — `frontend/src/components/chart/*` + `frontend/src/pages/Symbol/SymbolChart·LightweightChart` chart-curator single owner LOCK. 본 ADR의 lazy backfill 가드 강화 + 봉수 input UI 역시 chart-curator 1차 lens 의무.
- **ADR 0086 §14 룰** — KIS 호출 한도 cover 의무 + cursor 페이지네이션 algorithm 의무 + helper class 분리 의무. 본 ADR의 60 페이지 backfill은 `KisCursorPaginator`(chart-curator backlog §79 후속 의제) helper class 패턴으로 분리 후 회귀 test 1건 의무 LOCK.
- **ADR 0086 §6 passive 가드** — `MAX_PAGES` 안전 한도는 timeframe별 매트릭스로 보강 (단일 6 LOCK → 6 / 12 / 50 / 60 등 차등).

## §8. 결정 매트릭스 요약

| 항목 | 결정 |
|------|------|
| 기본 patten | Lazy backfill (대안 B) |
| Default 봉수 | timeframe별 매트릭스 (1d=300 / 1m=600 / 1y=50 등) |
| Max cap | timeframe별 매트릭스 (1d=5000 / 1m=4800 / 1y=600 등) |
| 사용자 입력 UI | select + 직접입력 모달 + localStorage |
| API endpoint | 기존 `GET /candles?before=&limit=` 재사용 + `limit` cap 500 → 5000 |
| MAX_PAGES 상수 | property 외부화 (`magicjar.candle.max-pages-by-interval` map) |
| ADR 0037 Rate Limit | baseline LOCK으로 cover (추가 정책 0) |
| chart-curator 1차 lens | 자본 path 직결 = 보수 lens 의무(ADR 0085·0087) |
| 마이그레이션 | 0 (add-only) |
| Phase | (1) architect ADR (본) → (2) chart-curator spec → (3) backend-core property+cap → (4) frontend UI → (5) chart-curator LWC 가드+dogfooding |

## §9. 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-05-20 | ADR 0093 신설 (Proposed) — 차트 캔들 600봉 cap 결함 fix + lazy backfill + 사용자 입력 + timeframe 매트릭스. chart-curator spec 발행 dispatch 대기. (architect, opus 4.7 1M) |
