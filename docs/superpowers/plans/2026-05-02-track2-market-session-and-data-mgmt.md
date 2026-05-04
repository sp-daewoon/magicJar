# 2번 트랙 — 장 상태 인지 + 시세 Fallback + REJECTED Audit + EGW00202 hotfix 실행 plan

> spec: `docs/superpowers/specs/2026-05-02-track2-market-session-and-data-mgmt-design.md` (Phase §1~§14)

- **작성일**: 2026-05-02
- **작성자**: architect (opus 4.7 1M)
- **상태**: DRAFT — 사용자 검토 후 별도 세션에서 실행
- **v2 정합 노트 (2026-05-03)**: v2 베이스라인 진입 후 본 plan은 T2 (W1) 트랙 실행 plan으로 흡수. T20·T21·T22 ADR 발행 task는 v2에서 이미 정식 발행됨(0032~0035) — 트랙 진입 시 ADR 본문만 W1 dogfooding 결과 반영해서 보강. 새 ADR 발행 X.

본 plan은 **22개 task** (T1~T22)로 구성. spec §3 모듈별 변경 포인트 + §13 ADR 5개 + §14 사용자 결정 사항 반영.

---

## Phase 1 — 기반 (T1~T5): 도메인 + 캘린더 + 캐시 + 진단 인프라

`branch: agent/architect/track2-foundation` (또는 backend-core 위임)

### T1 — V10 `market_calendar` 테이블 + 2026·2027 시드

- **목표**: 영업일·휴장일 lookup의 단일 진실원 확보.
- **결과물**:
  - `api/src/main/resources/db/migration/V10__market_calendar.sql` (spec §9.1 그대로)
  - 2026 18개 휴장일 + 2027 자동 주말 시드 + 2027 공휴일은 운영 시 갱신 (T8 또는 수동)
- **영향 모듈**: api (Flyway)
- **의존성**: 없음
- **사이즈**: S
- **담당**: backend-core 또는 architect 직접
- **검증**:
  - `docker compose down -v && up -d` → V10 적용 검증
  - `SELECT * FROM market_calendar WHERE calendar_date='2026-05-02'` → is_business_day=false, holiday_reason='WEEKEND'
  - `SELECT count(*) FROM market_calendar WHERE EXTRACT(YEAR FROM calendar_date)=2026` = 365

### T2 — Domain `MarketSessionState` + `MarketSession` + Port

- **목표**: spec §4.1 도메인 모델 채움. port 정의로 모듈 경계 명확.
- **결과물**:
  - `domain/src/main/kotlin/com/mj/domain/market/session/MarketSessionState.kt` (sealed)
  - `domain/src/main/kotlin/com/mj/domain/market/session/MarketSession.kt` (data)
  - `domain/src/main/kotlin/com/mj/domain/market/session/port/MarketCalendarRepository.kt`
  - `domain/src/main/kotlin/com/mj/domain/market/session/port/MarketSessionResolver.kt`
  - `domain/src/test/kotlin/com/mj/domain/market/session/MarketSessionStateTest.kt` — sealed class case 검증
- **영향 모듈**: domain
- **의존성**: T1 (논리 의존, 코드 의존 0)
- **사이즈**: S
- **담당**: architect (도메인 경계는 architect 단독)
- **검증**:
  - `:domain:test` PASS (신규 5+ 테스트)
  - `MarketSessionState`의 sealed when exhaustive 컴파일 OK

### T3 — `MarketCalendarJpaAdapter` + Entity·Repository

- **목표**: api 모듈에 영속성 어댑터.
- **결과물**:
  - `api/src/main/kotlin/com/mj/api/infrastructure/persistence/calendar/MarketCalendarEntity.kt`
  - `api/.../MarketCalendarJpaRepository.kt`
  - `api/.../MarketCalendarJpaAdapter.kt` (port 구현)
  - `api/src/test/.../MarketCalendarJpaAdapterIT.kt` — Testcontainers PostgreSQL
- **영향 모듈**: api
- **의존성**: T1, T2
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - IT 4 시나리오 PASS — isBusinessDay·holidayReason·next/previousBusinessDay
  - 토요일·평일·공휴일·임시휴장 4 케이스 모두 검증

### T4 — `MarketSessionResolver` 구현 + 1분 cache

- **목표**: 시간(KST) + 캘린더 lookup → MarketSession 조립. 1분 in-memory cache로 호출 부하 감소.
- **결과물**:
  - `consumer/src/main/kotlin/com/mj/consumer/application/session/MarketSessionResolverImpl.kt`
  - `consumer/.../MarketSessionResolverImplTest.kt` — 시간 mocking (Clock 주입)
