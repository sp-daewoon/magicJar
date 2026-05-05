# R3-5c-3 — KIS 토큰 일원화 + Redis 분산 lock (Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- 작성일: 2026-05-05
- 작성자: architect (orchestrator=architect, ADR 0030)
- 상태: Draft → 자율 실행 PR1+PR2까지, 나머지 사용자 컨펌 필요
- 트랙: R3-5c (Phase F+ — KIS 외부 API 정책 정공법 마무리)
- spec: `docs/superpowers/specs/2026-05-05-track-r3-5c-3-kis-token-unification-design.md`

---

## 0. 요약

spec §3 (12 결정사항) · §4 (11경로 매트릭스) · §5~§8 (도메인·Redis·HASH 스키마)을 코드로 옮긴다.

**Goal:** REST 10 + WS 2 = 11경로의 KIS 인증 발급을 단일 store + Redis 분산 single-flight + multi-tenant scope 추상으로 통합한다. 운영 중 다중 JVM이 동시에 KIS `/oauth2/tokenP`를 호출해도 1회만 발생.

**Non-goals (spec §15):** pyworker 통합, KIS revokeP 자동화, KIS 응답 데이터 캐싱, 실전 키 발급, Redis Sentinel/Cluster, MCP server.

---

## 1. PR 분할 결정 (architect 판단)

### 1-1. 후보안 비교

| 안 | PR 수 | diff 추정 | 회귀 위험 | 사용자 외부 액션 | 머지 안전성 |
|---|---|---|---|---|---|
| **단일 PR** | 1 | ~1500+줄 | 매우 높음 (11경로 + 4 yml + 신규 의존성 + .env 정정 한 묶음) | docker rebuild + .env 정정 | 자율 머지 절대 불가 |
| **2 PR** (port+helper / 어댑터+yml+env) | 2 | ~400 + ~1100줄 | 높음 | 후자 PR이 docker rebuild + .env 정정 | 자율 머지 위험 |
| **5 PR** (port / api / batch / consumer / yml+env) | 5 | ~400 + ~350 + ~250 + ~300 + ~200줄 | 보통 | PR5에서만 .env + docker rebuild | **선택** |
| **6 PR** (port / helper / api / batch / consumer / yml+env) | 6 | port와 helper를 분리하면 helper가 caller 0인 PR이 되어 검증 불가 | 분할 비용 > 이득 | 동일 | 비선택 |

### 1-2. 5 PR 채택 + 자율 실행 범위 분리

**자율 실행 (autonomy ON 윈도우 안 머지)**
- **PR1**: 도메인 port + scope sealed class + 공유 helper. **외부 caller 시그니처 호환 유지** (deprecated overload로 `currentTokenOrIssue(mode: String)` 잔존).
- **PR2**: Redisson 의존성 + `KisAuthRedissonConfig` Bean + 메트릭 등록. `@ConditionalOnProperty(magicjar.kis.redis-token.enabled, default=false)` — 디폴트 비활성. 회귀 0.

**사용자 컨펌 후 진입 (자율 머지 보류)**
- **PR3**: api 6 어댑터 통합 (cachedToken 필드 제거 + port 주입 + 표준 401 retry once 패턴). `KisChartCandleAdapter`는 이미 통합됨, 5건 신규.
- **PR4**: batch 3 어댑터 통합 + `KisCorpActionTokenHolder` 폐기 + `MinuteCandleBackfillJob` 통합.
- **PR5**: consumer REST 통합 (KisHttpSession → port wrapper) + WS approval 통합 (KisApprovalStorePort 신규) + 설정 prefix `magicjar.kis.*` 일원화 + docker-compose env 매핑 + .env.example 갱신 + `magicjar.kis.redis-token.enabled=true` 활성화.

### 1-3. 자율 실행 범위가 PR1+PR2까지인 이유

architect 자율 머지 6 체크리스트 (`development-rules.md §8`):

