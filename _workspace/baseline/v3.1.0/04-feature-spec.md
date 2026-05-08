# 04 — W1 7 트랙 기능 명세 상세 (v3.1.0)

> 기준: 2026-05-08 (W1 마감, v3.1.0 베이스라인 컷)
> 각 트랙은 실제 코드 path + 시그니처 수준으로 기술. 추측 0건.

---

## §1 T-ONBOARD ★★ — 셋업 마법사

### 1-1 개요

신규 사용자가 magicJar를 처음 사용할 때 완료해야 하는 7단계 셋업 마법사. KIS 실전/모의 키 검증 + AES-256-GCM 암호화 DB 영속. 셋업 미완 시 `OnboardingGuardFilter`가 `/api/onboarding/**` 이외의 모든 API를 401 차단.

### 1-2 REST 엔드포인트

소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/OnboardingController.kt`

| 메서드 | 경로 | 요청 DTO | 응답 DTO | 비고 |
|--------|------|---------|---------|------|
| GET | `/api/onboarding/status` | - | `OnboardingStatus` | 인증 불요, 4 항목 체크 |
| POST | `/api/onboarding/kis/validate` | `KisValidateRequest` | `KisValidateResponse` | KIS OAuth tokenP 검증 |
| POST | `/api/onboarding/kis/mock` | `KisMockRequest` | `KisMockResponse` | 모의 서버 openapivts:29443 |
| POST | `/api/onboarding/system-config` | `SystemConfigRequest` | `SystemConfigResponse` | DART 키, Slack webhook |
| POST | `/api/onboarding/complete` | `OnboardingCompleteRequest` | `OnboardingCompleteResponse` | UserOnboardedEvent 발행 |

주요 응답 schema:
```
OnboardingStatus {
    hasUserAccount: Boolean,
    hasRealKisCredential: Boolean,
    hasMockKisCredential: Boolean,
    hasSystemConfig: Boolean
}

KisValidateRequest {
    userId: String,
    displayName: String,
    accountNumber: String (8자리),
    appKey: String,
    secretKey: String
}

KisValidateResponse {
    userId: String,
    validated: Boolean,
    maskedAccount: String
}
```

### 1-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드/메서드 |
|-------------|------|----------------|
| `UserAccount` | `domain/user/UserAccount.kt` | userId, displayName, maskedAccount |
| `UserKisCredential` | `domain/user/UserKisCredential.kt` | userId, accountType(REAL/MOCK), accountNumber, appKeyEnc(BYTEA), secretKeyEnc(BYTEA) |
| `SystemConfig` | `domain/user/SystemConfig.kt` | dartApiKey, notificationSlackWebhook |
| `UserOnboardedEvent` | `domain/user/UserOnboardedEvent.kt` | Spring ApplicationEvent |
| `SecretCipher` | `domain/secret/port/SecretCipher.kt` | encrypt(plaintext): ByteArray, decrypt(ciphertext): String |
| `UserKisCredentialRepository` | `domain/user/port/UserKisCredentialRepository.kt` | findByUserId, save |

### 1-4 영속 테이블

| 테이블 | Flyway | 핵심 컬럼 |
|--------|--------|----------|
| `system_config` | V1 | id=1 singleton, dart_api_key BYTEA, notification_slack_webhook BYTEA |
| `user_account` | V1+V2 | user_id PK, masked_account GENERATED, last_login_at |
| `user_kis_credential` | V1+V2 | (user_id, account_type) PK, app_key_enc BYTEA, secret_key_enc BYTEA, created_at, updated_at |

### 1-5 외부 통합

- KIS OAuth tokenP: `POST https://openapi.koreainvestment.com:9443/oauth2/tokenP` (실전)
- KIS OAuth tokenP: `POST https://openapivts.koreainvestment.com:29443/oauth2/tokenP` (모의)
- 소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/kis/KisAuthValidator.kt`

### 1-6 Kafka 이벤트

`UserOnboardedEvent` — Spring ApplicationEvent (Kafka 미사용). consumer 모듈이 이벤트를 수신하여 polling 시작. 소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/OnboardingService.kt`

### 1-7 frontend 진입점

`_workspace/baseline/v3.1.0/05-ui-inventory.md` §1 OnboardingWizard 참조 (TBD — frontend sub-agent 담당)

