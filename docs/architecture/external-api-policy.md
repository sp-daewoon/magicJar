# 외부 API 호출 정책 (KIS · Yahoo · DART)

- 작성일: TBD

magicJar는 외부 API에 무분별하게 호출하지 않는다. 각 API의 공식 한도와 우리 운영 정책을 정리한다.

> ADR 정합:
> - **ADR 0037** — KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷)
> - **ADR 0038** — KIS OAuth 24h 토큰 + Redis 분산락
> - **ADR 0039** — 종목 마스터 갱신 Cron (KIS 13시각 +5분)
> - **ADR 0028** — Watchlist 기반 Ingestion (외부 호출의 진입 단위)

---

## 1. 정책 한 줄 요약

> **공식 한도 안에서 강제 통과. 토큰은 단일 store + 23h 사전 갱신 + 분산락. 모든 KIS 호출은 공통 RateLimiter 경유 의무.**

---

## 2. KIS OpenAPI

### 2-1. 공식 한도

| 항목 | KIS 한도 | 위반 시 |
|---|---|---|
| 토큰 발급 | **1분당 1회** (계정·앱키당) | EGW00133 |
| 토큰 유효 | 24시간 | — |
| **REST 호출** | **초당 10건 (TPS)** (계정·앱키당) | **EGW00201 또는 HTTP 429** |
| WebSocket 동시 구독 | 41건 | 초과 거부 |
| 모의/실전 키 분리 | 별도 발급 | base URL도 다름 (`openapivts.koreainvestment.com:29443` vs `openapi.koreainvestment.com:9443`) |

### 2-2. Rate Limit 강제 (ADR 0037)

| 항목 | 정책 |
|---|---|
| **메커니즘** | 토큰 버킷 (Bucket4j Redis 분산 모드 권장) — 모든 JVM이 단일 bucket share |
| **공통 진입점** | `domain/.../market/ratelimit/KisRateLimiterPort` — 모든 KIS REST 호출 직전 `acquire()` 의무 |
| **우회 금지** | 어댑터별 직접 RestClient 호출 금지. ArchUnit/ktlint 빌드 게이트로 강제 |
| **우선순위 큐** | 사용자 path(현재가·차트·주문) > Watchlist Ingestion > backfill·corp action |
| **EGW00201 / 429** | 1초 대기 + 1회 재시도. 연속 5분 50% 이상 fail 시 5분 circuit breaker |

### 2-3. OAuth 토큰 (ADR 0038)

| 항목 | 정책 |
|---|---|
| **store** | Redis HASH `kis:token:{scope}` |
| **분산락** | Redisson `RLock` — multi-JVM single-flight 발급 |
| **갱신 마진** | 23h 시점 사전 갱신 (1h 마진) |
| **invalidate** | 401 응답 시 즉시 invalidate + 분산락 재발급 |
| **broadcast** | RTopic — 한 JVM이 invalidate하면 모든 JVM 알림 |
| **L1 캐시** | 각 JVM in-memory copy (Redis read 빈도 절감) |
| **fallback** | Redis 다운 시 in-memory adapter + ERROR 로그 (`[kis-auth][redis-down]`) |
| **scope 분리** | System(시세) vs User(주문·잔고) — 사용자별 분산락 |

### 2-4. 어댑터 매핑

| 어댑터 | 모듈 | 용도 |
|---|---|---|
| `KisChartCandleAdapter` | api | D1·W·MO 사용자 차트 fetch |
| `KisInstrumentInfoAdapter` | api | 종목 상세 (PER·PBR·시총) |
| `KisInvestorTrendAdapter` | api | 투자자 동향 |
| `KisMarketIndexAdapter` | api | 코스피·코스닥 지수 |
| `KisOrderbookAdapter` | api | 호가 |
| `KisRecentTradesAdapter` | api | 최근 체결 |
| `KisInstrumentMasterAdapter` | batch | 종목 마스터 sync (ADR 0039 cron 13개) |
| `KisCorpActionAdapter` | batch | 권리주 일정 |
| `KisDailyCandleBackfillJob` | batch | 일봉 backfill (Watchlist 풀) |
| `KisOrderAdapter` | consumer | 주문·체결 |
| `KisWebSocketBridge` | consumer | 실시간 tick·체결통보 |