- **영향 모듈**: consumer
- **의존성**: T2, T3 (port 주입)
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - 시간별 케이스: 08:30 평일 (PRE_MARKET), 10:00 평일 (OPEN), 16:00 평일 (POST_MARKET), 14:30 토요일 (CLOSED), 10:00 어린이날 (HOLIDAY), 16:00 12-31 (HOLIDAY 임시휴장)
  - cache hit 검증 (2번째 호출은 repo lookup 0)
  - **회귀 가드**: api batch consumer 셋 다 부팅 OK (api는 영속성·consumer는 resolver·batch는 무관)

### T5 — `QuoteCache` Port + Redis 어댑터

- **목표**: spec §5.4 Redis key 설계대로 last-quote 캐시.
- **결과물**:
  - `domain/.../market/cache/port/QuoteCache.kt`
  - `consumer/src/main/kotlin/com/mj/consumer/application/cache/QuoteCacheRedis.kt`
  - `consumer/.../QuoteCacheRedisIT.kt` — Testcontainers Redis
  - `consumer/src/test/.../InMemoryQuoteCache.kt` (test fixture, 단위테스트용)
- **영향 모듈**: domain, consumer
- **의존성**: T2 (TickEvent 변경 T11 후에야 stale·asOf 사용 가능. T5 시점은 기존 TickEvent로 시작 OK)
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - put·get round-trip OK
  - TTL 7일 적용 검증
  - 미존재 key get → null

---

## Phase 2 — Guard + REJECTED Audit (T6~T11)

`branch: agent/architect/track2-guard-audit` (T6·T7만 별도 가능 — EGW00202 hotfix 우선시 R1)

### T6 — EGW00202 진단: raw HTTP 로깅 옵션 추가 (P1)

- **목표**: `RealKisOrderAdapter`의 RestClient 요청 raw HTTP 로깅 활성. curl과 비교해 차이 식별.
- **결과물**:
  - `consumer/.../infrastructure/kis/RealKisOrderAdapter.kt`에 `MAGICJAR_KIS_RAW_HTTP_LOGGING=true` 시 logging interceptor 활성
  - 또는 `KisHttpSession`의 RestClient builder에 `BufferingClientHttpRequestFactory` + `RestClient.requestInterceptor` 추가
  - `consumer/src/main/resources/logback-spring.xml`에 KIS_RAW logger 설정
  - `_workspace/operations/track2-egw00202-diagnosis.md` — 로그 캡처 → curl 응답 비교 표 (사용자 dogfooding 시 채움)
- **영향 모듈**: consumer
- **의존성**: 없음 (병렬)
- **사이즈**: S
- **담당**: backend-core 또는 architect
- **검증**:
  - 환경변수 toggle on/off 정상
  - 로그에 request line + headers + body 평문 출력 (단, appkey·appsecret은 마스킹)
  - 사용자 1회 매수 시도 → 로그 캡처 → 운영 가이드 표 채움

### T7 — EGW00202 hotfix: hashkey 헤더 + 직렬화 정밀화 (P1)

- **목표**: T6 진단 결과 반영. KIS 공식 hashkey 발급 endpoint 호출 → POST 요청 헤더 첨부.
- **결과물**:
  - `consumer/.../infrastructure/kis/KisHashKeyClient.kt` (신규) — `/uapi/hashkey` POST 호출
  - `consumer/.../infrastructure/kis/RealKisOrderAdapter.kt` 변경:
    - postWithAuth 호출 시 hashkey 헤더 자동 첨부 (`MAGICJAR_KIS_HASHKEY_ENABLED=true` default)
    - `Accept`·`User-Agent` 명시 override
    - body Map → DTO data class 또는 명시 LinkedHashMap (직렬화 순서 보장)
  - `consumer/src/test/.../infrastructure/kis/RealKisOrderAdapterIT.kt` 신규 — WireMock으로 hashkey 호출 + order-cash 호출 2단 검증
- **영향 모듈**: consumer
- **의존성**: T6
- **사이즈**: M
- **담당**: market-data 또는 backend-core (KIS 어댑터 전문)
- **검증**:
  - WireMock IT — hashkey 발급 1회 + order-cash POST 1회, hashkey 헤더 검증
  - 사용자 dogfooding (월요일 또는 dev offline-fallback) — EGW00202 미발생
  - **대조**: `MAGICJAR_KIS_HASHKEY_ENABLED=false`로 EGW00202 재현 (회귀 가드)