### 1-8 관련 PR + ADR

- PR #5a (V1 baseline), PR #6 (T-ONBOARD 본격), PR #21b (키 보관 정책 정합)
- ADR 0046 (온보딩 7-step 설계), ADR 0041 (AES-256-GCM 키 관리)

### 1-9 현재 한계 + W2 이후 예정

- v3.1.0: 단일 사용자(single-tenant). `user_account` 첫 row 자동 resolve.
- KIS 키 화면 직접 입력 정책 (사용자 메모리 `project_kis_key_management.md`).
- W4 이후: multi-user 지원, OAuth2 인증 세션.

---

## §2 T-W1 ★ — Watchlist CRUD + Position Bootstrap

### 2-1 개요

관심종목 등록/조회/삭제. magicJar의 1순위 진입점 — 관심종목 없이는 시세 수집 미동작. 등록 시 Kafka `watchlist.changed.v1` 발행 → T-W2 ingestion worker 트리거. Position Bootstrap은 부팅 시 KIS 잔고 조회 → DB upsert → Redis warm.

### 2-2 REST 엔드포인트

소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/WatchlistController.kt`

| 메서드 | 경로 | 요청 DTO | 응답 DTO | 비고 |
|--------|------|---------|---------|------|
| GET | `/api/watchlists` | - | `List<WatchlistEntryDto>` | priority ASC + created_at DESC |
| POST | `/api/watchlists` | `WatchlistAddRequest` | `WatchlistEntryDto` | 201 Created + Location 헤더 |
| DELETE | `/api/watchlists/{code}` | - | 204 No Content | code 6자리 검증 |

주요 DTO schema:
```
WatchlistAddRequest {
    code: String (@Pattern "^\\d{6}$"),
    priority: Int? = 100
}

WatchlistEntryDto {
    id: Long, userId: String, code: String,
    name: String?, market: String?,
    priority: Int, createdAt: Instant,
    type: String?,             // PR-21b: STOCK|PREFERRED (null=미sync)
    securitiesGroup: String?,  // ST=보통주
    marketCapPrev: Long?,
    tradingHalt: Boolean,
    managementStock: Boolean,
    industryMedium: String?,
    isKospi200: Boolean, isKosdaq150: Boolean,
    delisted: Boolean          // active=false 라벨
}
```

응답 정책 (PR-21b):
- `securitiesGroup='ST'` 또는 `type IN (STOCK, PREFERRED, UNKNOWN)` 만 포함
- ETF·ETN·REIT·WARRANT 제외 (DB row 보존, 응답에서만 필터)
- `active=false` → `delisted=true` 라벨로 노출 (사용자 직접 제거 결정)

소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/WatchlistService.kt:76-92`

### 2-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드 |
|-------------|------|---------|
| `WatchlistEntry` | `domain/watchlist/WatchlistEntry.kt` | userId, code, priority |
| `WatchlistRepository` | `domain/watchlist/port/WatchlistRepository.kt` | findByUserId, add, remove |
| `Position` | `domain/position/Position.kt` | userId, code, quantity, avgPrice |
| `PositionRepository` | `domain/position/port/PositionRepository.kt` | upsertAll, findByUserId |
| `KisBalancePort` | `domain/position/port/KisBalancePort.kt` | fetchBalance(userId, accountType): List<Position> |

### 2-4 영속 테이블

| 테이블 | Flyway | 핵심 컬럼 |
|--------|--------|----------|
| `watchlist` | V1+V3 | id BIGSERIAL, user_id, code, priority, UQ(user_id,code) |
| `position` | V1+V3 | id BIGSERIAL, user_id, code, quantity, avg_price, UQ(user_id,code) |

### 2-5 외부 통합

