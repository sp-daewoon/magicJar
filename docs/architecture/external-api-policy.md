# 외부 API 호출 정책 (KIS · DART)

- 작성일: TBD
- 2026-05-11 갱신: **Yahoo Finance §3 전면 제거** — ADR 0057 (KIS OpenAPI 단일 데이터 출처) 정합. 사용자 명시 "kis에 있는 데이터만 사용하라고" (2026-05-11 dogfooding).
- 2026-05-11 amend: 정보 수집 API는 **prod credential 단일 채널** (모의/실전 분기는 주문 라우터 한정 — 사용자 명시 정책 정정). ADR 0019 supersede 인용 제거 → "Accepted (Yahoo 의존 부분만 보류)"로 정정 — 5m~1h은 1m 합산 가공 방향 확정 (backlog §31 트랙).
- 2026-05-12 amend (v3.2.3 doc cut — backlog §35): **§2-5 NXT 매트릭스 추가** (KIS `fid_cond_mrkt_div_code` 옵션 J/NX/UN 본문 추가) + **KOSPI→UN / KOSDAQ→J 분기 룰 명시** (PR #304 구현 결과 인용) + **§2-5-A·§2-5-B 신설** (raw 출처 인용 — `inquire_time_dailychartprice.py` + `inquire_daily_itemchartprice.py`). ADR 0062·0063 Accepted 정합 (5m~1h 백엔드 precomputed 합산 + NXT 08:00~20:00 KST cover + threshold 700).
- 2026-05-20 amend (ADR 0091): **KOSPI→UN / KOSDAQ→J market-level 분기 폐기**. `UN`은 종목 단위 NXT 후보(`is_kospi200` 또는 `is_kosdaq150`)에만 우선 적용하고, 응답 0건이면 `J`로 fallback한다.

magicJar는 외부 API에 무분별하게 호출하지 않는다. 각 API의 공식 한도와 우리 운영 정책을 정리한다.

> ADR 정합:
> - **ADR 0057** — KIS OpenAPI 단일 데이터 출처 (Yahoo·네이버 등 보조 출처 폐기)
> - **ADR 0062** — 5m~1h 합산 가공 백엔드 precomputed (2026-05-11 Accepted)
> - **ADR 0063** — NXT/통합거래 매트릭스 cover (2026-05-11 Accepted)
> - **ADR 0091** — NXT 통합 시장코드 종목 단위 resolver (2026-05-20 Accepted, `UN` 후보 + `J` fallback) ← **최신**
> - **ADR 0037** — KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷)
> - **ADR 0038** — KIS OAuth 24h 토큰 + Redis 분산락
> - **ADR 0039** — 종목 마스터 갱신 Cron (KIS 13시각 +5분)
> - **ADR 0028** — Watchlist 기반 Ingestion (외부 호출의 진입 단위)
> - **ADR 0019** — 캔들 저장 정책 (Accepted, 2026-05-12 §2 NXT/합산 5종 재통합)

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

**magicJar credential 정책 (사용자 명시 2026-05-11)**: 정보 수집 API(시세·캔들·호가·종목 마스터 등)는 **prod(실전) credential 단일 채널** 사용. `AccountType.REAL` 자격으로 KIS REST/WS 호출. 모의/실전 분기는 **주문 라우터(거래 액션)에만** 적용. 정보 수집에서 모의 credential 사용 0건.

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

### 2-5. 캔들 데이터 KIS 단일 출처 (ADR 0057·0062·0063 정합)

2026-05-11 ADR 0057 발효 이후 magicJar의 캔들 데이터는 **KIS OpenAPI 단일 출처**다. Yahoo Finance·네이버 금융 등 보조 출처는 폐기. **모든 호출은 prod credential 단일 채널** (사용자 명시 정책 정정 2026-05-11).