### T8 — `MarketSessionGuard` Guard chain 합류

- **목표**: spec §7.1 신규 Guard.
- **결과물**:
  - `consumer/.../application/guard/MarketSessionGuard.kt` (`@Order(150)`, profile 분기)
  - `consumer/.../GuardChainConfig.kt` 자동 wiring 검증
  - `consumer/src/test/.../MarketSessionGuardTest.kt` — 6 시나리오 (mock·dev OPEN·dev CLOSED with/without offline-fallback·prd CLOSED·HOLIDAY)
- **영향 모듈**: consumer
- **의존성**: T2, T4
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - mock profile에선 PASS (KIS 호출 0)
  - dev + CLOSED + offline-fallback=true → PASS
  - dev + CLOSED + offline-fallback=false → REJECT(MarketClosed)
  - prd + CLOSED → REJECT (offline-fallback 무관)

### T9 — Domain `RejectReason` sealed class + `OrderRejectedEvent`

- **목표**: spec §4.2·§4.3 도메인 추가.
- **결과물**:
  - `domain/.../common/RejectReason.kt`
  - `domain/.../order/event/OrderRejectedEvent.kt`
  - `domain/src/test/.../common/RejectReasonTest.kt` — sealed when exhaustive 검증
- **영향 모듈**: domain
- **의존성**: 없음
- **사이즈**: S
- **담당**: architect
- **검증**: `:domain:test` PASS

### T10 — V11 마이그레이션 + `OrderRecord`·repository 변경

- **목표**: spec §9.2 — orders.reject_reason_code + kis_order_no NULLABLE.
- **결과물**:
  - `api/src/main/resources/db/migration/V11__order_reject_audit.sql`
  - `api/.../infrastructure/persistence/order/OrderEntity.kt` — kis_order_no nullable + reject_reason_code 추가
  - `api/.../OrderJpaAdapter.kt` 매퍼 갱신
  - `domain/.../order/OrderRecord.kt` — `kisOrderNo: String?`·`rejectReasonCode: String?`
  - `domain/.../order/OrderEventRecord.kt` — `kisOrderNo: String?`
- **영향 모듈**: domain, api
- **의존성**: T9
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - Flyway 마이그 적용 OK (기존 데이터 NULL 백필)
  - 회귀: 기존 PLACED 흐름 (kis_order_no 비어있지 않음) 그대로 작동
  - REJECTED 흐름: kis_order_no=NULL INSERT 가능
  - 부분 UNIQUE 인덱스 — 두 개의 PLACED는 거부, 다수의 REJECTED는 허용 검증

### T11 — `KisOrderSender` REJECTED audit trail 통합

- **목표**: spec §7.3 — Guard reject·KisApiException 모두 orders + order_events INSERT + Kafka publish.
- **결과물**:
  - `consumer/.../application/order/KisOrderSender.kt` 변경:
    - `recordRejected(order, userId, accountNo, kisMode, reason: RejectReason, source: String)` 헬퍼 도입
    - Guard Reject 분기 + KisApiException 분기 둘 다 호출
    - Kafka `order.rejected.v1` publisher 주입 + publish
  - `consumer/.../messaging/producer/OrderRejectedPublisher.kt` (신규)
  - `consumer/src/test/.../KisOrderSenderTest.kt` 보강:
    - Guard Reject → orders+order_events INSERT 검증
    - KisApiException → orders+order_events INSERT 검증
    - 멱등 dedup 검증 (재시도 시 INSERT 0)
- **영향 모듈**: consumer
- **의존성**: T8, T9, T10
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - 단위테스트 4개 추가 PASS
  - mock profile + KisOrderSender 우회 흐름 영향 0 (mock은 KisOrderSender 미사용)

---

## Phase 3 — TickEvent + Fallback Emitter (T12~T15)

### T12 — `TickEvent` 하위호환 확장 (`stale`·`asOf`)

- **목표**: spec §4.6 — default 값으로 producer 무변경.
- **결과물**:
  - `domain/.../market/event/TickEvent.kt` 변경 (default 추가)
  - `domain/src/test/.../TickEventSerializationTest.kt` — JSON 호환성 검증 (옛 메시지·신 메시지 둘 다 deserialize OK)
- **영향 모듈**: domain
- **의존성**: 없음
- **사이즈**: S
- **담당**: architect
- **검증**:
  - `:domain:test` PASS
  - 기존 producer (MockTickGenerator·KIS WS adapter) 변경 0
  - 기존 consumer (TickStompFanoutListener) 변경 0 (default 값으로 작동)