- Position Bootstrap: KIS 잔고 조회 (TTTC8434R 실전 / VTTC8434R 모의)
- 소스: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/bootstrap/PositionBootstrapRunner.kt`

### 2-6 Kafka 이벤트

| 토픽 | 방향 | payload |
|------|------|---------|
| `watchlist.changed.v1` | publish (WatchlistService) | `{userId, code, action:"ADDED"\|"REMOVED", occurredAt}` |

### 2-7 frontend 진입점

`05-ui-inventory.md` §2 WatchlistPage, DashboardPage 참조 (TBD)

### 2-8 관련 PR + ADR

- PR #7 (T-W1 Watchlist CRUD), PR #8 (Position Bootstrap), PR #20 (instrument join), PR #21b (KIS-first 메타 필드)
- ADR 0028 (Watchlist 기반 Ingestion 정책), ADR 0040 (Watchlist CRUD 설계), ADR 0042 (Position Bootstrap)

### 2-9 현재 한계 + W2 이후 예정

- priority 편집 API 미구현 (v3.1.0 — W2 예정).
- instrument stub (market=UNKNOWN) 등록 시 name/market null → T-IM1 InstrumentSyncJob 이후 갱신.
- Position Bootstrap: KIS 응답 실패 시 해당 user KillSwitch auto-ON.

---

## §3 T-W2 ★ — Ingestion Worker

### 3-1 개요

관심종목 등록 이벤트를 수신하여 KIS WS / polling fallback으로 실시간 시세 수집 시작. 41 stream slot 예산 관리 (H0STCNT0 30 + H0STASP0 10 + H0STCNI0 1). Yahoo Finance cold-start로 과거 캔들 500일치 선수집.

### 3-2 REST 엔드포인트

직접 REST endpoint 없음 — Kafka event-driven.

### 3-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드/메서드 |
|-------------|------|----------------|
| `KisWsPort` | `domain/market/port/KisWsPort.kt` | subscribe(codes), unsubscribe(codes), subscribedCodes(), subscribeOrderbook, unsubscribeOrderbook |
| `SubscriptionPolicy` | `domain/market/port/SubscriptionPolicy.kt` | PRICE_TICK_MAX=30, ORDERBOOK_MAX=10, priceTickSlotsAvailable(current) |
| `YahooCandleFetcher` | `domain/market/port/YahooCandleFetcher.kt` | fetchCandles(code, interval, limit): List<Candle> |
| `CandleRepository` | `domain/market/port/CandleRepository.kt` | countByCodeAndIntervalIn(code, intervals): Long, batchInsert |
| `SourceTracker` | `domain/market/port/SourceTracker.kt` | set(userId, code, source), remove, get |
| `QuoteCachePort` | `domain/market/port/QuoteCachePort.kt` | getLatestPrice(code), putLatestPrice |

### 3-4 영속 테이블

| 테이블 | Flyway | 비고 |
|--------|--------|------|
| `candle` | V1+V8 | Yahoo cold-start 영속 (source='YAHOO') |
| `watchlist` | V1+V3 | 구독 종목 목록 기준 |

Redis:
- `candle:current:{code}:1m` TTL 2m — 진행 중 1분봉
- `candle:today:{code}:1d` TTL 24h — 당일 1d 캔들
- `quote:last:{code}` TTL 7d — 최종 체결가

### 3-5 외부 통합

- KIS WS: `wss://ops.koreainvestment.com:21000` (실전) / `wss://ops.koreainvestment.com:31000` (모의)
- tr_id: H0STCNT0 (체결가), H0STASP0 (호가), H0STCNI0 (체결통보 — T-O 예정)
- Yahoo Finance: `consumer/src/main/kotlin/.../consumer/infrastructure/yahoo/YahooCandleFetcherAdapter.kt`
- KIS REST 폴백: `consumer/src/main/kotlin/.../consumer/infrastructure/kis/KisRestPriceAdapter.kt`

