# 외부 API 호출 정책 (KIS · yfinance · DART)

- 작성일: 2026-05-05
- 최종 갱신: R3-5c-3 PR5 (KIS 토큰 단일 store + Redis 분산 single-flight 통합)

magicJar는 외부 API에 무분별하게 호출하지 않는다. 각 API의 공식 한도와 우리 운영 정책을 정리한다.

---

## 1. 정책 한 줄 요약

> **공식 한도의 75% 이내에서만 호출. 토큰은 단일 store + 23h 갱신. 실패 시 1회 재시도(401·EGW00133·EGW00201).**

---

## 2. KIS OpenAPI

### 2-1. 공식 한도

| 항목 | KIS 한도 | 비고 |
|---|---|---|
| 토큰 발급 | **1분당 1회** (계정·앱키당) | 위반 시 EGW00133 |
| 토큰 유효 | 24시간 | 재발급 불요 |
| REST 호출 | **초당 20건 (TPS)** (계정·앱키당) | 위반 시 EGW00201 |
| WebSocket 동시 구독 | 41건 | 초과 거부 |
| 모의/실전 키 분리 | 별도 발급 | base URL도 다름 (`openapivts.koreainvestment.com:29443` vs `openapi.koreainvestment.com:9443`) |

### 2-2. 우리 정책

| 영역 | 정책 |
|---|---|
| **토큰** | `KisTokenStorePort` 단일 store. Redis HASH 영속 + Redisson RLock 분산 single-flight + RTopic broadcast (R3-5c-3 PR5). 24h 유효 — **23h 시점에 갱신 유도**(1h 마진). 모든 어댑터(api 6 + batch 3 + consumer KisHttpSession/KisTokenStore/KisWsApprovalProvider) 공유. multi-JVM에서 KIS `/oauth2/tokenP` 호출 빈도 11회/JVM → **1회/JVM** (single-flight). 게이트: `magicjar.kis.redis-token.enabled` (default false; PR5 docker rebuild로 활성). Redis 다운 시 in-memory fallback + ERROR 로그 + 메트릭(P0 알람 후보). |
| **REST 호출** | 초당 **15건** (KIS 한도의 75%). 단순 Semaphore + permit 1=1초/15ms. 사용자 path 우선, backfill·corp action은 후순위. |
| **재시도** | `401 Unauthorized` → 토큰 invalidate + 1회 재발급 후 재시도. `EGW00133` → 60초 대기 + 1회 재시도. `EGW00201` (TPS 초과) → 1초 대기 + 1회 재시도. 그 외 5xx → exponential backoff 3회. |
| **circuit breaker** | 분당 50% fail 시 5분 차단 → empty fallback. (1차는 logging만, 본격 적용은 후속 사이클) |
| **모드 분리** | `magicjar.kis.mode` flag — `vps`(모의) / `prod`(실전). base URL + 키 자동 전환. |

### 2-3. 어댑터 매핑