### T13 — TickIngest에서 QuoteCache.put 호출

- **목표**: 모든 tick이 들어올 때마다 last-quote 캐시 갱신.
- **결과물**:
  - `consumer/.../messaging/listener/TickIngestListener.kt` (또는 등가 흐름) 변경 — QuoteCache.put 호출 추가
  - `consumer/src/test/.../TickIngestListenerTest.kt` 보강
- **영향 모듈**: consumer
- **의존성**: T5, T12
- **사이즈**: S
- **담당**: market-data
- **검증**:
  - tick 수신 → Redis hash put 검증 (Testcontainers)
  - put 실패 시 listener 자체는 정상 처리 (cache는 best-effort)

### T14 — `TickFallbackEmitter` 신규 (장외 시 cached emit)

- **목표**: spec §10 S5 — 장외에 last-cached를 STOMP에 emit.
- **결과물**:
  - `consumer/.../application/market/TickFallbackEmitter.kt` (`@Scheduled(fixedDelay=10s)`)
  - 동작: resolver.resolve() != OPEN이면 watchlist 종목 모두 cached emit (stale=true)
  - `consumer/src/test/.../TickFallbackEmitterTest.kt`
- **영향 모듈**: consumer
- **의존성**: T4, T5, T12, T13
- **사이즈**: M
- **담당**: market-data
- **검증**:
  - OPEN 시 emit 0 (skip)
  - CLOSED 시 5종목 emit 1회/10초
  - cache miss 종목은 skip + WARN 로그
  - 회귀: MockTickGenerator와 충돌 없음 (mock profile에선 emitter 비활성)

### T15 — `TickStompFanoutListener` payload 확장 (stale·asOf 통과)

- **목표**: api 모듈에서 STOMP payload에 `stale`·`asOf` 포함.
- **결과물**:
  - `api/.../messaging/listener/TickStompFanoutListener.kt` 변경 — TickMessage DTO에 두 필드 추가
  - `api/src/test/.../TickStompFanoutListenerTest.kt` 보강
- **영향 모듈**: api
- **의존성**: T12
- **사이즈**: S
- **담당**: backend-core
- **검증**:
  - STOMP 메시지에 stale·asOf 포함 검증
  - frontend 옛 클라이언트 호환 (기본값으로 falsy 처리)

---

## Phase 4 — REST endpoint + Frontend (T16~T18)

### T16 — `MarketSessionController` 신규 — `/api/market/session` + `/api/market/calendar`

- **목표**: spec §6.1 — frontend가 polling으로 현재 상태 조회.
- **결과물**:
  - `api/.../controller/MarketSessionController.kt`
  - `api/.../controller/dto/MarketSessionResponse.kt`
  - `api/.../application/market/MarketSessionQueryService.kt`
  - `api/src/test/.../MarketSessionControllerTest.kt` — `@WebMvcTest` 4 시나리오 (OPEN·CLOSED 토요일·HOLIDAY 어린이날·HOLIDAY 임시휴장)
- **영향 모듈**: api
- **의존성**: T2, T3, T4 (resolver를 api에서 직접? 또는 api 자체 resolver 인스턴스 — 채택: api도 동일 구현 주입. consumer·api 양쪽에 MarketSessionResolverImpl 인스턴스 OK, cache는 분리)
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - `GET /api/market/session` 200 + spec §6.1 응답 형식
  - `GET /api/market/calendar?from=2026-05-01&to=2026-05-10` 200 + 영업일·휴장일 목록

### T17 — `OrderController` 보강: `/api/orders/{id}/events` + REJECTED filter

- **목표**: REJECTED audit trail 가시화 + 주문별 lifecycle 조회.
- **결과물**:
  - `api/.../controller/OrderController.kt` 변경 — `@GetMapping("/{id}/events")` + 기존 list에 `?status=REJECTED` filter
  - `api/.../application/order/OrderApiService.kt` 보강
  - `api/.../controller/dto/OrderEventResponse.kt` 신규
  - `api/src/test/.../OrderControllerTest.kt` 보강 (5 신규 테스트)
- **영향 모듈**: api
- **의존성**: T10, T11
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - REJECTED 필터 응답 검증
  - 주문 lifecycle 조회 (PLACED + EXECUTED 또는 REJECTED 단일)

### T18 — Frontend: MarketSessionBadge + Orders REJECTED + stale 표시