소스: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ws/KisWebSocketBridge.kt`

### 3-6 Kafka 이벤트

| 토픽 | 방향 | groupId |
|------|------|---------|
| `watchlist.changed.v1` | consume | magicjar-consumer-watchlist-ingestion |
| `market.tick.v1` | publish | — |
| `market.candle.minute.closed.v1` | publish (TickToMinuteAggregator) | — |
| `market.orderbook.v1` | publish | — |
| `symbol.detail.command.v1` | consume | — |

### 3-7 frontend 진입점

`05-ui-inventory.md` §3 DashboardPage (실시간 가격 배지), §4 SymbolDetailPage (호가 패널) 참조 (TBD)

### 3-8 관련 PR + ADR

- PR #9a (KIS WS 연결), PR #9b (H0STCNT0 파서), PR #10b (H0STASP0 호가 + 41 slot), PR #9c (Yahoo cold-start)
- ADR 0014 (Fallback 3-tier), ADR 0029 (캔들 Redis 임시 버퍼), ADR 0048 (KIS WS 41 slot 관리)

### 3-9 현재 한계 + W2 이후 예정

- TickFallbackEmitter T2: 구독 종목 순회 stub — 실제 구독 목록 노출 API 추가 후 활성화 예정.
- H0STCNI0 체결통보: slot reserve만, 핸들러 미구현 (T-O W3 예정).
- polling fallback: WatchlistPollingWorker 5초 주기, KIS REST inquire-price 호출.

---

## §4 T-S1 ★ — Symbol Detail

### 4-1 개요

종목 상세 화면에서 사용하는 현재가·캔들·호가 API. 1순위: Redis (실시간), 2순위: KIS REST fallback. 호가는 Kafka 명령 → consumer OrderbookSubscriptionRouter → KIS WS H0STASP0 → STOMP fanout.

### 4-2 REST 엔드포인트

소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SymbolController.kt`

| 메서드 | 경로 | 파라미터 | 응답 DTO |
|--------|------|---------|---------|
| GET | `/api/symbols/{code}` | - | `SymbolDto` |
| GET | `/api/symbols/{code}/candles` | interval(1m/5m/30m/1h/1d), before(ISO8601), limit(1~500) | `CandlesResponseDto` |
| POST | `/api/symbols/{code}/orderbook/subscribe` | - | `OrderbookSubscribeResponse` |
| POST | `/api/symbols/{code}/orderbook/unsubscribe` | - | `OrderbookUnsubscribeResponse` |

주요 DTO schema:
```
SymbolDto {
    code: String, name: String, market: String,
    price: BigDecimal?, change: BigDecimal?, changeRate: BigDecimal?,
    per: BigDecimal?, pbr: BigDecimal?, marketCap: Long?,
    sector: String?, stale: Boolean, asOf: Instant?
}

CandlesResponseDto {
    candles: List<CandleDto>,  // ts ASC 정렬 (LWC v4 setData 기대)
    hasMore: Boolean,
    nextBefore: String?        // ISO8601 cursor
}

CandleDto {
    ts: Long (epoch ms), open: BigDecimal, high: BigDecimal,
    low: BigDecimal, close: BigDecimal, volume: Long
}
```

### 4-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드 |
|-------------|------|---------|
| `PriceMetrics` | `domain/market/PriceMetrics.kt` | code, price, change, changeRate, per, pbr, marketCap |
| `Candle` | `domain/market/candle/Candle.kt` | code, interval, ts, openRaw, closeRaw, openAdj, closeAdj, adjustmentFactor |
| `OrderbookSnapshot` | `domain/market/OrderbookSnapshot.kt` | code, ts, bids, asks |
| `KisRestPriceMetricsPort` | `domain/market/port/KisRestPriceMetricsPort.kt` | fetchPriceMetrics(code): PriceMetrics |
| `QuoteCachePort` | `domain/market/port/QuoteCachePort.kt` | getLatestPrice(code), putLatestPrice |

### 4-4 영속 테이블

| 테이블 | Flyway | 비고 |
|--------|--------|------|
| `candle` | V1+V8 | interval IN (1m/5m/30m/1h/1d) |

Redis:
- `quote:last:{code}` — SymbolMetaService 1순위
- `candle:today:{code}:1d` — CandleQueryService 당일 합류

### 4-5 외부 통합