**2026-05-12 갱신 (ADR 0062·0063 Accepted 재통합 — v3.2.3 doc cut)**: 5m·15m·30m·90m·1h은 **백엔드 precomputed 합산** (CandleAggregator 도메인 본체 부활, PR #302 D 구현 + PR #303 AggregateBackfillRunner). 1m baseline은 NXT 통합 cover (08:00~20:00 KST, ADR 0063).

**2026-05-20 갱신 (ADR 0091)**: `KOSPI=UN/KOSDAQ=J` market-level 분기는 폐기. `UN`은 종목 단위 NXT 후보에만 우선 적용하고, 빈 응답이면 `J`로 fallback한다.

| Interval | KIS endpoint | tr_id | fid_cond_mrkt_div_code | 비고 |
|---|---|---|---|---|
| 틱 | WebSocket `H0STCNT0` | — | — | Redis only |
| 1m (1분봉) — 당일 | `inquire-time-itemchartprice` | `FHKST03010200` | NXT 후보=`UN`→`J`, 그 외=`J` | 당일 한정 — Redis & DB (NXT cover 08:00~20:00 KST) |
| **1m (1분봉) — 30 거래일 페이지네이션** | `inquire-time-dailychartprice` | **`FHKST03010230`** | **NXT 후보=`UN`→`J`, 그 외=`J`** | **NXT cover — 720 bar/일 × 30 거래일 (ADR 0062 옵션 2 채택)** |
| **5m·15m·30m·90m·1h (백엔드 합산)** | — | — | — | **CandleAggregator 합산 (ADR 0062 §3 권장안 A-1 백엔드 precomputed). KIS REST 호출 0건** |
| 1d (일봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='D') | NXT 후보=`UN`→`J`, 그 외=`J` | 전체 기간 — DB 영속 |
| 1wk (주봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='W') | NXT 후보=`UN`→`J`, 그 외=`J` | 전체 기간 — DB 영속 |
| 1mo (월봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='M') | NXT 후보=`UN`→`J`, 그 외=`J` | 전체 기간 — DB 영속 |
| 1y (년봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='Y') | NXT 후보=`UN`→`J`, 그 외=`J` | 전체 기간 — DB 영속 |
| 현재가 | `inquire-price` | `FHKST01010100` | `J` | 시가·고가·저가·거래량 + 등락 |

### 2-5-A. `fid_cond_mrkt_div_code` 매트릭스 (ADR 0063·0091 정합)

KIS raw spec — 시장 분류 코드 옵션 3종:

| 옵션 | KIS 공식 의미 | magicJar 적용 |
|---|---|---|
| **J** | KRX 주식 (KOSPI + KOSDAQ 포함) | 기본값. `UN` 후보가 빈 응답이면 fallback |
| **NX** | NXT 단독 | 1차 MVP 사용 안 함 (통합 캔들 우선) |
| **UN** | KRX + NXT 통합 | `is_kospi200=true` 또는 `is_kosdaq150=true`인 NXT 후보 종목 우선 시도 |

**분기 룰** (ADR 0091):
- `instrument.is_kospi200=true` 또는 `instrument.is_kosdaq150=true` → `UN` 우선, 응답 0건이면 `J` fallback
- 그 외 종목, `UNKNOWN/기타` → `J` (안전 기본값)
- KOSPI/KOSDAQ market 이름만으로 `UN`을 선택하지 않는다.

**raw 출처 인용 (헌법 §3 KIS-first)**:
- 1분봉 페이지네이션: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`
  - spec line: `fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:주식, NX:NXT, UN:통합)`
- 일봉: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py`
  - spec line: `fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:KRX, NX:NXT, UN:통합)`

### 2-5-B. NXT 시간대 cover 매트릭스