- **목표**: spec §6.3 + S1·S5·S6 시나리오 UI.
- **결과물**:
  - `frontend/src/hooks/useMarketSession.ts` (1분 polling)
  - `frontend/src/components/MarketSessionBadge.tsx`
  - `frontend/src/api/types.ts` — MarketSessionResponse·TickMessage stale·OrderResponse rejectReasonCode 추가
  - `frontend/src/pages/Dashboard/index.tsx` — 페이지 상단 MarketSessionBadge + 종목 카드 stale 표시 + asOf 시각
  - `frontend/src/pages/SymbolDetail/index.tsx` — 차트 상단 Badge
  - `frontend/src/pages/Orders/index.tsx` — REJECTED row 가시화 + reason 컬럼 + filter dropdown (전체·PENDING·FILLED·REJECTED·CANCELED)
  - `frontend/src/api/client.ts` — `getMarketSession()`·`getOrderEvents(id)`
  - `frontend/src/components/__tests__/MarketSessionBadge.test.tsx` — 5 상태 렌더 검증
  - `frontend/src/pages/Orders/__tests__/index.test.tsx` 보강
- **영향 모듈**: frontend
- **의존성**: T16, T17
- **사이즈**: L
- **담당**: frontend
- **검증**:
  - vitest 신규 8개+ PASS
  - Storybook 또는 dev server 수동 — 5 state 모두 렌더 OK
  - dogfooding 시 토요일 페이지 진입 → "장 마감" 배지 노출 확인

---

## Phase 5 — Profile 분기 + Wiring + 운영 (T19~T22)

### T19 — `OrderSenderProfileConfig` 정밀화 (dev offline-fallback 라우팅)

- **목표**: spec §8 매트릭스 + D6 (toggle).
- **결과물**:
  - `consumer/.../application/order/OrderSenderProfileConfig.kt` 변경:
    - mock profile → MockOrderSender 빈
    - dev profile → KisOrderSender 빈, 단 `MAGICJAR_DEV_OFFLINE_FALLBACK=true` 시 SessionAwareOrderSender (CLOSED/HOLIDAY엔 MockOrderSender, 그 외엔 KisOrderSender)
    - prd profile → KisOrderSender 빈 (이중 게이트)
  - `consumer/.../application/order/SessionAwareOrderSender.kt` (신규 wrapper)
  - `consumer/src/test/.../OrderSenderProfileConfigTest.kt` — 4 profile 조합 빈 등록 검증
- **영향 모듈**: consumer
- **의존성**: T4, T8, T11
- **사이즈**: M
- **담당**: backend-core
- **검증**:
  - mock·dev·dev+fallback·prd 4 시나리오 부팅 + 빈 종류 검증
  - 회귀: 1번 트랙 KisOrderSender 흐름 영향 0

### T20 — ADR 5개 정식 발행 + README·운영 가이드 갱신

- **목표**: spec §13 ADR draft를 정식 파일로 옮김.
- **결과물**:
  - `_workspace/arch/adr/0031-market-session-fsm.md`
  - `_workspace/arch/adr/0032-quote-fallback-policy.md`
  - `_workspace/arch/adr/0033-rejected-audit-trail.md`
  - `_workspace/arch/adr/0034-profile-session-matrix.md`
  - `_workspace/arch/adr/0035-egw00202-hotfix.md`
  - `_workspace/arch/adr/README.md` — 5건 추가
  - `_workspace/operations/order-policy.md` 갱신 — §3·§7 매트릭스 보강 + §11 신설 (장 상태·캘린더 갱신·offline-fallback toggle)
  - `_workspace/operations/track2-data-mgmt.md` (신규) — 캐시·캘린더·fallback 운영 가이드
- **영향 모듈**: docs only
- **의존성**: T1~T19 완료 후 (실제 코드 검증 후 ADR 발행)
- **사이즈**: M
- **담당**: architect
- **검증**: 사용자 검토 OK

### T21 — Docker compose · `.env.example` 갱신

- **목표**: 신규 환경변수 명시.
- **결과물**:
  - `docker/docker-compose.yml` (또는 dev 변형) — Redis 사용처 명시 + 신규 env 추가:
    - `MAGICJAR_MARKET_SESSION_ENABLED=true`
    - `MAGICJAR_DEV_OFFLINE_FALLBACK=false` (default)
    - `MAGICJAR_QUOTE_CACHE_TTL_DAYS=7`
    - `MAGICJAR_KIS_RAW_HTTP_LOGGING=false`
    - `MAGICJAR_KIS_HASHKEY_ENABLED=true`
  - `.env.example` 업데이트
  - `scripts/build-and-deploy.sh` — `--env-file .env` 옵션 명시 (F-T2-005 hotfix)