| # | 체크 | PR1+PR2 | PR3 | PR4 | PR5 |
|---|---|---|---|---|---|
| 1 | diff 일독 | OK (~400+~250) | 보통 (~350) | 보통 (~250) | 큼 (~300+200) |
| 2 | CI GREEN | OK (단위테스트만) | OK | OK | **사용자 .env 미갱신 시 mock에서만 GREEN** |
| 3 | 모듈 테스트 통과 | OK | OK | OK | OK |
| 4 | QA 트리거 (ADR 0016) | #2 도메인 모델 깨는 변경 ✓ → 셀프 리뷰 | #5 회귀 영역 11경로 ✓ → boundary-qa 권장 | 동상 | #5 + 운영 영향 ✓ |
| 5 | worktree 정리 | OK | OK | OK | OK |
| 6 | CLAUDE.md 라인 | OK | OK | OK | OK |
| **외** | **사용자 외부 액션** | **없음** | 없음 | 없음 | **있음 (.env 정정 + docker rebuild)** |

PR5는 외부 액션이 있어 자율 머지 시 사용자 운영이 깨진다. PR3·PR4는 외부 액션은 없으나 **회귀 영역이 큼 (어댑터 6+3개) + boundary-qa 권장 트리거**라 같은 자율 윈도우에서 일괄 머지 부적절.

→ **PR1+PR2 자율 실행 후 본 사이클 일단 정지, 사용자 컨펌 받아 PR3~PR5 별도 사이클 진입.**

---

## 2. 회귀 검증 단위

### 2-1. PR1 (도메인 port + scope + helper)

- `:domain:test` — `KisAuthScope.tokenKey()/lockKey()` 정합 (System / User 4 변종).
- 기존 `KisChartCandleAdapter` 호출이 deprecated overload로 그대로 동작 (회귀 0).

### 2-2. PR2 (Redisson Bean + 메트릭)

- `@ConditionalOnProperty(magicjar.kis.redis-token.enabled, matchIfMissing=false)` → 디폴트 비활성 → 기존 InMemory path 유지.
- Redisson 의존성 추가만으로는 회귀 0 (실제 사용처는 PR3~5).
- 단위 테스트: `KisAuthRedissonConfig`가 `@ConditionalOnProperty` true 시에만 Bean 등록.

### 2-3. PR3 (api 6 어댑터)

- 어댑터별 단위 테스트 5종 (KisInstrumentInfo, KisInvestorTrend, KisMarketIndex, KisOrderbook, KisRecentTrades) — port mock 주입 + 401 retry 시나리오.
- `KisMarketIndexAdapter` 변종(401 즉시 재발급 → retry once 표준)이 폐기되었음을 명시 + 회귀 검증.

### 2-4. PR4 (batch 3 어댑터)

- `RealKisRestAdapter` WireMock IT (시나리오 7·8 R3-4 회귀).
- `RealKisCorpActionAdapter` 회귀 (KisCorpActionTokenHolder 폐기 + port 직접 주입).
- `MinuteCandleBackfillJob` 단위 테스트.

### 2-5. PR5 (consumer + 설정 일원화)

- `KisHttpSession` per-appKey 캐시 폐기 → port wrapper 회귀.
- `RealKisOrderAdapter` IT (1번 트랙 산출물).
- `KisWsApprovalProvider` 회귀 (체결통보 WS subscribe).
- 시세 WS approval — 신규 `ConsumerRedisKisApprovalStoreAdapter` 추가 → 시세 WS 연결 회귀.
- **multi-JVM IT** — testcontainers redis + WireMock + 100개 동시 호출 → KIS mock 호출 1회 검증.
- mock profile 부팅 회귀 (Redisson Bean이 mock에서도 등록되지만 KIS 호출 0).

---

## 3. 트러블슈팅 후보