모든 어댑터는 `KisRateLimiterPort` 통과 + `KisAuthCommonModule` 토큰 사용 의무.

---

## 3. Yahoo Finance

### 3-1. 공식 한도

| 항목 | 한도 | 비고 |
|---|---|---|
| **공식 API 없음** | — | 비공식 raw data scraping (v8 chart endpoint) |
| REST 호출 | 명시 없음 | 보수적 초당 1~2건 권장 |
| 차단 신호 | HTTP 429 / 404 / 빈 응답 | 약 60초 후 자동 풀림 |

**Interval별 최대 과거 조회 (현 시점 기준)**

| Interval | 최대 과거 | 비고 |
|---|---|---|
| **1m (1분봉)** | 최근 **7일** | 7일 초과 요청 시 응답 없음 |
| **2m / 5m / 15m / 30m / 90m** | 최근 **60일** | 1시간봉 미만 분봉 |
| **1h (60m, 1시간봉)** | 최근 **730일 (~2년)** | — |
| **1d 이상 (일봉/주봉/월봉)** | 전체 (Max) | 상장 이후 데이터 전체 |

### 3-2. 우리 정책

| 영역 | 정책 |
|---|---|
| **호출 위치** | `api /api/backfill/yahoo` 단일 진입점. RestClient 직접 호출 |
| **호출 단위** | Watchlist 풀 진입 시 cold-start (ADR 0028) — 종목당 interval별 1회 max 한도 fetch |
| **빈도** | 종목당 분당 1회 backfill (in-memory dedup). 전역 초당 2건 limiter |
| **재시도** | 429·404 → 60s 대기 + 1회. 응답 빈/오류 → graceful fallback |
| **데이터 검증** | 응답 interval 검증 + `.KS`(코스피)·`.KQ`(코스닥) market 정합 |
| **저장** | DB 영속 (ADR 0019 정합) — Yahoo 한도 안에서 max fetch 후 INSERT |

---

## 4. DART (전자공시)

### 4-1. 공식 한도

| 항목 | 한도 |
|---|---|
| API key | 무료 발급, 1일 최대 10,000건 |
| 응답 형식 | JSON / XML |

### 4-2. 우리 정책

| 영역 | 정책 |
|---|---|
| **호출 위치** | `DartFinancialAdapter` (api) — 종목 상세에서 재무·공시 fetch |
| **빈도** | 종목당 6시간 캐시 (Redis) |
| **재시도** | 5xx → 1회. 404 → fallback (재무 정보 null) |
| **corp_code 맵** | 최초 1회 ZIP 다운로드 (~3.5MB) → in-memory 보존 |

---

## 5. 운영 가이드

### 5-1. 새 어댑터 추가 시 체크리스트

1. [ ] **`KisRateLimiterPort` 통과** (KIS 어댑터인 경우, ADR 0037 강제)
2. [ ] **`KisAuthCommonModule` 사용** (KIS OAuth 토큰, ADR 0038)
3. [ ] Rate limiter 적용 (Yahoo=초당 2, DART=종목별 6h 캐시)
4. [ ] 재시도 정책 헬퍼 사용
5. [ ] `application.yml`에 base URL + key env 명시
6. [ ] 본 정책 문서에 어댑터 매핑 한 줄 추가

### 5-2. 모니터링

- KIS 토큰 발급 횟수: `[kis-token] 발급` INFO 로그 (기대: 24h당 1회/scope)
- EGW00133 발생: WARN 로그 → 발생 시 분산락 path 점검
- EGW00201 발생: WARN 로그 → Rate Limiter 큐 상태 점검
- Yahoo Finance 429: api 로그 grep
- DART 5xx: api WARN 로그
- Health: `/actuator/health/kisAuth` (DEGRADED 시 Redis 점검)

### 5-3. KIS 키 회전

- 24h마다 자동 갱신 (KisAuthCommonModule)
- 키 자체 회전 시: SettingsModal → DB AES 암호화 → `KisTokenStorePort.invalidate()` 호출