- **영향 모듈**: docker, scripts
- **의존성**: T1~T19
- **사이즈**: S
- **담당**: architect
- **검증**:
  - `docker compose config` 통과
  - 9 컨테이너 healthy
  - 사용자 dogfooding 시 toggle on/off 둘 다 검증

### T22 — Dogfooding 시나리오 문서 + harness-state 갱신 + CLAUDE.md 변경 이력

- **목표**: 사용자가 본 트랙 검증할 시나리오 6개(spec §10 S1~S6) 단계별 가이드 + 후속 트랙 메모.
- **결과물**:
  - `_workspace/dogfooding-track2-2026-05-XX.md` — 6 시나리오 단계별 (사용자 직접 수행 가이드)
  - `_workspace/_harness-state.json` 갱신 — track2 진행 상태 + open issues
  - `CLAUDE.md` 변경 이력 1행 추가
- **영향 모듈**: docs only
- **의존성**: T1~T21
- **사이즈**: S
- **담당**: architect
- **검증**: 사용자 검토 OK

### T23 (선택) — `MarketCalendarSyncJob` (batch 모듈)

- **목표**: 분기·연도 단위 캘린더 자동 갱신. 1차는 수동 SQL OK.
- **결과물**:
  - `batch/.../job/calendar/MarketCalendarSyncJob.kt`
  - 한국거래소 공식 캘린더 API 또는 KIS 휴장일 조회 endpoint 호출
  - cron: 매월 1일 03:00
- **영향 모듈**: batch
- **의존성**: T1
- **사이즈**: M
- **담당**: backend-core
- **선택 여부**: T20 작성 시 사용자 결정 — 1차 정적 시드 OK면 보류, 자동화 필요시 채택.
- **검증**: WireMock IT + 분기별 dry-run

---

## Task 의존 그래프

```
T1 ──┬─→ T3 ──┬─→ T4 ──┬─→ T8 ──┬─→ T11 ──┐
     │        │        │        │         │
     └─→ T23* │        │        │         │
              │        │        │         │
T2 ──────────→┴────────┴─→ T9 ─→ T10 ─→ T11
                                  │
T5 ──┬→ T13 ─→ T14                │
     └→ T11                       │
                                  │
T12 ─┬→ T13                       │
     ├→ T14                       │
     └→ T15                       │
                                  │
T6 → T7                           │
                                  │
T16 (T2·T3·T4)                    │
T17 (T10·T11) ←───────────────────┘
T18 (T16·T17)
T19 (T4·T8·T11)
T20 (T1~T19)
T21 (T1~T19)
T22 (T1~T21)
```

## 추정 사이즈 합계

| Phase | task | 사이즈 합 |
|---|---|---|
| 1 (T1~T5) | 5 | S+S+M+M+M = ~6일 |
| 2 (T6~T11) | 6 | S+M+M+S+M+M = ~7일 |
| 3 (T12~T15) | 4 | S+S+M+S = ~3일 |
| 4 (T16~T18) | 3 | M+M+L = ~5일 |
| 5 (T19~T22) | 4 | M+M+S+S = ~3일 |
| **총합** | **22** | **~24일** (단일 인력 기준) |

병렬 가능 — Phase 1 T1·T2는 동시, Phase 2의 T6·T7과 T8·T9·T10·T11은 병렬 가능. **추정 압축 후 ~12~14일** (architect + backend-core + market-data + frontend 4명 분담).

---

## 권장 진행 순서 (Round 분할)

### R1 — 긴급 hotfix + 기반 (T1·T2·T6·T7)

- **목표**: EGW00202 즉시 해소 + 도메인 + 캘린더 시드 (실제 흐름 검증 가능 상태)
- **branch**: `agent/architect/track2-r1-hotfix`
- **사이즈**: ~3일
- **결과물 후 사용자 검증**: 평일 또는 dev offline-fallback 흐름 dogfooding

### R2 — 시세 WS 본격 (R2-A) + Guard·Audit·Resolver (R2-B)

R2는 사용자 보고(2026-05-04 장중 tick 미수신)에 따라 **R2-A**(시세 WS 본격 구현)이 우선이고, 기존 R2 잔여(Guard·Audit·Resolver)는 **R2-B**로 후속.

#### R2-A — KIS 실시간 시세 WS (체결가·호가·지수·Fallback)