| 어댑터 | 모듈 | 용도 | KisTokenStorePort 사용 |
|---|---|---|---|
| `KisChartCandleAdapter` | api | D1·W·MO 사용자 차트 fetch | ✅ R3-5c-2 |
| `KisInstrumentInfoAdapter` | api | 종목 상세 (PER·PBR·시총) | ✅ R3-5c-2 |
| `KisInvestorTrendAdapter` | api | 투자자 동향 | ✅ R3-5c-2 |
| `KisMarketIndexAdapter` | api | 코스피·코스닥 지수 | ✅ R3-5c-2 |
| `KisOrderbookAdapter` | api | 호가 | ✅ R3-5c-2 |
| `KisRecentTradesAdapter` | api | 최근 체결 | ✅ R3-5c-2 |
| `RealKisRestAdapter` (batch) | batch | 룰 엔진/백테스트 D1 backfill | ✅ R3-5c-2 |
| `RealKisCorpActionAdapter` (batch) | batch | 권리주 일정 | ✅ R3-5c-3 PR4 (KisCorpActionTokenHolder 폐기) |
| `MinuteCandleBackfillJob` (batch) | batch | 분봉 backfill | ✅ R3-5c-3 PR4 |
| `KisTokenStore` (consumer) | consumer | KIS REST 토큰 (KisRestClient·시세 모듈) | ✅ R3-5c-3 PR5 (도메인 port 구현체로 일반화) |
| `KisHttpSession` (consumer) | consumer | KIS REST 사용자 키 (RealKisOrderAdapter·KisHashKeyClient) | partial — 본 사이클은 RestClient·executeWithRetry 보존, 토큰 흡수는 후속 사이클 |
| `KisWsApprovalProvider` (consumer) | consumer | KIS WS approval_key (체결통보 WS) | ✅ R3-5c-3 PR5 (KisApprovalStorePort 구현체) |
| `ApiRedisKisTokenStoreAdapter` (api) | api | Redis 분산 single-flight (시스템 키) | ✅ R3-5c-3 PR3 |
| `BatchRedisKisTokenStoreAdapter` (batch) | batch | Redis 분산 single-flight (시스템 키) | ✅ R3-5c-3 PR4 |
| `ConsumerRedisKisTokenStoreAdapter` (consumer) | consumer | Redis 분산 single-flight (시스템·사용자 키) | ✅ R3-5c-3 PR5 |
| `KisAuthHealthIndicator` (api) | api | `/actuator/health/kisAuth` DEGRADED 노출 | ✅ R3-5c-3 PR3 |

---

## 3. yfinance (Yahoo Finance — pyworker 경유)

### 3-1. 공식 한도

| 항목 | 한도 | 비고 |
|---|---|---|
| **공식 API 없음** | — | yfinance는 비공식 raw data scraping |
| REST 호출 | 명시 없음 | 보수적 초당 1~2건 권장 |
| 차단 신호 | HTTP 429 / 404 / 빈 응답 | 약 60초 후 자동 풀림 |
| 분봉(1m) 한도 | 30일 (Yahoo 제약) | 그 이상은 일봉 기준 |

### 3-2. 우리 정책

| 영역 | 정책 |
|---|---|
| **호출 위치** | `pyworker /backfill/yahoo` 단일 진입점. JVM 모듈은 직접 yfinance 호출 0건. |
| **빈도** | 종목당 **분당 1회** backfill (in-memory dedup, pyworker 측). 전역 초당 **2건** limiter. |
| **재시도** | 429·404 → 60s 대기 + 1회. yfinance 응답 빈/오류 → graceful fallback (사용자에 빈 응답). |
| **데이터 검증** | 응답 interval 검증 — `.KS`(코스피)·`.KQ`(코스닥) market 정합 (PR #153 hotfix 정합). |

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
| **빈도** | 종목당 6시간 캐시 (Redis) → 같은 종목 재조회 시 캐시 hit |
| **재시도** | 5xx → 1회. 404 → fallback (재무 정보 null) |
| **corp_code 맵** | 최초 1회 ZIP 다운로드 (~3.5MB) → in-memory 보존 |

---

## 5. 운영 가이드

### 5-1. 새 어댑터 추가 시 체크리스트

1. [ ] `KisTokenStorePort` 사용 (KIS 어댑터인 경우)
2. [ ] Rate limiter 적용 (KIS=초당 15, yfinance=초당 2, DART=종목별 6h 캐시)
3. [ ] 재시도 정책 헬퍼 사용
4. [ ] `application.yml`에 base URL + key env 명시
5. [ ] 본 정책 문서에 어댑터 매핑 한 줄 추가

### 5-2. 모니터링

- KIS 토큰 발급 횟수: `[kis-token] 발급` INFO 로그 (기대: 24h당 1회)
- EGW00133 발생: WARN 로그 → 발생 시 토큰 발급 path 점검
- yfinance 429: pyworker 로그 grep
- DART 5xx: api WARN 로그

### 5-3. KIS 키 회전

- 24h마다 자동 갱신 (KisTokenStorePort)
- 키 자체 회전 시: SettingsModal → DB AES 암호화 → KisTokenStorePort.invalidate() 호출

---

## 6. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-05 | 발행 (R3-5c-2) | 사용자 지적 — 외부 API 막무가내 호출 정책 필요 |