| # | 항목 | 대응 |
|---|---|---|
| 1 | Spring Boot 3.3.5 × Redisson 3.40.2 호환 | `redisson-spring-boot-starter:3.40.2` artifact는 SB 3.3 매핑 공식 지원. 빌드 실패 시 `redisson:3.40.2` (non-starter) 직접 + 수동 Bean 등록으로 fallback. |
| 2 | testcontainers Redis vs embedded redis | testcontainers `redis:7.4-alpine` 권장 (compose와 정합). embedded `it.ozimov.embedded-redis`는 maintenance 종료 위험. |
| 3 | Redis 다운 fallback IT | `Testcontainers.redis.stop()` 후 fallback 동작 + `kis_token_redis_unavailable_total` 카운터 증가 검증. |
| 4 | RTopic publish/subscribe 단일 JVM 테스트 | RedissonClient 1개 + 두 abstract adapter 인스턴스 → 한쪽 invalidate → 다른 쪽 L1 evict 검증. |
| 5 | mock profile에서 Redisson 미작동 | `@ConditionalOnProperty(magicjar.kis.redis-token.enabled=true)` + mock yml에서 `enabled: false`. mock 부팅 회귀 0. |
| 6 | RestClient `SimpleClientHttpRequestFactory` retain | 어댑터별 RestClient 그대로 유지 (port는 token만 책임, HTTP 호출은 어댑터가). 기존 패턴 보존. |
| 7 | `KisCredentialProvider.forSystem(mode)` 추가 시 track1 산출물(`forUser`) 호환 | spec §5-4 — interface에 default impl `forSystem`을 abstract로 추가하면 track1 `DbKisCredentialProvider`가 컴파일 fail. **default method 또는 별도 인터페이스 분리** 검토. |

---

## 4. 라인 변경량 / 영향 모듈 추정

| PR | 라인 변경 (추정) | 신규 파일 | 수정 파일 | 영향 모듈 |
|---|---|---|---|---|
| PR1 | +400 -10 | `KisAuthScope.kt`, `KisApprovalScope.kt`, `KisApprovalStorePort.kt`, `AbstractRedisKisAuthAdapter.kt` (4) | `KisTokenStorePort.kt` (signature 일반화 + deprecated overload), `InMemoryKisTokenStoreAdapter.kt`, `KisChartCandleAdapter.kt` (caller 1 정합) | domain · api |
| PR2 | +250 -0 | `KisAuthRedissonConfig.kt`, `KisAuthMetrics.kt` | `api/build.gradle.kts`, `batch/build.gradle.kts`, `consumer/build.gradle.kts` (Redisson 의존성) | api · batch · consumer (Bean만, 호출 없음) |
| PR3 | +350 -200 | 단위 테스트 5종 보강 | api 6 어댑터 (KisInstrumentInfo / KisInvestorTrend / KisMarketIndex / KisOrderbook / KisRecentTrades) — `cachedToken`/`issueToken()` 제거 | api |
| PR4 | +250 -150 | - | `RealKisRestAdapter.kt`, `MinuteCandleBackfillJob.kt`, `RealKisCorpActionAdapter.kt`. **삭제: `KisCorpActionTokenHolder.kt`** | batch |
| PR5 | +500 -300 | `ConsumerRedisKisTokenStoreAdapter.kt`, `ConsumerRedisKisApprovalStoreAdapter.kt`, multi-JVM IT, runbook | `KisHttpSession.kt`, `KisWsApprovalProvider.kt`, `application.yml` 4개, `docker/docker-compose.yml`, `.env.example`, 외부-API-policy 문서 | consumer + ops |

**합계 추정: +1750 -660 ≈ 2410 변경 라인.**

---

## 5. 마이그레이션 무중단 가능성

spec §13-2 인용. 가능. 컨테이너 재시작 1회 (PR5 머지 시 `docker compose up -d` 재기동, 다운타임 ~30s × 3 서비스).

핵심: PR5 머지 전까지는 `magicjar.kis.redis-token.enabled=false`이므로 InMemory path 유지 + 기존 운영 그대로. PR5에서 활성화 + 사용자 .env 정정 + 사용자 docker rebuild로 한 번에 전환.

---

## 6. 롤백 plan

### 6-1. PR별 롤백 가능성