- **하위 plan**: [`docs/superpowers/plans/2026-05-04-track2-r2-quote-ws.md`](./2026-05-04-track2-r2-quote-ws.md)
- **분할**:
  - R2-A1 — 체결가 (KisWsRouter + H0UNCNT0 + 자동 부팅 구독)
  - R2-A2 — 호가 + 지수 (H0UNASP0 + H0UPCNT0 + 신규 STOMP 토픽 + Orderbook UI + Index 카드)
  - R2-A3 — Fallback 통합 (TickFallbackEmitter + Market Session FSM wiring + 18종목 LRU + watchlist 변경 listener)
- **branch (3개)**:
  - `agent/market-data/track2-r2-a1-quote-ws`
  - `agent/market-data/track2-r2-a2-orderbook-index`
  - `agent/architect/track2-r2-a3-fallback-session-wiring`
- **결과물 후 사용자 검증**: 장중 차트 갱신 + KOSPI/KOSDAQ 등락률 갱신 + 호가창 + 장외 stale 배지

#### R2-B — Guard + Audit + Resolver (T3·T4·T5·T8·T9·T10·T11)

- **목표**: REJECTED audit trail + Guard chain 합류 + 캐시 어댑터 (1번 트랙 dogfooding 재개와 함께)
- **branch**: `agent/architect/track2-r2-b-guard-audit`
- **사이즈**: ~5일
- **결과물 후 사용자 검증**: 토요일 매수 → REJECTED row 보임

### R3 — Tick Fallback REST + Frontend (T12·T13·T14·T15·T16·T17·T18)

- **목표**: REST quote fallback + 시세 fallback emit + UI 표시 (R2-A3에서 emitter 본체는 이미 도입 — 본 R3는 REST quote 통합과 잔여 UI)
- **branch**: `agent/architect/track2-r3-fallback-ui`
- **사이즈**: ~3일 (R2-A3 도입분 제외)
- **결과물 후 사용자 검증**: 토요일 Dashboard 진입 → "장 마감 5/1 기준" 배지 + cached 가격

### R4 — Profile + ADR 정식 발행 + Docs (T19·T20·T21·T22)

- **목표**: 매트릭스 정밀화 + 운영 문서 갱신
- **branch**: `agent/architect/track2-r4-finalize`
- **사이즈**: ~3일
- **결과물 후 사용자 검증**: 트랙 종료 + 다음 트랙 트리거

### R5 (선택) — `MarketCalendarSyncJob` (T23)

- 1차 정적 시드로 충분하면 보류. 운영 부담 시 R5에서.

---

## 에이전트별 task 분담 (ADR 0017 git author 분리 정합)

| Task | 담당 | git author | 비고 |
|---|---|---|---|
| T1 | backend-core | `bot+backend-core@magicjar.local` | Flyway |
| T2 | architect | `bot+architect@magicjar.local` | 도메인 경계 |
| T3 | backend-core | `bot+backend-core@magicjar.local` | JPA 어댑터 |
| T4 | backend-core | `bot+backend-core@magicjar.local` | resolver 구현 |
| T5 | backend-core | `bot+backend-core@magicjar.local` | Redis 어댑터 |
| **T6** | backend-core | `bot+backend-core@magicjar.local` | EGW00202 진단 |
| **T7** | market-data | `bot+market-data@magicjar.local` | KIS 어댑터 hotfix |
| T8 | backend-core | `bot+backend-core@magicjar.local` | Guard chain |
| T9 | architect | `bot+architect@magicjar.local` | 도메인 sealed class |
| T10 | backend-core | `bot+backend-core@magicjar.local` | Flyway + 매핑 |
| T11 | backend-core | `bot+backend-core@magicjar.local` | OrderSender |
| T12 | architect | `bot+architect@magicjar.local` | 도메인 |
| T13 | market-data | `bot+market-data@magicjar.local` | tick listener |
| T14 | market-data | `bot+market-data@magicjar.local` | fallback emitter |
| T15 | backend-core | `bot+backend-core@magicjar.local` | api STOMP |
| T16 | backend-core | `bot+backend-core@magicjar.local` | REST |
| T17 | backend-core | `bot+backend-core@magicjar.local` | REST |
| T18 | frontend | `bot+frontend@magicjar.local` | UI |
| T19 | backend-core | `bot+backend-core@magicjar.local` | wiring |
| T20 | architect | `bot+architect@magicjar.local` | ADR + docs |
| T21 | architect | `bot+architect@magicjar.local` | docker + scripts |
| T22 | architect | `bot+architect@magicjar.local` | docs |
| T23 | backend-core | `bot+backend-core@magicjar.local` | batch (선택) |

**Husky pre-commit author 화이트리스트**: 위 4개 author 모두 `^bot\+(architect|backend-core|market-data|frontend)@magicjar\.local$` 정합 — ADR 0017.