- KIS REST: `GET /uapi/domestic-stock/v1/quotations/inquire-price` (FHKST01010100)
- 소스: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisRestPriceAdapter.kt`

### 4-6 Kafka 이벤트

| 토픽 | 방향 | payload |
|------|------|---------|
| `symbol.detail.command.v1` | publish (SymbolController) | `{action:"ENTER"\|"EXIT", code, userId, ts}` |
| `market.orderbook.v1` | (consumer 모듈이 STOMP fanout 처리) | — |

### 4-7 frontend 진입점

`05-ui-inventory.md` §4 SymbolDetailPage 참조 (TBD) — LWC v4 TradingView Lightweight Charts 연동.

### 4-8 관련 PR + ADR

- PR #10a (Symbol REST), PR #10b (호가 STOMP), PR #12 (캔들 API), PR #29c (T-S1 잔재)
- ADR 0014 (Fallback 3-tier), ADR 0019 (캔들 interval 정책), ADR 0048 (호가 10 slot LRU)

### 4-9 현재 한계 + W2 이후 예정

- PER·PBR·시총: KIS inquire-price 응답에서 파싱. 재무 상세(T-IND)는 W2 예정.
- 캔들 before 파라미터: DB cursor pagination. 1m·5m 대용량 cursor 성능 최적화 미완.
- 호가 slot: LRU 10 — 11번째 진입 시 가장 오래된 종목 자동 eviction (ADR 0048 §2-1).

---

## §5 T-MS1 — Market Session FSM + Fallback 3-tier + REJECTED Audit

### 5-1 개요

한국 주식시장 세션 상태 머신 (FSM 4 상태: Regular·PreMarket·PostMarket·Closed). 비영업일 판별 (KRX market_calendar DB 기반 + KIS chk-holiday 동기화). Fallback 3-tier 시세 소스 관리. 주문 거부 감사 로그.

### 5-2 REST 엔드포인트

직접 REST endpoint 없음. 내부 서비스로 consumer/batch 전역에서 사용.

### 5-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드/메서드 |
|-------------|------|----------------|
| `MarketSession` | `domain/market/session/MarketSession.kt` | sealed: Regular, PreMarket, PostMarket, Closed(reason) |
| `MarketSessionResolver` | `domain/market/session/MarketSession.kt` | resolve(instant), isBusinessDay(date), nextBusinessDay, previousBusinessDay |
| `MarketCalendarEntry` | `domain/market/session/MarketCalendarEntry.kt` | date, isBusinessDay, isHoliday, holidayName, source |
| `BusinessDayCalendar` | `domain/market/session/BusinessDayCalendar.kt` | isBusinessDay, nextBusinessDay |
| `MarketCalendarRepository` | `domain/market/session/port/MarketCalendarRepository.kt` | findByDate, upsert |
| `RejectReason` | `domain/market/order/RejectReason.kt` | 거부 사유 코드 enum |

### 5-4 영속 테이블

| 테이블 | Flyway | 핵심 컬럼 |
|--------|--------|----------|
| `market_calendar` | V6 | date PK, is_business_day, is_holiday, holiday_name, source(STATIC_SEED\|KIS_CHK_HOLIDAY\|MANUAL) |
| `order_reject_audit` | V6 | intent_id(UUID UNIQUE), user_id, code, side, reject_source, reject_reason, occurred_at |
| `orders` | V1+V6 | reject_reason, reject_detail, reject_source 컬럼 추가 |

### 5-5 외부 통합

- KIS chk-holiday (CTCA0903R): `GET /uapi/domestic-stock/v1/quotations/chk-holiday`
- 소스: `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/infrastructure/kis/KisChkHolidayAdapter.kt`
- v3.1.0 실제 연동: credential 조회 미구현 — 정적 시드 fallback 유지 중

### 5-6 Kafka 이벤트

없음 (내부 서비스 — Kafka 미사용).

### 5-7 Fallback 3-tier 정책 (ADR 0014)

| Tier | 소스 | 조건 |
|------|------|------|
| T1 | KIS WS H0STCNT0 live | KIS WS 연결 + 구독 성공 |
| T2 | Redis quote:last:{code} (stale) | WS 신호 30초 이상 없음 — TickFallbackEmitter 10초 주기 재발행 |
| T3 | DB candle 1d close | 장 마감(Closed 세션) — DailyCloseFinalizer 영속 값 |

소스: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/market/TickFallbackEmitter.kt`

### 5-8 MarketSessionResolver 구현 현황

- `SimpleMarketSessionResolver` (consumer) — 시간 기반 stub (주말만 제외, 공휴일 미인지)
- `KrxMarketSessionResolver` (api) — market_calendar DB 기반 (PR-29a 본격 구현)
- `@ConditionalOnMissingBean` 패턴: KrxMarketSessionResolver 존재 시 SimpleResolver 비활성