| PR | 롤백 방식 |
|---|---|
| PR1 | revert PR. caller `KisChartCandleAdapter`의 deprecated overload 호출이 그대로 작동. 회귀 0. |
| PR2 | revert PR. Redisson 의존성 제거. Bean이 어차피 미작동 상태였으므로 회귀 0. |
| PR3 | revert PR. api 6 어댑터가 자체 cachedToken 패턴으로 복귀. 회귀 0. |
| PR4 | revert PR. batch 3 어댑터 자체 cachedToken 패턴으로 복귀. `KisCorpActionTokenHolder` 부활. |
| PR5 | revert PR. **단, .env 정정·docker-compose 변경이 사용자 환경에 적용됐다면 그 부분 수동 롤백 필요.** 사용자에게 명시. |

### 6-2. EGW00133 폭주 시 비상 차단

사용자 path 전체 차단되는 시나리오. 즉시 `magicjar.kis.redis-token.enabled=false` 환경변수 변경 + 컨테이너 재기동 → InMemory path 회복. 사용자 차트는 작동 (race 위험 다시 노출되나 기존 운영 수준).

### 6-3. Redis 다운 fallback 검증 시나리오

PR5 머지 후 사용자 환경에서 `docker stop redis` → 사용자 차트 호출 → ERROR 로그 + 메트릭 카운터 + in-memory fallback 작동 검증 → `docker start redis` 후 자동 회복.

---

## Phase 1 — 도메인 port + scope (PR1, 자율 실행)

### Task 1: `KisAuthScope` / `KisApprovalScope` sealed class 신설

**Files:**
- Create: `domain/src/main/kotlin/com/mj/domain/market/kis/port/KisAuthScope.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/market/kis/port/KisApprovalScope.kt`
- Test: `domain/src/test/kotlin/com/mj/domain/market/kis/port/KisAuthScopeTest.kt`

- [ ] Step 1: `KisAuthScope` sealed class 작성 (spec §3 D2, §5-3)
  - `System(mode: String)` / `User(userId: String, mode: String)`
  - `tokenKey()`, `lockKey()` 함수
- [ ] Step 2: `KisApprovalScope` sealed class 동일 패턴
- [ ] Step 3: 단위 테스트 — 4 변종 키 정합

### Task 2: `KisTokenStorePort` 시그니처 일반화 + deprecated overload

**Files:**
- Edit: `domain/src/main/kotlin/com/mj/domain/market/kis/port/KisTokenStorePort.kt`

- [ ] Step 1: 신규 시그니처
  ```kotlin
  fun currentTokenOrIssue(scope: KisAuthScope): String
  fun invalidate(scope: KisAuthScope)

  @Deprecated("R3-5c-3: KisAuthScope 기반으로 전환", ReplaceWith("currentTokenOrIssue(KisAuthScope.System(mode))"))
  fun currentTokenOrIssue(mode: String): String =
      currentTokenOrIssue(KisAuthScope.System(mode))

  @Deprecated("R3-5c-3", ReplaceWith("invalidate(KisAuthScope.System(mode))"))
  fun invalidate(mode: String) =
      invalidate(KisAuthScope.System(mode))
  ```
- [ ] Step 2: `InMemoryKisTokenStoreAdapter`가 신규 시그니처 구현. 기존 mode 분기 → scope.mode로 변환.

### Task 3: `KisApprovalStorePort` 도메인 port 신설

**Files:**
- Create: `domain/src/main/kotlin/com/mj/domain/market/kis/port/KisApprovalStorePort.kt`

- [ ] Step 1: spec §5-2 시그니처 옮기기

### Task 4: 공유 helper `AbstractRedisKisAuthAdapter` (위치 결정)

**위치 결정:** `domain/.../market/kis/port/`에 두면 domain이 Redisson을 의존하게 되어 hexagonal 위반. **별도 모듈은 빌드 비용 과다.** → **`api`·`batch`·`consumer`에 각각 동일 abstract class를 internal package로 두는 대신, 공유 위치를 `consumer/.../infrastructure/kis/auth/` 같은 가장 무거운 모듈에 두고 다른 모듈이 의존하는 것은 모듈 의존 방향 위반.**

