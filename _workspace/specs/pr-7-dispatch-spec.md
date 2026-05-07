# PR-7 sub-agent 분배 명세

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-7-w1-design.md` v1 + ADR 0042·0028·0040
- 본 문서: orchestrator가 backend-core·frontend 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-7-conflict-matrix.md` 참조.

---

## 1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-7a** | backend-core | M~L | `agent/backend-core/pr-7-w1-backend` | **병렬 1** |
| **PR-7b** | frontend | M | `agent/frontend/pr-7-w1-frontend` | **병렬 1** |

**디스패치 모드**: **병렬** (충돌 행렬 정합 — `_workspace/specs/pr-7-conflict-matrix.md` 참조).

**인터페이스 동결 시점**: 본 spec(`pr-7-w1-design.md` + 본 dispatch + 충돌 행렬) 머지 즉시 — backend·frontend 양쪽이 §3·§4 (REST 시그니처 LOCK)·§5 (부팅 잔고 워밍 인터페이스 LOCK)·§6 (Watchlist UI 계약 LOCK)을 바라보고 동시 시작 가능.

---

## 2. backend-core 위임 (PR-7a)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet) |
| 브랜치 | `agent/backend-core/pr-7-w1-backend` |
| base | `main` (PR-6 T-ONBOARD 머지된 상태) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-W1 backend — V3 마이그레이션 + Watchlist CRUD REST 4 endpoint + PositionBootstrapRunner + KisBalanceAdapter + KisAuthAdapter(운영용) + RedisPositionCacheAdapter + KisBalancePort(domain 신규)` |
| Task 파일 deliverables | `V3__watchlist_position_refinements.sql, WatchlistController.kt + DTO + Service, WatchlistJpaAdapter (domain port 구현), KisBalancePort.kt + KisBalance/KisHolding (domain), PositionBootstrapRunner.kt (consumer ApplicationRunner), KisBalanceAdapter.kt (consumer infrastructure/kis), KisAuthAdapter.kt (consumer 운영용 토큰 cache + 분산락 ADR 0038), RedisPositionCacheAdapter.kt (consumer infrastructure/cache), Kafka watchlist.changed.v1 producer, smoke test 2건` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | M~L |

### 2-2. 작업 범위 (Done 정의)

다음 모두 완료해야 PR-7a Done:

#### A. Flyway V3 마이그레이션
- [ ] `api/src/main/resources/db/migration/V3__watchlist_position_refinements.sql` — spec §3-3 LOCK 정합
  - instrument.market·type CHECK 완화 ('UNKNOWN' 추가)
  - watchlist 정렬 인덱스 (`idx_watchlist_user_priority`)
  - position user_id 인덱스 (`idx_position_user`)
- [ ] `docker compose up -d api-app` 후 V3 적용 검증

#### B. Watchlist CRUD (api 모듈)

##### B-1. domain 모듈 — 변경 0 또는 최소
- [ ] `domain/.../watchlist/WatchlistEntry.kt` — **PR-5a에 이미 존재 ✅**
- [ ] `domain/.../watchlist/port/WatchlistRepository.kt` — **PR-5a에 이미 존재 ✅**
- [ ] 변경 0 — domain은 PR-5a에 충분히 정의됨

##### B-2. api 모듈
- [ ] `api/.../persistence/WatchlistJpaEntity.kt` (신규)
- [ ] `api/.../persistence/WatchlistJpaRepository.kt` (Spring Data JPA `interface`) — `existsByUserIdAndCode`·`findByUserIdOrderByPriorityAscCreatedAtDesc` 등
- [ ] `api/.../persistence/WatchlistRepositoryAdapter.kt` — domain `WatchlistRepository` port 구현
- [ ] `api/.../persistence/InstrumentJpaEntity.kt` + `InstrumentJpaRepository.kt` (신규 — stub upsert용)
- [ ] `api/.../persistence/InstrumentRepositoryAdapter.kt` — `existsByCode`·`upsertStub`
- [ ] `api/.../service/WatchlistService.kt` — 비즈니스 로직
  - add(userId, code, priority?) — instrument stub upsert + watchlist add + audit + Kafka publish
  - remove(userId, code) — watchlist remove + audit + Kafka publish
  - list(userId, sort) — `findByUserIdOrderByPriority`
  - currentUser() helper — `user_account` 첫 row의 user_id resolve (single-tenant 단순화)
- [ ] `api/.../controller/WatchlistController.kt` — 3 endpoint (spec §4-1 LOCK 정합)
  - `GET /api/watchlists`
  - `POST /api/watchlists` (201 Created + Location header)
  - `DELETE /api/watchlists/{code}` (204 No Content)
- [ ] `api/.../controller/dto/WatchlistDto.kt` — `WatchlistEntryDto`·`WatchlistAddRequest` + `@Schema`(Swagger) + `@field:Pattern("^\\d{6}$")` validation
- [ ] Swagger 4단 어노테이션 (사용자 메모리 정합)

#### C. Kafka topic 신규 — `watchlist.changed.v1`
- [ ] `api/src/main/resources/application.yml` — Kafka bootstrap·producer config (PR-5a에 이미 있을 가능성 — 점검 후 보강)
- [ ] `api/.../service/WatchlistService.kt` 내 KafkaTemplate inject + 등록·삭제 시 publish
- [ ] payload JSON: `{"userId":"...","code":"...","action":"ADDED"|"REMOVED","occurredAt":"..."}`
- [ ] 본 PR-7 범위 — producer만 (consumer 측 listener는 T-W2)

#### D. Position Bootstrap (consumer 모듈)

##### D-1. domain 모듈 — 신규 port + DTO
- [ ] `domain/.../position/port/KisBalancePort.kt` — spec §5-4 LOCK 정합
- [ ] `domain/.../position/KisBalance.kt` + `domain/.../position/KisHolding.kt` (또는 KisBalancePort.kt 안에 nested) — Spring 의존 0 + Pure Kotlin
- [ ] `domain/.../position/port/PositionCachePort.kt` — **PR-5a에 이미 존재** (없으면 신규 추가). spec §5-5 정합 — `put`·`get`만 본 PR-7 구현, `applyExecution`·`getLastUpdatedAt`은 stub (NotImplementedError 또는 default impl)

##### D-2. consumer 모듈
- [ ] `consumer/.../bootstrap/PositionBootstrapRunner.kt` — spec §5-1 LOCK 정합
  - `ApplicationRunner` 구현
  - 활성 사용자 조회 (`UserKisCredentialRepository.findAllByAccountType("REAL")` — 또는 모든 type)
  - 활성 0명이면 idle return
  - forEach 직렬 — STAGE 0-1 (KIS REST) → 0-2 (DB upsert) → 0-3 (Redis 워밍)
  - 실패 시 KillSwitch auto-ON (`KillSwitchRepository.enable(userId, "POSITION_BOOTSTRAP_FAILED", e.message)`)
  - audit row INSERT — action="POSITION_BOOTSTRAP" / "POSITION_BOOTSTRAP_FAILED"
  - log: 시작·종료·실패 모두 마스킹된 user_id

- [ ] `consumer/.../infrastructure/kis/KisBalanceAdapter.kt` — spec §5-2 LOCK 정합
  - `KisBalancePort` 구현
  - WebClient (Spring `WebClient.builder()` Bean)
  - `user_kis_credential` BYTEA 읽고 `SecretCipher.decrypt` → APP_KEY·SECRET_KEY
  - `KisAuthAdapter.getAccessToken(userId, accountType)` 호출
  - KIS REST `POST /uapi/domestic-stock/v1/trading/inquire-balance`
  - host: 실전 `openapi.koreainvestment.com:9443` / 모의 `openapivts.koreainvestment.com:29443`
  - TR_ID: `TTTC8434R` (실전) / `VTTC8434R` (모의)
  - Headers: authorization, appkey, appsecret, tr_id, custtype="P"
  - Query params: CANO, ACNT_PRDT_CD="01", AFHR_FLPR_YN="N", OFL_YN="", INQR_DVSN="02", UNPR_DVSN="01", FUND_STTL_ICLD_YN="N", FNCG_AMT_AUTO_RDPT_YN="N", PRCS_DVSN="00", CTX_AREA_FK100="", CTX_AREA_NK100=""
  - response output1 → KisHolding list 매핑 (pdno, hldg_qty, pchs_avg_pric)
  - 메모리 zero-fill (APP_KEY·SECRET_KEY)
  - timeout 5s

- [ ] `consumer/.../infrastructure/kis/KisAuthAdapter.kt` — spec §5-3 LOCK 정합 (운영용 신규)
  - Redis cache `kis:token:{userId}:{accountType}` TTL 23h
  - 분산락 `kis:token:lock:{userId}:{accountType}` TTL 30s (SETNX)
  - `POST /oauth2/tokenP` (PR-6의 KisAuthValidator와 동일 endpoint, body 정합)
  - 메모리 zero-fill

- [ ] `consumer/.../infrastructure/cache/RedisPositionCacheAdapter.kt` — spec §5-5 LOCK 정합
  - `PositionCachePort` 구현
  - StringRedisTemplate + ObjectMapper (JSON 직렬화)
  - put: TTL 24h
  - get: cache miss 시 null
  - applyExecution·getLastUpdatedAt — `TODO("T-W2")` 또는 default impl

- [ ] `consumer/.../infrastructure/persistence/PositionRepositoryAdapter.kt` — domain `PositionRepository.upsertAll` 구현
  - JPA 또는 native SQL `INSERT ... ON CONFLICT (user_id, code) DO UPDATE SET quantity=EXCLUDED.quantity, avg_price=EXCLUDED.avg_price, updated_at=now()`
  - 또는 batch INSERT for performance (주석으로 향후 개선 명시)

- [ ] `consumer/.../infrastructure/persistence/UserKisCredentialRepositoryAdapter.kt` — domain `UserKisCredentialRepository.findAllByAccountType` 구현 (PR-6 api 모듈에는 이미 있으나 consumer는 신규)
  - **architect 결정**: PR-6의 `api/.../persistence/UserKisCredentialJpaRepository.kt`를 consumer에서도 import 가능하게 위치 이동? **아니다** — api와 consumer는 다른 Spring Boot context. consumer는 자기 자신의 JPA repository 별도 정의. domain port 의존성으로 통합.
  - 또는 consumer는 KIS 키만 필요 — UserKisCredentialJpaRepository를 consumer 모듈에 별도 정의 (entity·repository·adapter 3종)
  - **권장**: consumer가 자기 자신의 entity·repository·adapter 정의 (domain port 의존)

##### D-3. KillSwitch 통합
- [ ] `domain/.../risk/port/KillSwitchRepository.kt` — **PR-5a에 이미 존재 ✅**
- [ ] `consumer/.../infrastructure/persistence/KillSwitchRepositoryAdapter.kt` (신규) — domain port 구현
  - `enable(userId, reason, detail)` — kill_switch_state row UPSERT (enabled=true, reason, updated_by="POSITION_BOOTSTRAP")
  - kill_switch_state 테이블은 V1에 이미 존재

##### D-4. AuditTrail 통합
- [ ] `consumer/.../infrastructure/persistence/AuditTrailRepositoryAdapter.kt` (신규) — domain port 구현
  - audit_trail INSERT — actor="consumer", action, entity_type, entity_id, detail_json

#### E. (선택) Position Bootstrap 디버그 endpoint
- [ ] `api/.../controller/PositionController.kt` — `POST /api/positions/bootstrap`
- [ ] **분량 부담 시 빼도 OK** — orchestrator·architect 1차 권장은 **빼는 쪽**. T-W2 진입 시 합류

#### F. WebClient·Resilience4j 설정 (consumer 모듈)
- [ ] `consumer/.../config/WebClientConfig.kt` — Bean 정의 (api 모듈 PR-6과 동일 패턴 — copy)
- [ ] timeout 5s + 재시도 0 (단발 호출)
- [ ] Resilience4j Circuit Breaker는 본 PR-7 비범위 (단순화)

#### G. 검증 게이트 통과
spec §11-1 모든 항목 GREEN.

#### H. smoke test 2건 (룰 4 정합)
- [ ] `api/src/test/kotlin/.../WatchlistControllerTest.kt` — CRUD round-trip (Spring Boot Test + Testcontainers OR @MockBean)
- [ ] `consumer/src/test/kotlin/.../PositionBootstrapRunnerTest.kt` — 활성 user 0명 idle 검증 (단순 unit test)

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(db): V3 watchlist + position refinements — instrument CHECK 완화 + 인덱스 2건` | `V3__watchlist_position_refinements.sql` |
| 2 | `feat(domain): KisBalancePort + KisBalance + KisHolding (Position Bootstrap port)` | `domain/.../position/port/KisBalancePort.kt` + DTO |
| 3 | `feat(api): Watchlist CRUD REST 3 endpoint + Service + JPA persistence + instrument stub upsert` | `WatchlistController.kt` + `WatchlistService.kt` + DTO + persistence/* |
| 4 | `feat(api): watchlist.changed.v1 Kafka producer (T-W2 listener는 후속)` | `WatchlistService.kt` Kafka publish 부분 |
| 5 | `feat(consumer): KisAuthAdapter (운영용 토큰 cache + 분산락 ADR 0038)` | `KisAuthAdapter.kt` + WebClientConfig |
| 6 | `feat(consumer): KisBalanceAdapter (KIS REST inquire-balance TR TTTC8434R/VTTC8434R)` | `KisBalanceAdapter.kt` |
| 7 | `feat(consumer): RedisPositionCacheAdapter + PositionRepositoryAdapter (upsert)` | `RedisPositionCacheAdapter.kt` + `PositionRepositoryAdapter.kt` |
| 8 | `feat(consumer): PositionBootstrapRunner — ApplicationRunner + KillSwitch auto-ON 실패 처리` | `PositionBootstrapRunner.kt` + KillSwitch·AuditTrail adapter |
| 9 | `test(backend): Watchlist CRUD + PositionBootstrap smoke 2건` | test 파일 |

(commit 1~9 묶기 OK — 그러나 git rebase 충돌 시 분할 권장.)

### 2-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (api·consumer·domain) — Watchlist CRUD + Position Bootstrap (T-W1)

## Summary
- PR-7a: T-W1 backend — V3 마이그레이션(instrument CHECK 완화·인덱스 2건) + Watchlist CRUD REST 3 endpoint + PositionBootstrapRunner(consumer ApplicationRunner) + KisBalanceAdapter(KIS REST inquire-balance) + KisAuthAdapter(운영용 토큰 cache) + RedisPositionCacheAdapter + Kafka watchlist.changed.v1 producer.
- ADR 0042 Bootstrap 부분 + ADR 0028 Watchlist + ADR 0040 UI 흐름 정합. Recovery·UserOnboardedEvent listener·KIS WS subscribe는 T-W2로 명시 미룸.
- 정합: `_workspace/specs/pr-7-w1-design.md` + ADR 0042·0028·0040.

## 신규 ADR
없음 — ADR 0042·0028·0040 정합 구현.

## Scope
- domain: KisBalancePort + KisBalance/KisHolding 신규 (PR-5a watchlist·position 도메인 + port는 그대로 사용)
- api: WatchlistController + Service + DTO + JPA persistence (Watchlist + Instrument stub) + Kafka producer
- consumer: PositionBootstrapRunner + KisAuthAdapter(운영용) + KisBalanceAdapter + RedisPositionCacheAdapter + PositionRepositoryAdapter + UserKisCredentialRepositoryAdapter + KillSwitch·AuditTrail adapter
- batch: 변경 0
- frontend: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (T-S1 진입 시 또는 별도 묶음). 본 PR에 smoke 2건만 (Watchlist CRUD round-trip + PositionBootstrap idle 검증).

## 사용자 검증 방법
1. PR-6 머지 + 셋업 마법사 완료 (사용자 본인 KIS 키 등록)
2. `scripts/build-and-deploy.sh`
3. `curl -X POST http://localhost:8080/api/watchlists -H "Content-Type: application/json" -d '{"code":"005930"}'` → 201 + WatchlistEntryDto
4. `curl http://localhost:8080/api/watchlists` → 200 + 1건 배열
5. `curl -X DELETE http://localhost:8080/api/watchlists/005930` → 204
6. `docker logs magicjar-consumer-app | grep PositionBootstrap` → "Position bootstrap 시작" + "STAGE 0-1·0-2·0-3" log
7. `psql -h localhost -U magicjar -c "SELECT code,quantity,avg_price FROM position WHERE user_id='<8자리>'"` → 사용자 KIS 보유 종목 N건
8. `redis-cli GET position:current:<userId>` → JSON list (보유 종목)
9. `kafka-console-consumer --bootstrap-server localhost:9092 --topic watchlist.changed.v1 --from-beginning` → 등록 1 message + 삭제 1 message

## 정합 ADR
- 0042 (Bootstrap 부분) — Position Bootstrap & Recovery (Recovery는 T-W2)
- 0028 — Watchlist 기반 Ingestion Worker (인터페이스만 — Worker는 T-W2)
- 0040 — 관심종목 우선 UI 흐름
- 0046 — Onboarding & Credential (KIS 키 복호화 사용 시점)
- 0038 — KIS OAuth 24h 토큰 + Redis 분산락 (KisAuthAdapter)
- 0010 — Multi-tenant (user_id)
- 0043 — KillSwitch 행동 (auto-ON 트리거 정합)
```

### 2-5. backend-core 의존성·정합 의무

- **ADR 0042 Bootstrap 의무 항목**: spec §2 체크리스트 14개 항목 모두 구현 (Recovery·WS·UserOnboardedEvent listener는 T-W2 명시)
- **ADR 0028**: Kafka topic `watchlist.changed.v1` producer만 본 PR-7 구현. consumer listener는 T-W2.
- **ADR 0040**: REST endpoint는 ADR 0040 §2-4 신규 컴포넌트 정합 (`/api/watchlist` → 본 PR-7은 `/api/watchlists` 복수형 — RESTful 표준)
  - **architect 결정**: ADR 0040은 `/api/watchlist/{code}`(단수형) 사용. 본 PR-7은 RESTful 표준 따라 `/api/watchlists` 복수형 채택. 사유 — RFC 7807·HATEOAS 권장 + 후속 query params 자연 조합. ADR 0040 본문 update는 본 PR 비범위 (post-merge 별도 docs PR 또는 ADR 0028·0040 minor revision으로 정합).
- **ADR 0019**: 본 PR-7은 candle 테이블 미사용 (T-W2 진입 시).
- **ADR 0037 (KIS Rate Limit)**: 본 PR-7은 부팅 시 1회 + 디버그 endpoint 1회 — Rate Limiter 별도 도입 비범위. T-W2 진입 시 RateLimiter 도입.
- **ADR 0038 (KIS OAuth)**: KisAuthAdapter (운영용) 본 PR-7 신규. PR-6 KisAuthValidator (검증용) 그대로 유지 (api 모듈) — 이중 어댑터 아키텍처.
- **ADR 0043 (KillSwitch)**: Bootstrap 실패 시 auto-ON 트리거. KillSwitch UI 통합·Slack 통지는 T-O·T-NOTIFY (W3).
- **룰 5 (AI 호출 자제)**: spring-ai·anthropic·openai import 0건
- **룰 4 (테스트 마지막 일괄)**: R3 일괄은 T-S1 또는 별도 묶음 — 본 PR-7a는 smoke 2건만

**KIS-first 의무**: KIS endpoint는 ADR 0042 §2-2 + KIS open-trading-api `examples_llm/.../inquire_balance.py` 출처. tr_id·endpoint 자체 추론 0건. 분량 부담 시 kis-code-assistant MCP로 추가 검증 가능.

---

## 3. frontend 위임 (PR-7b)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | frontend (sonnet) |
| 브랜치 | `agent/frontend/pr-7-w1-frontend` |
| base | `main` (PR-6 T-ONBOARD 머지된 상태) |
| 작업 author | `bot+frontend@magicjar.local` |
| 작업 author name | `magicjar-frontend` |
| Task 파일 goal | `T-W1 frontend — WatchlistPage(빈 상태·등록 폼·list·삭제) + TanStack Query hooks + axios 통합 + 라우트 + smoke 1건` |
| Task 파일 deliverables | `pages/Watchlist/WatchlistPage.tsx, components WatchlistAddForm·WatchlistRow·WatchlistEmpty, api/watchlist.ts (TanStack Query hooks), App.tsx 라우트 추가, AppLayout/AppHeader.tsx (top nav), smoke test 1건` |
| 머지 정책 | rebase |
| 분량 | M |

### 3-2. 작업 범위 (Done 정의)

#### A. WatchlistPage 메인 컴포넌트
- [ ] `frontend/src/pages/Watchlist/WatchlistPage.tsx` (신규)
- [ ] `useWatchlist()` 호출 → list 데이터
- [ ] 빈 list → `<WatchlistEmpty />` / 채워진 list → `<WatchlistRow />` 매핑
- [ ] 상단에 `<WatchlistAddForm />`
- [ ] 페이지 헤더: "관심종목" + 등록 종목 수 표시 (예: "총 3종목")
- [ ] Tailwind 기반 layout (max-w-3xl·py-8 등 — PR-6 SetupWizard 정합)

#### B. Sub Components

- [ ] `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` (또는 `components/watchlist/WatchlistAddForm.tsx`)
  - 1 input (종목 코드 6자리)
  - clientside validation (`/^\d{6}$/`)
  - 등록 버튼 (loading state)
  - inline error 메시지 (validation 실패·409 중복·400 형식)
  - 등록 성공 시 input clear + inline success 메시지 1초

- [ ] `frontend/src/pages/Watchlist/WatchlistRow.tsx` (또는 components)
  - 1 row 구성 — code·priority·등록일 + 휴지통 버튼
  - 휴지통 클릭 시 `window.confirm("관심종목 005930을(를) 삭제하시겠습니까?")`
  - confirm 확인 시 `useRemoveWatchlist()` mutation 호출
  - row fade-out animation (200ms — Tailwind transition)

- [ ] `frontend/src/pages/Watchlist/WatchlistEmpty.tsx`
  - "관심종목이 없습니다" 메시지
  - "관심종목 등록하기" CTA 버튼 (input 포커스)

#### C. TanStack Query hooks + axios

- [ ] `frontend/src/api/watchlist.ts` (신규)
  - `watchlistApi.list()` · `watchlistApi.add(req)` · `watchlistApi.remove(code)`
  - DTO 타입 정의 (`WatchlistEntryDto`·`WatchlistAddRequest`)

- [ ] `frontend/src/api/hooks/useWatchlist.ts` (신규)
  - `useWatchlist()` — useQuery (queryKey ['watchlist'], staleTime 30s)
  - `useAddWatchlist()` — useMutation + invalidateQueries
  - `useRemoveWatchlist()` — useMutation + invalidateQueries

- [ ] PR-6의 `lib/axios.ts` 그대로 재사용 (interceptor·baseURL 정합)

#### D. App.tsx 라우트 추가

- [ ] `frontend/src/App.tsx` — `/watchlist` 라우트 추가
  - 기존 `/`·`/setup` 외에 `<Route path="/watchlist" element={<WatchlistPage />} />`
  - PR-6 OnboardingGuard 라우팅 그대로 (셋업 미완 시 `/setup`으로 redirect — `/watchlist` 진입도 OnboardingGuard 통과 후)
  - 1차 default 라우트는 `/` (Dashboard placeholder) 그대로 — spec §6-8 정합

#### E. AppLayout / AppHeader (top nav)

- [ ] `frontend/src/components/layout/AppLayout.tsx` 또는 `AppHeader.tsx` (PR-5b·PR-6에 부재 시 신규)
  - 상단 nav: "magicJar" 로고 + "대시보드" + "관심종목" 메뉴
  - `<NavLink>` 사용 — 활성 메뉴 highlight (Tailwind `aria-current` 또는 `className` 분기)
  - **주의**: PR-6 SetupWizard는 nav 미표시 (셋업 흐름 — UI 단순화). 셋업 완료 후에만 nav 표시.
  - 구현 — App.tsx에서 path가 `/setup`이면 `<AppLayout>` 미적용, 그 외 Layout으로 감싸기

#### F. 사용자 피드백

- [ ] inline error/success 메시지 — Tailwind 색상 (red-600·green-600)
- [ ] toast 라이브러리 미도입 (1차 단순화 — spec §6-7 정합)

#### G. 검증 게이트 통과
spec §11-2 모든 항목 GREEN.

#### H. smoke test 1건 (룰 4 정합)
- [ ] `frontend/src/__tests__/watchlist-flow.test.tsx` — 빈 상태 → 등록 → list 1건 표시 (vitest + react-testing-library + msw)
  - msw로 GET `/api/watchlists` mock (빈 → 등록 후 1건)
  - "005930" 입력 + 등록 버튼 → list row 표시 검증

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(frontend): TanStack Query watchlist hooks + axios DTO 타입` | `api/watchlist.ts` + `api/hooks/useWatchlist.ts` |
| 2 | `feat(frontend): Watchlist 빈 상태·등록 폼·row 컴포넌트 (Tailwind)` | `pages/Watchlist/WatchlistEmpty.tsx`·`WatchlistAddForm.tsx`·`WatchlistRow.tsx` |
| 3 | `feat(frontend): WatchlistPage + /watchlist 라우트 + AppHeader top nav` | `pages/Watchlist/WatchlistPage.tsx` + `App.tsx` + `components/layout/AppHeader.tsx` |
| 4 | `test(frontend): watchlist flow smoke 1건` | `__tests__/watchlist-flow.test.tsx` |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
frontend — Watchlist CRUD UI 1순위 진입점 (T-W1)

## Summary
- PR-7b: T-W1 frontend — WatchlistPage(빈 상태·등록 폼·list·삭제) + TanStack Query hooks(useWatchlist·useAddWatchlist·useRemoveWatchlist) + axios `/api/watchlists` 통합 + `/watchlist` 라우트 + AppHeader top nav.
- ADR 0040 §2-4 신규 컴포넌트 정합 — `pages/Watchlist/WatchlistPage`. 단 라우트 default는 `/`(Dashboard placeholder) 그대로 유지 (Dashboard 본격 화면은 T-S1 이후).
- 정합: `_workspace/specs/pr-7-w1-design.md` §6 (UI 계약 LOCK) + ADR 0040.

## 신규 ADR
없음 — ADR 0040·0028 정합 구현.

## Scope
- frontend 모듈만
- 신규: `pages/Watchlist/WatchlistPage.tsx` + sub components (3) + `api/watchlist.ts` + `api/hooks/useWatchlist.ts` + `components/layout/AppHeader.tsx`
- 변경: `App.tsx` (/watchlist 라우트 + Layout 적용) + smoke test 1건
- backend·docker compose: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (T-S1 진입 시 또는 별도 묶음). 본 PR에 smoke 1건만 (등록 → list 표시).

## 사용자 검증 방법
1. PR-7a backend 머지된 main 상태 확인
2. `cd frontend && pnpm dev` (또는 `scripts/build-and-deploy.sh`)
3. PR-6 셋업 마법사 완료 (사용자 본인 KIS 키)
4. `localhost:3000/watchlist` 진입 → "관심종목이 없습니다" 빈 상태 + CTA
5. "005930" 입력 + 등록 버튼 → 1초 이내 list에 1행 표시 (삼성전자)
6. 같은 코드 재 등록 → "이미 등록된 종목입니다" inline error
7. "ABC" 입력 → "6자리 숫자만 가능합니다" inline error
8. 휴지통 클릭 → confirm → 확인 → row 사라짐
9. 새로고침 → 등록 종목 유지 (DB 영속)
10. 브라우저 DevTools Network → 401 응답 0건 (셋업 완료 사용자 = OnboardingGuardFilter 통과)

## 정합 ADR
- 0040 — 관심종목 우선 UI 흐름
- 0028 — Watchlist 기반 Ingestion Worker (인터페이스만 — Worker는 T-W2)
- 0009 — frontend SPA (React 19 + TS + Vite + Tailwind + LWC v4)
- 0046 — Onboarding & Credential (셋업 완료 사용자 진입 정합)
```

### 3-5. frontend 의존성·정합 의무

- **ADR 0040 §2-2**: 빈 상태 CTA "관심종목 등록하기" 의무
- **ADR 0040 §2-4**: 신규 컴포넌트 위치 — `frontend/src/pages/Watchlist/`
- **ADR 0009**: React 19 + TS + Tailwind + LWC v4 + pnpm — PR-5b·PR-6 스택 그대로
- **PR-5b·PR-6 의존**: lib/axios.ts·TanStack Query·Zustand·React Router 그대로 사용
- **TradingView Advanced 의존 0**: 본 PR-7b는 차트 0 — 무관 (차트는 T-S1)
- **사용자 메모리 `feedback_no_mock_data.md`**: 잘못된 ticker·KIS reject 시 "데이터 없음" 표시 의무 — 단 본 PR-7b 단계는 시세 표시 자체가 비범위 (T-S1). 등록 자체는 옵션 C(raw code) 정합 — 잘못된 코드 등록 가능, 시세는 T-W2에서 차단.

---

## 4. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 4-1. REST API 시그니처 (spec §4-1 LOCK)

| Endpoint | Request DTO | Response DTO | Status |
|---|---|---|---|
| `GET /api/watchlists` | (none) — query `?sort=priority|created` | `List<WatchlistEntryDto>` { id, userId, code, priority, createdAt } | 200 |
| `POST /api/watchlists` | `WatchlistAddRequest` { code, priority? } | `WatchlistEntryDto` | 201 / 400 / 401 / 409 |
| `DELETE /api/watchlists/{code}` | (none) | (empty) | 204 / 400 / 401 / 404 |
| `POST /api/positions/bootstrap` (선택) | (none) | `PositionBootstrapResult` | 200 / 401 / 503 / 504 |

### 4-2. ErrorResponse 공통 포맷 (PR-6 정합 재사용)

```json
{
  "code": "INVALID_TICKER_FORMAT" | "WATCHLIST_DUPLICATE" | "WATCHLIST_NOT_FOUND" | "ONBOARDING_REQUIRED" | "KIS_UNAVAILABLE" | "VALIDATION_ERROR",
  "message": "한국어 메시지"
}
```

### 4-3. 예시 페이로드 (양쪽 검증용)

#### Watchlist 등록 요청
```json
POST /api/watchlists
{
  "code": "005930"
}

→ 201
{
  "id": 1,
  "userId": "74683223",
  "code": "005930",
  "priority": 0,
  "createdAt": "2026-05-07T10:23:45.123Z"
}

→ 409 (이미 등록)
{
  "code": "WATCHLIST_DUPLICATE",
  "message": "이미 등록된 종목입니다"
}

→ 400 (형식 위반)
{
  "code": "INVALID_TICKER_FORMAT",
  "message": "종목 코드는 6자리 숫자여야 합니다 (예: 005930)"
}
```

#### Watchlist list — 빈 상태
```json
GET /api/watchlists
→ 200
[]
```

#### Watchlist list — 채워진 상태
```json
GET /api/watchlists
→ 200
[
  { "id": 1, "userId": "74683223", "code": "005930", "priority": 0, "createdAt": "2026-05-07T10:23:45.123Z" },
  { "id": 2, "userId": "74683223", "code": "035720", "priority": 1, "createdAt": "2026-05-07T11:00:12.456Z" }
]
```

#### Watchlist 삭제
```json
DELETE /api/watchlists/005930
→ 204 (No Content)
```

#### Kafka watchlist.changed.v1 message
```json
{
  "userId": "74683223",
  "code": "005930",
  "action": "ADDED",
  "occurredAt": "2026-05-07T10:23:45.123Z"
}
```

### 4-4. SoT 변경 시 처리

본 spec(`pr-7-w1-design.md` + 본 dispatch + 충돌 행렬)이 머지된 후 양쪽 sub-agent 진행 중에 시그니처 변경이 필요하면:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-7 작업 중엔 spec 변경 0건 의무 (사용자 본인 결정 외).

---

## 5. 운영·실패 대응

### 5-1. backend-core가 PR-7a 분량 초과 인지 시
- Watchlist CRUD를 PR-7a-1, Position Bootstrap을 PR-7a-2로 분할 가능 (orchestrator 사전 동의 필요)
- frontend는 PR-7a-1 (Watchlist CRUD) 머지 후 즉시 진행 가능
- Position Bootstrap은 frontend 무관 — frontend 작업 차단 없음

### 5-2. frontend가 PR-7b 진행 중 PR-7a 미머지 발견 시
- frontend는 OpenAPI 스펙(spec §4-1 + dispatch §4-3 예시 페이로드)만 보고 진행 가능 — backend mock 시뮬레이션
- `pnpm dev` proxy가 backend 부재 시 502 → frontend는 msw(Mock Service Worker)로 mock 서버 띄워서 개발 가능
- 단 통합 검증 (DoD #3·#4·#6)은 PR-7a 머지 후
- 즉, **frontend 단독 PR 머지 OK** — backend 부재 시에도 빌드·smoke 통과

### 5-3. CI fail 시
- backend-core·frontend 본인 영역 fail → 본인 PR에서 수정
- spec 자체 결함 발견 → architect 호출 → SoT 갱신 + sub-agent 작업 재개

### 5-4. ADR 정합 누락 발견 시
- backend-core·frontend는 직접 ADR 변경 금지
- architect 호출 → 별도 PR로 ADR 정정 → main 머지 후 sub-agent 작업 재개

### 5-5. KIS 검증 endpoint URL·TR_ID 정정 필요 시
- `kis_devlp.yaml` 또는 KIS 공식 문서가 변경 — architect 호출 → spec §5-2 갱신
- 본 PR-7의 KIS endpoint·TR_ID 출처는 ADR 0042 §2-2 + KIS open-trading-api `examples_llm/.../inquire_balance.py` (정합 의무)

### 5-6. PR-7a Position Bootstrap이 KIS 다운으로 검증 어려움
- backend-core가 KIS REST mock(WireMock) 또는 단순 unit test로 Bootstrap 흐름 검증
- 통합 검증 (DoD #12·#13·#14)은 사용자 직접 KIS 운영 시점에 자체 검증
- 본 PR-7a 머지 게이트 #9~#13은 mock 기반 검증 OK (사용자 자체 검증은 별도)

---

## 6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-7 sub-agent 분배 명세 v1 발행. PR-7a (backend-core) + PR-7b (frontend) 병렬 권장. 인터페이스 동결 + REST 시그니처 LOCK + 부팅 잔고 워밍 인터페이스 LOCK + 예시 페이로드 (architect, opus 4.7 1M). |