소스: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/session/SimpleMarketSessionResolver.kt:32-33`

### 5-9 frontend 진입점

`05-ui-inventory.md` §5 MarketStatusBadge, SessionClosedBanner 참조 (TBD)

### 5-10 관련 PR + ADR

- PR-29a (T-MS1 본격 구현)
- ADR 0013 (Market Session FSM 설계), ADR 0014 (Fallback 3-tier 정책), ADR 0015 (주문 거부 감사)

### 5-11 현재 한계 + W2 이후 예정

- KIS chk-holiday KrxMarketSessionResolver: batch credential bean 추가 후 본격 활성화.
- REJECTED audit: order_reject_audit 테이블 존재, write 로직은 T-O (W3) 예정.
- 세션 변경 이벤트 consumer 연동: SubscriptionRouter.onSessionChanged() 구현됨 — 실제 cron 트리거 미완.

---

## §6 T-CA1 — Corporate Action + Adjusted Price

### 6-1 개요

기업 행위(액면분할·무상증자·현금배당 등) 이벤트 수집 및 캔들 수정주가 소급 재계산. KIS ksdinfo 6 endpoint + DART OPENDART 이중 수집. CorpActionSyncJob(18:30 KST) → corp_action upsert → Kafka `corp_action.applied.v1` 발행 → CandleAdjustmentJob 수신 → candle open_adj/close_adj 재계산.

### 6-2 REST 엔드포인트

없음 (batch job 전용 — 수동 트리거 endpoint 미구현).

### 6-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드/메서드 |
|-------------|------|----------------|
| `CorpAction` | `domain/instrument/corp_action/CorpAction.kt` | instrumentCode, type(CorpActionType), exDate, ratio, cashDividend, source, appliedAt |
| `CorpActionType` | `domain/instrument/corp_action/CorpActionType.kt` | STOCK_SPLIT, REVERSE_SPLIT, BONUS_ISSUE, CASH_DIVIDEND, STOCK_DIVIDEND, RIGHTS_OFFERING, MERGER, SPINOFF, CAPITAL_DECREASE, DELISTING_NOTICE |
| `CorpActionRepository` | `domain/instrument/corp_action/port/CorpActionRepository.kt` | upsert(action), findPendingApplied(), markApplied(id, appliedAt) |
| `KisKsdInfoFetcher` | `domain/instrument/corp_action/port/KisKsdInfoFetcher.kt` | fetchPaidinCapin, fetchBonusIssue, fetchDividend, fetchMergerSplit, fetchRevSplit, fetchCapDcrs |
| `DartDisclosureFetcher` | `domain/instrument/corp_action/port/DartDisclosureFetcher.kt` | fetchRecent(from, to): List<CorpAction> |

### 6-4 영속 테이블

| 테이블 | Flyway | 핵심 컬럼 |
|--------|--------|----------|
| `corp_action` | V7 | instrument_code, type, ex_date, ratio, cash_dividend, source, applied_at, raw_payload JSONB |
| `candle` | V1+V8 | adjustment_factor NUMERIC(15,8), volume_adjusted BIGINT (V8 추가) |

### 6-5 외부 통합

KIS ksdinfo 6 endpoint (KIS-first 원칙 — ADR 0018 §2-5):

| endpoint | tr_id | 내용 |
|----------|-------|------|
| `/uapi/domestic-stock/v1/ksdinfo/paidin-capin` | HHKDB669100C0 | 유상증자 |
| `/uapi/domestic-stock/v1/ksdinfo/bonus-issue` | HHKDB669101C0 | 무상증자 |
| `/uapi/domestic-stock/v1/ksdinfo/dividend` | HHKDB669102C0 | 배당 |
| `/uapi/domestic-stock/v1/ksdinfo/merger-split` | HHKDB669104C0 | 합병·분할 |
| `/uapi/domestic-stock/v1/ksdinfo/rev-split` | HHKDB669105C0 | 액면분할·병합 |
| `/uapi/domestic-stock/v1/ksdinfo/cap-dcrs` | HHKDB669106C0 | 감자 |

DART OPENDART:
- endpoint: `https://opendart.fss.or.kr/api/list.json`
- pblntf_ty: `B` (유·무상증자), `D` (배당)
- 소스: `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/infrastructure/dart/DartDisclosureAdapter.kt`