**채택:** `domain` 모듈에 **port-only 추상 (Redisson 미의존)** + `각 모듈 인프라에 RedissonClient를 주입받는 helper 자체 구현`. 즉 PR1에선 `AbstractRedisKisAuthAdapter`를 만들지 않고 **인터페이스 + 도메인 로직(L1 cache + scope 관리)만 도메인에 두고**, 실제 Redisson lock·HASH는 모듈별 어댑터(PR2~PR5)에서 직접 작성한다. 코드 중복은 ~150줄/모듈 × 3 = 450줄 늘지만 의존 방향 보존.

**대안:** 신규 `:shared-kis-auth` Gradle 모듈 — 빌드 시간 + ktlint 설정 추가. 본 사이클 spec §6-1의 abstract class를 짤짤하게 위치시키기 부담. **plan에서 helper 모듈은 미생성, 모듈별 직접 구현으로 결정.**

**Files (PR1 수정):** 없음 (Redisson은 PR2). PR1은 도메인 port + scope만.

### Task 5: 기존 caller 호환 유지 회귀 검증

**Files:**
- Edit: `api/src/main/kotlin/com/mj/api/infrastructure/kis/KisChartCandleAdapter.kt:53,145,192` (호출처 3곳)

- [ ] Step 1: 기존 `tokenStore.currentTokenOrIssue(mode)` → `tokenStore.currentTokenOrIssue(KisAuthScope.System(mode))` 정합 (deprecated overload 자동 위임으로 무수정도 가능, 그러나 명시 전환 권장).
- [ ] Step 2: `:api:test` PASS — `KisChartCandleAdapter` 회귀 0.

---

## Phase 2 — Redisson Bean + 메트릭 (PR2, 자율 실행)

### Task 6: Redisson 의존성 추가 (3 모듈)

**Files:**
- Edit: `api/build.gradle.kts`, `batch/build.gradle.kts`, `consumer/build.gradle.kts`

- [ ] Step 1: 각 모듈 `dependencies` 블록에:
  ```kotlin
  implementation("org.redisson:redisson-spring-boot-starter:3.40.2")
  ```
- [ ] Step 2: 빌드 검증 — 4 모듈 BUILD SUCCESS, 충돌 0.

### Task 7: `KisAuthRedissonConfig` 공통 Bean 등록 (모듈별 신규)