---

## 사용자 결정 필요 사항 (실행 전 확인)

본 plan 진입 전 다음을 사용자가 확정:

1. **R1~R4 병렬 vs 순차**:
   - 추천 — R1 단독 (긴급) → R2·R3 병렬 → R4 마지막
   - 또는 사용자 토큰 절약 우선시 R1~R4 순차
2. **T23 채택 여부** (캘린더 자동 갱신)
   - 기본 보류 권장 — 1차 정적 시드 + 분기 수동 갱신 충분
3. **ADR 5개 발행 시점** (T20)
   - 모든 task 완료 후 발행 (안전) vs T8·T11 시점에 ADR 0031·0033 선발행 (더 견고한 의사결정 트레일)
4. **`MAGICJAR_DEV_OFFLINE_FALLBACK` default**
   - 추천 `false` (KIS 호출 시도 → REJECTED audit이 표준)
   - 또는 `true` (사용자 편의)
5. **EGW00202 우선순위**
   - R1 단독 처리 (사용자 톤 톤상 우선) vs R2와 함께
6. **frontend Badge 위치**
   - 페이지 상단 + 종목 카드 둘 다 (추천) vs 페이지 상단만

---

## 검증 매트릭스 (트랙 종료 시점 게이트)

| 시나리오 | 사용자 수동 검증 | 자동 테스트 |
|---|---|---|
| S1 (토요일 dev REJECT) | `/orders` 페이지에 REJECTED row 보임 | OrderControllerTest + KisOrderSenderTest |
| S2 (토요일 dev offline-fallback FILLED) | `/orders` 페이지에 FILLED row | OrderSenderProfileConfigTest |
| S3 (평일 정상 매수) | 월요일 dogfooding | KisOrderSenderTest + WireMock IT |
| S4 (EGW00202 미발현) | 평일 또는 dev offline-fallback | RealKisOrderAdapterIT (T7) |
| S5 (장 마감 fallback emit) | 16:00 이후 Dashboard 배지·cached 가격 | TickFallbackEmitterTest |
| S6 (휴일) | 5/5 어린이날 dogfooding | MarketSessionResolverImplTest + UI 수동 |
| Guard chain 회귀 | 1번 트랙 PriceSanity·DailyLimit·KillSwitch 모두 작동 | KisOrderSenderTest 회귀 |
| 캘린더 갱신 | `SELECT * FROM market_calendar WHERE...` | MarketCalendarJpaAdapterIT |
| Redis 캐시 hit/miss | `redis-cli GET quote:last:005930` | QuoteCacheRedisIT |
| profile 부팅 | mock·dev·prd 셋 다 9 컨테이너 healthy | docker compose smoke |

---

## 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-02 | 초안 작성 (architect, opus 4.7 1M) |
| 2026-05-04 | R2 진입 — R2-A 신설(시세 WS 본격, 하위 plan `2026-05-04-track2-r2-quote-ws.md`), R2-B는 기존 Guard·Audit 잔여로 분리. R1 EGW hotfix 머지 완료. (architect, opus 4.7 1M) |

---

## R2 진입 (2026-05-04)

**트리거**: 사용자 보고 — 장중 차트 미수신 + Dashboard 지수 등락률 불변. 진단 결과 **KIS 실시간 시세 WS subscribe 미구현**이 근본 원인.

**디스패치**: 본 plan의 R2를 두 분기로 분할:

1. **R2-A** — 시세 WS 본격 구현 (위 §R2 참조, 하위 plan: `2026-05-04-track2-r2-quote-ws.md`)
2. **R2-B** — Guard + REJECTED audit + Resolver wiring (기존 R2 task T3·T4·T5·T8·T9·T10·T11 그대로)

R2-A 머지 완료 + 사용자 검증 완료 후 R2-B 진입. R3는 R2-A3 이후 잔여(REST quote + 추가 UI)로 사이즈 축소.

**KIS trId 정합 검증** (R2-A plan §2-A): `H0UNCNT0`(체결가 통합)·`H0UNASP0`(호가 통합)·`H0UPCNT0`(지수 체결)·`H0UPANC0`(지수 예상)·`H0UNMKO0`(장운영) 5종 모두 GitHub source 검증 완료, 평문 (AES 미적용 — 체결통보만 적용).

**ADR 신규 발행 없음** — R2-A 결정은 ADR 0033·0035에 흡수 (ADR 0035에 시세 trId 표 footnote 추가는 R2-A1 시점 architect 직접 1줄 갱신).