### 6-6 Kafka 이벤트

| 토픽 | 방향 | groupId | payload |
|------|------|---------|---------|
| `corp_action.applied.v1` | publish (CorpActionSyncJob) | — | `{id, instrumentCode, type, exDate, ratio, cashDividend, source}` |
| `corp_action.applied.v1` | consume (CandleAdjustmentJob) | candle-adjustment | 동일 |

### 6-7 보정 계수 계산 (ADR 0018 §4-3)

| CorpActionType | 보정 계수 계산 | 비고 |
|----------------|-------------|------|
| STOCK_SPLIT | `factor = 1 / ratio` | ratio=50 → 가격×(1/50) |
| REVERSE_SPLIT | `factor = ratio` | 가격×ratio |
| BONUS_ISSUE | `factor = 1 / (1 + ratio)` | ratio=0.1(10%) → ×0.909 |
| CAPITAL_DECREASE | `factor = ratio` | 무상소각 시 |
| CASH_DIVIDEND | null (보정 없음) | 시장 자동 반영 |
| 기타 | null (보정 없음) | post-MVP ADR 0018 §4-4 |

소스: `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/job/corp_action/CandleAdjustmentJob.kt:142-177`

### 6-8 frontend 진입점

`05-ui-inventory.md` §4 SymbolDetailPage — 수정주가 차트(open_adj/close_adj 사용) 참조 (TBD)

### 6-9 관련 PR + ADR

- PR-29b (T-CA1 본격 구현)
- ADR 0018 (Corporate Action 처리 정책)

### 6-10 현재 한계 + W2 이후 예정

- MERGER·SPINOFF·RIGHTS_OFFERING 보정 정책: post-MVP (ADR 0018 §4-4 — 현재 factor=null).
- CandleAdjustmentJob Redis invalidate: `indicator:cache:{code}:*` DEL — W2 T-IND 지표 구현 후 실질 효과.
- DART 중복 row: KIS·DART 양쪽 발견 시 source별 독립 row 보존 — CandleAdjustmentJob은 KIS 우선 적용 (ADR 0018 §4-2).

---

## §7 T-IM1 — Instrument Master

### 7-1 개요

KIS 종목마스터 70+ 필드 전체 sync. KOSPI 70 필드(227byte), KOSDAQ 64 필드(221byte). 06:05 KST cron + 수동 trigger. Redis 자동완성 검색 인덱스 재구성. 상장폐지 = DELETE 금지 + active=false.

### 7-2 REST 엔드포인트

소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/InstrumentController.kt`

| 메서드 | 경로 | 파라미터 | 응답 DTO |
|--------|------|---------|---------|
| GET | `/api/instruments/search` | q(필수), limit(1~20, default 10), market(KOSPI\|KOSDAQ\|KONEX) | `InstrumentSearchResponseDto` |
| GET | `/api/instruments/{code}` | - | `InstrumentDetailDto` |
| GET | `/api/instruments` | market, active(default true), limit(1~200), offset | `InstrumentUniverseResponseDto` |
| POST | `/api/instruments/sync` | - | `InstrumentSyncTriggerResponse` (202 Accepted) |

주요 DTO schema:
```
InstrumentDetailDto {
    code, isin, name, nameEn, market, type, sector, theme,
    listedAt, delistedAt, active,
    aliases: List<InstrumentAliasDto>,  // {alias, source}
    securitiesGroup,
    tradingGuards: InstrumentTradingGuardsDto {
        tradingHalt, liquidation, managementStock, marketAlert,
        shortOverheated, abnormalRunup, falseDisclosure,
        backdoorListing, creditAble, marginRate, shortOverheatedStatus
    },
    classification: InstrumentClassificationDto {
        industryLarge, industryMedium, industrySmall,
        isKospi200, kospi200Sector, isKosdaq150, isKospi100,
        isKospi50, isKrx, isKrx100, isKrx300,
        isSpac, isVenture, isLowLiquidity,
        preferredStockType, etpProductType, avlsScale
    },
    marketMeta: InstrumentMarketMetaDto {
        basePrice, tradingUnitNormal, prevVolume,
        marketCapPrev, listedShares, settlementMonth
    }
}
```

소스: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/InstrumentController.kt:139-211`