**Files:**
- Create: `api/src/main/kotlin/com/mj/api/infrastructure/kis/auth/KisAuthRedissonConfig.kt`
- Create: `batch/src/main/kotlin/com/mj/batch/infrastructure/kis/auth/KisAuthRedissonConfig.kt`
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/auth/KisAuthRedissonConfig.kt`

- [ ] Step 1: `@Configuration @ConditionalOnProperty(magicjar.kis.redis-token.enabled, matchIfMissing=false)` — **디폴트 OFF**, PR5에서 활성화.
- [ ] Step 2: `RedissonClient` Bean — `@ConditionalOnMissingBean(RedissonClient::class)` (이미 등록된 RedissonClient 있으면 재사용 — ADR 0050 의 ApiRedisUserCandleCacheAdapter가 RedissonClient를 사용 중인 경우 충돌 회피).
- [ ] Step 3: 단위 테스트 — `enabled=false` 시 Bean 미등록.

### Task 8: `KisAuthMetrics` Micrometer 메트릭 등록 (모듈별 신규)

**Files:**
- Create: 각 모듈 `infrastructure/kis/auth/KisAuthMetrics.kt`

- [ ] Step 1: spec §3 D12 메트릭 5종 — Counter/Timer 등록.
- [ ] Step 2: `@Profile`/`@ConditionalOnProperty` 동일 게이팅.

---

## Phase 3 — api 6 어댑터 통합 (PR3, **사용자 컨펌 후**)

### Task 9: `ApiRedisKisTokenStoreAdapter` 신설 — 시스템 키 path

(spec §6-2 모듈별 어댑터 표 + §7-2 RLock 시퀀스 인용)

### Task 10~14: api 5 어댑터 cachedToken 제거 + port 주입

- KisInstrumentInfoAdapter
- KisInvestorTrendAdapter
- KisMarketIndexAdapter (변종 폐기, retry once 표준)
- KisOrderbookAdapter
- KisRecentTradesAdapter

각 어댑터:
- `private var cachedToken` 필드 제거
- `private fun issueToken()` 제거
- `KisTokenStorePort` 생성자 주입
- `currentTokenOrIssue(KisAuthScope.System("prod"))` 호출
- 401 → `tokenStore.invalidate(scope) + retry once` 표준 패턴

### Task 15: api 단위 테스트 5종 보강

WireMock 401 응답 → invalidate + retry 회귀.

---

## Phase 4 — batch 3 어댑터 통합 (PR4, **사용자 컨펌 후**)

### Task 16: `BatchRedisKisTokenStoreAdapter` 신설

### Task 17: `RealKisRestAdapter` 통합

WireMock IT (R3-4 시나리오 7·8) 회귀 검증.

### Task 18: `KisCorpActionTokenHolder` 폐기 + `RealKisCorpActionAdapter` 직접 주입

### Task 19: `MinuteCandleBackfillJob` 통합

---

## Phase 5 — consumer + 설정 일원화 (PR5, **사용자 컨펌 후**)

### Task 20: `ConsumerRedisKisTokenStoreAdapter` 신설 (System + User scope)

### Task 21: `KisHttpSession` → port wrapper 흡수

`acquireToken(appKey, appSecret)` → `acquireToken(scope: KisAuthScope.User)`. ConcurrentHashMap 캐시 폐기.

### Task 22: `ConsumerRedisKisApprovalStoreAdapter` 신설

### Task 23: `KisWsApprovalProvider` 통합 — port 위임

### Task 24: 시세 WS approval 통합 (별도 holder 없는 path)

### Task 25: `KisCredentialProvider.forSystem(mode)` 추가

track1 `DbKisCredentialProvider` 호환 — default 메서드 또는 별도 인터페이스 분리.

### Task 26: 설정 prefix 일원화 — `magicjar.kis.*`

4개 application.yml 정합. `spring.kis.*` / `kis.*` deprecate.

### Task 27: docker-compose env 매핑 + .env.example

신규 키 8종 (spec §11-2).

### Task 28: multi-JVM IT — testcontainers + WireMock 100 동시 호출

KIS mock 호출 1회 검증.

### Task 29: 운영 가이드 갱신

`docs/architecture/external-api-policy.md` §2-2 / §2-3 / §5-2 / §5-3 / §6.

### Task 30: `magicjar.kis.redis-token.enabled=true` 활성화

PR5 머지 후 사용자 docker rebuild로 전환.

---

## 7. 자율 실행 결과 보고 의무

본 사이클 자율 실행 범위 (PR1+PR2) 종료 시 메인 세션에 보고:
- PR1+PR2 머지 commit hash
- 회귀 검증 결과 (4 모듈 PASS)
- PR3~PR5 진입을 위한 사용자 컨펌 필요 항목:
  - [ ] D7 Redis 다운 fallback 정책 — multi-JVM race 위험 수용 확인
  - [ ] D11 설정 prefix 일원화 — 사용자 .env 정정 부담 확인 (`KIS_APP_KEY` → `KIS_PROD_APP_KEY` 등)
  - [ ] PR3 boundary-qa 트리거 (#5 회귀 영역) 호출 여부 확인
  - [ ] PR5 사용자 docker rebuild + .env 정정 시점 합의

---

## 8. 후속 사이클 (out-of-scope)

spec §15 인용. 본 사이클 PR1+PR2 종료 후:
- PR3 (api 어댑터 통합) — 별도 사이클
- PR4 (batch 통합) — 별도 사이클
- PR5 (consumer + 설정 일원화) — 사용자 액션 동반 사이클
- redis-outage-runbook.md 작성 — 별도 운영 트랙
- 1번 트랙 dogfooding 재개 — PR5 머지 후

---

## 9. ADR 발행 결정

본 사이클 PR1+PR2 종료 시 **ADR 미발행** (PR3~PR5 진입 후 일괄 발행). 사유: ADR 0050 R3-5c-3 변경 이력 추가만으로 충분. 본격 ADR(R3-5c-3-a/b/c)은 PR5 머지 시 일괄.