- **NXT pre-hour**: 08:00 ~ 09:00 KST
- **NXT main-hour**: 09:00 ~ 15:30 KST (KRX 정규장과 동일)
- **NXT after-hour**: 15:30 ~ 20:00 KST
- 1m baseline 720 bar/일 (KRX 정규장 390 + NXT pre/after-hour 330)
- `MIN_1M_CANDLE_THRESHOLD = 700L` (ADR 0063 §2-D 옵션 C 채택 — 720의 97% 마진)
- 5m~1h 합산 boundary 정렬 매트릭스는 ADR 0062 §9-2 참조 (ADR 0019 §2-1·ADR 0029 §2-1 정합)

**raw 출처 (헌법 §3)**: https://github.com/koreainvestment/open-trading-api (examples_user/domestic_stock + examples_llm/domestic_stock 디렉토리)

---

## 3. Yahoo Finance — **폐기 (ADR 0057, 2026-05-11)**

ADR 0019 시점에서 분봉 데이터 보조 출처로 활용했으나, 2026-05-11 사용자 dogfooding에서 다음 결함 발견 후 전면 폐기:

- root cause: Yahoo v8 chart endpoint(`https://query1.finance.yahoo.com/v8/finance/chart/{ticker}`)가 한국 오래된 상장종목 `interval=1d&range=max` 조합에서 **monthly downsample** 응답 반환 → 일봉 위치에 월봉이 INSERT되어 사용자 차트 결함
- 사용자 명시 결정 (2026-05-11): "야후꺼 쓰지 말라니깐" / "kis에 있는 데이터만 사용하라고"

후속 처리:
- ADR 0019 — Accepted (Yahoo 의존 부분만 보류 — 폐기 아님, 사용자 명시 "ADR 폐기는 하지말어 백로그로 남겨")
- 본 §3 — 폐기 표기만 보존 (rollback 사례 발생 시 §3-old 복원 참조 — ADR 0057 §4-2)
- `consumer/.../infrastructure/yahoo/YahooCandleFetcherAdapter.kt` — `@Deprecated` 마킹 (별도 market-data sub-agent PR)

**Yahoo 부활 시나리오**: ADR 0057 §4-2 Rollback 시나리오 정독 의무 (KIS rate limit 영향 측정 또는 `FHKST03010230` 도입 후 5m~1h 합산 성능 부족이 발견될 시만 검토).

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

1. [ ] **캔들 데이터는 KIS 단일 출처 의무 (ADR 0057)** — Yahoo·네이버 등 보조 출처 신규 어댑터 발행 금지
2. [ ] **정보 수집 API는 prod credential 단일 채널 의무 (사용자 명시 2026-05-11)** — `AccountType.REAL` 자격으로만 호출. 모의 credential 사용 0건. 모의/실전 분기는 주문 라우터에만 적용.
3. [ ] **`KisRateLimiterPort` 통과** (KIS 어댑터인 경우, ADR 0037 강제)
4. [ ] **`KisAuthCommonModule` 사용** (KIS OAuth 토큰, ADR 0038)
5. [ ] Rate limiter 적용 (DART=종목별 6h 캐시)
6. [ ] 재시도 정책 헬퍼 사용
7. [ ] `application.yml`에 base URL + key env 명시
8. [ ] 본 정책 문서에 어댑터 매핑 한 줄 추가
9. [ ] **KIS-first raw fetch 의무 (헌법 §3)** — 새 KIS endpoint 도입 시 raw URL KDoc 인용

### 5-2. 모니터링

- KIS 토큰 발급 횟수: `[kis-token] 발급` INFO 로그 (기대: 24h당 1회/scope)
- EGW00133 발생: WARN 로그 → 발생 시 분산락 path 점검
- EGW00201 발생: WARN 로그 → Rate Limiter 큐 상태 점검
- DART 5xx: api WARN 로그
- Health: `/actuator/health/kisAuth` (DEGRADED 시 Redis 점검)

### 5-3. KIS 키 회전

- 24h마다 자동 갱신 (KisAuthCommonModule)
- 키 자체 회전 시: SettingsModal → DB AES 암호화 → `KisTokenStorePort.invalidate()` 호출