### 7-3 도메인 entity·port

| entity/port | 파일 | 핵심 필드 |
|-------------|------|---------|
| `Instrument` | `domain/instrument/Instrument.kt` | code, isin, name, market(InstrumentMarket), type, sector, active |
| `InstrumentAlias` | `domain/instrument/InstrumentAlias.kt` | code, alias, source(KRX\|NAVER\|FNGUIDE) |
| `InstrumentSearchResult` | `domain/instrument/InstrumentSearchResult.kt` | code, name, market, sector, score |
| `KisInstrumentMasterPort` | `domain/instrument/port/KisInstrumentMasterPort.kt` | fetchAll(): List<Instrument> |
| `InstrumentRepository` | `domain/instrument/port/InstrumentRepository.kt` | findByCode, upsert, markDelisted |
| `InstrumentSearchIndex` | `domain/instrument/port/InstrumentSearchIndex.kt` | search(q, limit, market), rebuildIndex() |

### 7-4 영속 테이블

| 테이블 | Flyway | 핵심 컬럼 |
|--------|--------|----------|
| `instrument` | V1+V3+V4+V5 | code PK, 70+ 컬럼 (KIS-first 전체 필드) |
| `instrument_alias` | V4 | (code, alias, source) PK, FK → instrument ON DELETE CASCADE |

### 7-5 외부 통합

KIS 종목마스터 (KIS-first 원칙 — ADR 0039):
- KOSPI: `GET /uapi/domestic-stock/v1/quotations/psearch-result` (BAS_PRCN_ISSU_UNDI_QTY 기준 binary parse, 70 필드 227byte)
- KOSDAQ: 동일 endpoint, 64 필드 221byte
- 소스: `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/infrastructure/kis/KisInstrumentMasterAdapter.kt`

InstrumentSyncJob 처리 흐름 (PR-21a 보강):
1. fetchAllRaw() — 70+ 필드 binary 파싱
2. diff (existingActive vs kisMap)
3. INSERT NEW / UPDATE CHANGED(매매가드·securities_group 변동 추적) / DELIST(active=false)
4. instrument_alias upsert (source=KRX)
5. Kafka `instrument.updated.v1` publish (partition key=market)
6. Redis invalidate (instrument:list:* + search:* + by-code:*)

소스: `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/job/instrument/InstrumentSyncJob.kt`

### 7-6 Kafka 이벤트

| 토픽 | 방향 | payload |
|------|------|---------|
| `instrument.updated.v1` | publish (InstrumentSyncJob) | `{syncedAt, changes:[{code, action:INSERT\|UPDATE\|DELIST, market, name}]}` |

### 7-7 Redis 검색 인덱스

- `instrument:search:prefix:*` — sorted set ZRANGEBYLEX 자동완성 (< 50ms 목표)
- `instrument:list:*` — 전체 목록 캐시
- `instrument:by-code:{code}` — 단일 종목 캐시
- rebuild: `POST /api/instruments/sync` 또는 InstrumentSyncJob 완료 후 자동

### 7-8 frontend 진입점

`05-ui-inventory.md` §6 InstrumentSearchAutocomplete, §7 InstrumentDetailModal 참조 (TBD)

### 7-9 관련 PR + ADR

- PR #11a (T-IM1 기본), PR #11b (종목 검색 Redis), PR #21a (KIS-first 70+ 필드 boilerplate), PR #21b (70+ 필드 WatchlistService 연동)
- ADR 0017 (종목 마스터 보존 정책), ADR 0039 (KIS-first 종목마스터 파싱)

### 7-10 현재 한계 + W2 이후 예정

- 영문명(nameEn): KIS 마스터 미제공. 별도 source 추가 후 채움 예정.
- 검색 trigram 인덱스: V4 pg_trgm extension. 초기 부팅 시 extension 미설치 환경 주의 (idx 생성 실패 시 LIKE fallback으로 degradation).
- T-IND (W2): 지수 편입 정보(is_kospi200 등) 기반 필터링 고도화.
- Flyway V5 70 컬럼: `upsertFull()` JPQL native query — 파라미터 수 70+ (Spring Data @Query 한계 우회, BatchInstrumentJpaRepository 직접 custom JPQL).
