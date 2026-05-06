# PR-6 sub-agent 분배 명세

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-6-onboard-design.md` v1 + ADR 0046
- 본 문서: orchestrator가 backend-core·frontend 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-6-conflict-matrix.md` 참조.

---

## 1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-6a** | backend-core | M~L | `agent/backend-core/pr-6-onboard-backend` | **병렬 1** |
| **PR-6b** | frontend | M | `agent/frontend/pr-6-onboard-frontend` | **병렬 1** |

**디스패치 모드**: **병렬** (충돌 행렬 정합 — `_workspace/specs/pr-6-conflict-matrix.md` 참조).

**인터페이스 동결 시점**: 본 spec(`pr-6-onboard-design.md` + 본 dispatch + 충돌 행렬) 머지 즉시 — backend·frontend 양쪽이 §3·§4 (REST 시그니처)·§5 (Wizard step 계약)·§6 (AES 명세)를 바라보고 동시 시작 가능.

---

## 2. backend-core 위임 (PR-6a)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet) |
| 브랜치 | `agent/backend-core/pr-6-onboard-backend` |
| base | `main` (PR-5a·PR-5b 머지된 상태) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-ONBOARD backend — V2 마이그레이션 + OnboardingController 5 endpoint + AesGcmCipher + KisAuthValidator + OnboardingGuardFilter` |
| Task 파일 deliverables | `V2__onboarding_refinements.sql, OnboardingController.kt, OnboardingService.kt, AesGcmCipher.kt, KisAuthValidator.kt, OnboardingGuardFilter.kt, UserOnboardedEvent.kt(이미 PR-5a에 도메인), JPA Entity·Repository, smoke test 2건` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | M~L |

### 2-2. 작업 범위 (Done 정의)

다음 모두 완료해야 PR-6a Done:

#### A. Flyway V2 마이그레이션
- [ ] `api/src/main/resources/db/migration/V2__onboarding_refinements.sql` — spec §3-3 LOCK 정합
  - user_account.masked_account → GENERATED ALWAYS AS (DROP + ADD)
  - user_account.last_login_at 인덱스 추가
  - user_kis_credential.created_at·updated_at 컬럼 추가
- [ ] `docker compose up -d api-app` 후 V2 적용 검증 (`psql -c "\d user_account"` GENERATED 표시)

#### B. AesGcmCipher 서비스
- [ ] `api/src/main/kotlin/kr/spdaewoon/magicjar/api/secret/AesGcmCipher.kt` — spec §6 정합
  - SecretCipher port 구현 (PR-5a domain에 이미 존재)
  - AES/GCM/NoPadding + 12-byte IV + 16-byte tag
  - 부팅 시 `MAGICJAR_SECRET_MASTER_KEY` 검증 → 부재 시 ApplicationContextException
  - encrypt: IV(12) + ciphertext + tag → BYTEA
  - decrypt: 첫 12 bytes IV, 나머지 ciphertext+tag
  - SecureRandom default + thread-safe Cipher 인스턴스 (per-call new instance — 안전)
- [ ] `application.yml` 추가: `magicjar.secret.master-key: ${MAGICJAR_SECRET_MASTER_KEY:}`

#### C. KisAuthValidator 어댑터
- [ ] `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/kis/KisAuthValidator.kt` — spec §4-6 정합
  - WebClient (Spring `WebClient.builder()` Bean — config/WebClientConfig.kt 1개)
  - validate(accountType, appKey, secretKey): ValidationResult sealed class
  - 실전 host: `https://openapi.koreainvestment.com:9443`
  - 모의 host: `https://openapivts.koreainvestment.com:29443`
  - POST /oauth2/tokenP + Content-Type: application/json; charset=utf-8
  - body: `{"grant_type":"client_credentials","appkey":..., "appsecret":...}`
  - timeout 5s · retry 0
  - 200 + access_token 수신 시 Success(tokenIssuedAt=now)
  - 401·400 → Failed(httpStatus)
  - 5xx·timeout → Unavailable
- [ ] KIS 응답 평문을 에러 메시지에 reflect 금지 (ADR 0046 §2-5)

#### D. OnboardingService + OnboardingController
- [ ] `api/.../persistence/UserAccountJpaEntity.kt` + `UserKisCredentialJpaEntity.kt` + `SystemConfigJpaEntity.kt` (JPA `@Entity` + `@Table`)
- [ ] `api/.../persistence/UserAccountJpaRepository.kt` + `UserKisCredentialJpaRepository.kt` + `SystemConfigJpaRepository.kt` (Spring Data JPA `interface ... : JpaRepository<...>`)
  - `existsAny()` 또는 `existsBy*` 메서드 — OnboardingGuardFilter용
- [ ] `api/.../persistence/UserAccountRepositoryAdapter.kt` — domain port 구현 (JPA → 도메인 변환)
- [ ] `api/.../service/OnboardingService.kt` — 비즈니스 로직
  - validateAndPersistKis(accountType, request): KisValidateResponse OR throw
  - addMockAccount(...)
  - updateSystemConfig(...)
  - complete(userId): UserOnboardedEvent publish
- [ ] `api/.../controller/OnboardingController.kt` — 5 endpoint (spec §4-1 LOCK 정합)
  - GET /api/onboarding/status
  - POST /api/onboarding/kis/validate
  - POST /api/onboarding/kis/mock
  - POST /api/onboarding/system-config
  - POST /api/onboarding/complete
  - DTO 정의 (KisValidateRequest·KisValidateResponse 등) — request/response 모두 `data class` + `@Schema`(Swagger) + `@field:Pattern` 등 validation
- [ ] Swagger 4단 어노테이션 (사용자 메모리 정합) — 컨트롤러/메서드/파라미터/DTO

#### E. OnboardingGuardFilter
- [ ] `api/.../config/OnboardingGuardFilter.kt` — spec §4-7-1 LOCK 정합
  - OncePerRequestFilter
  - `/api/onboarding/*` · `/actuator/*` · `!path.startsWith("/api/")` → pass-through
  - 그 외 `/api/*` + `userAccountRepository.existsAny()=false` → 401 + `ONBOARDING_REQUIRED`

#### F. UserOnboardedEvent + audit_trail
- [ ] domain 모듈에 이미 PR-5a로 추가되었는지 확인 — 없으면 추가 (`domain/.../user/UserOnboardedEvent.kt`)
- [ ] OnboardingService.complete() 시점에 ApplicationEventPublisher.publishEvent
- [ ] audit_trail INSERT — 4 endpoint 모두 (KIS 등록·모의 등록·system_config 갱신·complete)

#### G. 로그 마스킹
- [ ] `api/src/main/kotlin/kr/spdaewoon/magicjar/api/config/MaskingPatternLayout.kt`
- [ ] `api/src/main/resources/logback-spring.xml` — MaskingPatternLayout 적용
- [ ] 정규식: `(app_key|app_secret|secret_key|webhook_url|access_token)["':\s=]+[\w\-./]{8,}` → `[REDACTED]`
- [ ] KIS 계좌번호 8자리 → `1234****` 패턴 (선택 — log 빈도 낮으면 spec §6-8 패턴만)

#### H. 메모리 zero-fill
- [ ] OnboardingService.validateAndPersistKis() 종료 시점 (성공·실패 양쪽 finally) — `appKey.toByteArray().fill(0)` + `secretKey.toByteArray().fill(0)`
- [ ] String → ByteArray 변환은 try-finally 의무

#### I. .env.example 갱신
- [ ] `.env.example` — `MAGICJAR_SECRET_MASTER_KEY=` 추가 (생성 가이드 주석)
- [ ] DEPRECATED 주석 — `# KIS_APP_KEY=`·`# KIS_APP_SECRET=`·`# DART_API_KEY=`

#### J. Slack webhook test 발송 (옵션)
- [ ] OnboardingService.updateSystemConfig() — Slack URL 등록 시 POST `<webhook_url>` body=`{"text":"magicJar 셋업 — Slack webhook 검증 OK"}` (timeout 3s, retry 0)
- [ ] 200 OK → 등록 / 실패 → 503 응답

#### K. 검증 게이트 통과
spec §10-1 모든 항목 GREEN.

#### L. smoke test 2건 (룰 4 정합 — R1·R2 단계 단순 단위 테스트만)
- [ ] `api/src/test/kotlin/.../OnboardingControllerTest.kt` — 빈 DB → status `hasUserAccount=false` 검증 (Spring Boot Test + Testcontainers OR @MockBean)
- [ ] `api/src/test/kotlin/.../AesGcmCipherTest.kt` — round-trip encrypt(plain) → decrypt(cipher) === plain

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(db): V2 onboarding refinements — masked_account GENERATED + 인덱스 + created_at/updated_at` | `V2__onboarding_refinements.sql` |
| 2 | `feat(api): AesGcmCipher 서비스 + 부팅 시 마스터 키 검증` | `AesGcmCipher.kt` + `application.yml` 보완 |
| 3 | `feat(api): KisAuthValidator + WebClient config (실전·모의 host)` | `KisAuthValidator.kt` + `WebClientConfig.kt` + `ValidationResult.kt` |
| 4 | `feat(api): OnboardingController 5 endpoint + Service + DTO + JPA persistence` | `OnboardingController.kt` + `OnboardingService.kt` + `persistence/*` + DTO |
| 5 | `feat(api): OnboardingGuardFilter — 셋업 미완 시 /api/* 401 차단` | `OnboardingGuardFilter.kt` |
| 6 | `feat(api): logging 마스킹 + 메모리 zero-fill + UserOnboardedEvent publish` | `MaskingPatternLayout.kt` + `logback-spring.xml` + service finally 블록 |
| 7 | `chore(env): .env.example MAGICJAR_SECRET_MASTER_KEY 추가 + KIS·DART 평문 deprecated` | `.env.example` |
| 8 | `test(api): OnboardingController + AesGcmCipher smoke 2건` | test 파일 |

(commit 1~8 묶기 OK — 그러나 git rebase 충돌 시 분할 권장.)

### 2-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (api 모듈) — Onboarding REST + AES + KIS 검증 + 마법사 backend (T-ONBOARD)

## Summary
- PR-6a: T-ONBOARD backend — V2 마이그레이션(masked_account GENERATED·인덱스·created_at/updated_at) + AesGcmCipher + KisAuthValidator + OnboardingController 5 endpoint + OnboardingGuardFilter + 로그 마스킹.
- ADR 0046 §2-1·§2-3·§2-4·§2-5·§2-8 모두 구현.
- 정합: `_workspace/specs/pr-6-onboard-design.md` + ADR 0046.

## 신규 ADR
없음 — ADR 0046 정합 구현.

## Scope
- api 모듈: 5 controller endpoint + service + JPA persistence + AES + KIS validator + Filter + logging
- domain 모듈: 변경 0 (PR-5a에 이미 port·event 정의)
- batch·consumer·frontend: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄 (T-W1 진입 시). 본 PR에 smoke 2건만 (단순 단위 — controller status + AES round-trip).

## 사용자 검증 방법
1. `MAGICJAR_SECRET_MASTER_KEY=$(openssl rand -base64 32)` 설정 후 `scripts/build-and-deploy.sh`
2. `curl http://localhost:8080/api/onboarding/status` → `{"hasUserAccount":false,...}`
3. `curl -X POST http://localhost:8080/api/onboarding/kis/validate -H "Content-Type: application/json" -d '{"accountNumber":"<본인 8자리>","displayName":"테스트","appKey":"<KIS APP_KEY>","secretKey":"<KIS SECRET_KEY>"}'` → 200 + `{"userId":"<8자리>","maskedAccount":"<4자리>****","status":"VALIDATED"}`
4. `psql -h localhost -U magicjar -c "SELECT user_id, masked_account, octet_length(app_key_enc), octet_length(secret_key_enc) FROM user_account JOIN user_kis_credential USING(user_id)"` → 1행 + 암호문 길이 > 16
5. `curl http://localhost:8080/api/watchlist` → 401 (기존 endpoint 미존재 — Filter는 PR-6a에 있고 watchlist는 T-W1에서). 검증용으로 더미 endpoint 1개 추가 또는 `/api/test` 임시 endpoint로 검증.
6. `MAGICJAR_SECRET_MASTER_KEY=` (빈 값)으로 재시작 → 컨테이너 EXITED 1 + log "부재"

## 정합 ADR
- 0046 (전부) — Onboarding & Credential
- 0006 — PostgreSQL 단일 스키마
- 0010 — Multi-tenant (user_account)
- 0044 — Notification (Slack webhook 저장 위치)
- 0045 — Graceful Shutdown (정합)
```

### 2-5. backend-core 의존성·정합 의무

- **ADR 0046 (Onboarding) 의무 항목**: spec §2 체크리스트 12개 항목 모두 구현
- **ADR 0006 (DB)**: V2 마이그레이션은 api 모듈만 적용 (batch·consumer는 `spring.flyway.enabled=false` 그대로)
- **ADR 0010 (Multi-tenant)**: user_id = 8자리 KIS 실전 계좌번호 자연 키 정합
- **ADR 0038 (KIS OAuth)**: 본 PR-6a는 검증용 KisAuthValidator만 — 운영용 KisAuthAdapter (Redis 분산락)는 PR-7+ T-W2
- **룰 5 (AI 호출 자제)**: spring-ai·anthropic·openai import 0건
- **룰 4 (테스트 마지막 일괄)**: R3 일괄은 T-W1 R3에서 — 본 PR-6a는 smoke 2건만

**KIS-first 의무**: KIS endpoint는 ADR 0046 §2-3에 출처 명시 (`kis_devlp.yaml` prod/vps + `docs/operations/kis-api-reference.md` §1). 추가 endpoint는 본 PR에 없음 — kis-code-assistant MCP 호출 불필요.

---

## 3. frontend 위임 (PR-6b)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | frontend (sonnet) |
| 브랜치 | `agent/frontend/pr-6-onboard-frontend` |
| base | `main` (PR-5a·PR-5b 머지된 상태) |
| 작업 author | `bot+frontend@magicjar.local` |
| 작업 author name | `magicjar-frontend` |
| Task 파일 goal | `T-ONBOARD frontend — SetupWizard 7-step 컴포넌트 + 라우팅 가드 + axios interceptor + Zustand store + smoke test 1건` |
| Task 파일 deliverables | `pages/SetupWizard.tsx, pages/setup/steps/*.tsx (5개 step), components/setup/*, stores/onboarding.ts, api/onboarding.ts, lib/axios.ts (interceptor), App.tsx 라우팅 가드, smoke test 1건` |
| 머지 정책 | rebase |
| 분량 | M |

### 3-2. 작업 범위 (Done 정의)

#### A. SetupWizard 메인 컴포넌트
- [ ] `frontend/src/pages/SetupWizard.tsx` — PR-5b의 `SetupWizardPlaceholder.tsx`를 교체 (또는 신규 + placeholder import 제거)
- [ ] Zustand store에서 currentStep 읽어서 conditional render
- [ ] Tailwind progress bar (currentStep / 6 * 100%)
- [ ] 헤더: "magicJar 셋업 마법사" + 현재 step 번호

#### B. 5개 Step 컴포넌트
- [ ] `frontend/src/pages/setup/steps/KisRealStep.tsx` (Step 2 입력)
  - 4 input (accountNumber·displayName·appKey·secretKey)
  - input type="password" + autocomplete="off"
  - clientside validation (accountNumber `^\d{8}$`)
  - "검증 시작" 버튼
- [ ] `frontend/src/pages/setup/steps/KisValidatingStep.tsx` (Step 3 진행)
  - "검증 중..." 스피너
  - axios `POST /api/onboarding/kis/validate` 호출 (TanStack Query mutation)
  - 성공 시 zustand setRegistered(userId) + setStep(4)
  - 실패 시 setStep(2) + 에러 메시지
- [ ] `frontend/src/pages/setup/steps/KisMockStep.tsx` (Step 4 옵션)
  - 라디오 (Yes/No)
  - Yes 시 KisRealStep과 유사한 4 input
  - "건너뛰기" 버튼
- [ ] `frontend/src/pages/setup/steps/SystemConfigStep.tsx` (Step 5 옵션)
  - 2 input (dartApiKey·slackWebhookUrl)
  - "건너뛰기" 버튼
- [ ] `frontend/src/pages/setup/steps/CompleteStep.tsx` (Step 6 완료)
  - 등록 요약 (maskedAccount·hasMockAccount·hasDartKey·hasSlackWebhook)
  - "시작하기" 버튼 → POST /api/onboarding/complete → `<Navigate to="/" />`

#### C. Zustand store
- [ ] `frontend/src/stores/onboarding.ts` — spec §5-9 정합
  - currentStep · registeredUserId · hasMockRegistered · hasSystemConfig
  - setStep · setRegistered · reset

#### D. axios + TanStack Query 통합
- [ ] `frontend/src/lib/axios.ts` (또는 기존 axios 인스턴스 — PR-5b에 없으면 신규)
  - baseURL `/api` (Vite dev proxy 정합 — PR-5b vite.config.ts에 이미 있음)
  - response interceptor — 401 + `code:'ONBOARDING_REQUIRED'` 수신 시 `<Navigate to="/setup" />` (히스토리 push)
- [ ] `frontend/src/api/onboarding.ts` — TanStack Query hooks
  - `useOnboardingStatus()` (queryKey ['onboarding','status'])
  - `useValidateKisReal()` (mutation)
  - `useAddMockAccount()` (mutation)
  - `useUpdateSystemConfig()` (mutation)
  - `useCompleteOnboarding()` (mutation)

#### E. 라우팅 가드 (App.tsx 확장)
- [ ] `frontend/src/App.tsx` — onboarding status 조회 후 분기
  - `useOnboardingStatus()` 호출
  - `isLoading` → splash
  - `data.hasUserAccount=false` + path !== '/setup' → `<Navigate to="/setup" replace />`
  - `data.hasUserAccount=true` + path === '/setup' → `<Navigate to="/" replace />` (이미 완료된 사용자가 /setup 진입 시 차단)
- [ ] PR-5b의 라우터 구조 그대로 유지 — `/setup` 라우트는 SetupWizard 컴포넌트로 변경

#### F. 보안 — 메모리 zero-fill
- [ ] KisRealStep·KisMockStep·SystemConfigStep 컴포넌트 unmount 시 useEffect cleanup으로 state 비우기
- [ ] 폼 제출 후 즉시 setState('') 호출
- [ ] `useRef`로 input DOM 직접 비우기 (선택)

#### G. 진행률 시각화
- [ ] Tailwind progress bar — `<div className="h-2 bg-blue-500" style={{width: `${(currentStep-1)/6*100}%`}}/>`
- [ ] step 7은 표시 안 함

#### H. 검증 게이트 통과
spec §10-2 모든 항목 GREEN.

#### I. smoke test 1건 (룰 4 정합)
- [ ] `frontend/src/__tests__/onboarding-routing.test.tsx` — `hasUserAccount=false` mock → `/setup` 라우팅 검증 (vitest + react-testing-library + msw)

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(frontend): TanStack Query onboarding hooks + axios 401 interceptor` | `lib/axios.ts` + `api/onboarding.ts` |
| 2 | `feat(frontend): Zustand onboarding store (currentStep·registeredUserId)` | `stores/onboarding.ts` |
| 3 | `feat(frontend): SetupWizard 7-step 컴포넌트 + 5개 step (KisReal·Validating·KisMock·SystemConfig·Complete)` | `pages/SetupWizard.tsx` + `pages/setup/steps/*.tsx` |
| 4 | `feat(frontend): App.tsx 라우팅 가드 + 401 interceptor 통합` | `App.tsx` |
| 5 | `test(frontend): onboarding routing smoke 1건` | `__tests__/onboarding-routing.test.tsx` |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
frontend — Onboarding SetupWizard 7-step UI + 라우팅 가드 + axios 401 interceptor (T-ONBOARD)

## Summary
- PR-6b: T-ONBOARD frontend — SetupWizard 컴포넌트 + 5개 step + Zustand store + axios interceptor + 라우팅 가드.
- ADR 0046 §2-2 (7-step 흐름) + §2-5 (autocomplete=off·메모리 zero-fill) 구현.
- 정합: `_workspace/specs/pr-6-onboard-design.md` §5 (Wizard step 계약 LOCK) + ADR 0046.

## 신규 ADR
없음 — ADR 0046 정합 구현.

## Scope
- frontend 모듈만
- 신규: `pages/SetupWizard.tsx` + `pages/setup/steps/*.tsx` (5) + `stores/onboarding.ts` + `api/onboarding.ts` + `lib/axios.ts`
- 변경: `App.tsx` (라우팅 가드 추가) + `pages/SetupWizardPlaceholder.tsx` 제거(또는 SetupWizard로 교체)
- backend·docker compose: 변경 0

## 테스트 보류 사유
v2 룰북 §1-4 — 코드 우선, R3 일괄. 본 PR에 smoke 1건만 (라우팅 가드 동작 검증).

## 사용자 검증 방법
1. PR-6a backend가 머지된 main 상태 확인
2. `cd frontend && pnpm dev` (또는 `scripts/build-and-deploy.sh`)
3. `curl http://localhost:3000` → SetupWizard HTML (placeholder 아님)
4. 브라우저에서 `localhost:3000` 진입 → 마법사 Step 2 화면 보임 (입력 폼)
5. 본인 KIS 키 입력 → "검증 시작" → 1~3초 후 Step 4(모의계좌)·Step 5(DART)·Step 6(완료) 순차 진행
6. Step 6 "시작하기" → / (Dashboard placeholder) 진입
7. 브라우저 새로고침 → 바로 / 진입 (마법사 skip)
8. localStorage·sessionStorage에 KIS 키 평문 저장 0건 확인 (DevTools)

## 정합 ADR
- 0046 §2-2 (7-step 흐름) · §2-5 (보안 정책)
- 0009 (frontend SPA — React 19 + TS + Vite + LWC v4)
- 0040 (Watchlist 우선 UI 흐름 — Dashboard 진입은 본 PR 후 T-W1에서)
```

### 3-5. frontend 의존성·정합 의무

- **ADR 0046 §2-2**: 7-step 흐름 그대로 구현 (Step 0·1·7은 시스템·라우팅 — 컴포넌트 5개)
- **ADR 0046 §2-5**: HTTPS·CSRF는 dev OK / autocomplete=off·메모리 zero-fill·로그 출력 금지 (frontend는 console.log에 키 출력 금지)
- **ADR 0009**: React 19 + TS + Tailwind + LWC v4 + pnpm — PR-5b 스택 그대로
- **TradingView Advanced 의존 0**: 본 PR-6b는 차트 0 — 무관
- **TanStack Query**: PR-5b에 의존성 추가됨. 본 PR에서 첫 본격 사용
- **Zustand**: 동
- **axios vs fetch**: PR-5b에 axios 미포함 — frontend가 PR-6b에서 axios 추가 OR fetch + react-query 통합. **architect 권장 = axios** (interceptor 단순)
  - 만약 axios 추가 시 commit 1에 `pnpm add axios` 포함 (package.json·lockfile 갱신)

**PR-5b의 SetupWizardPlaceholder.tsx 처리**:
- 옵션 A: 파일 삭제 + App.tsx import 변경 (`SetupWizard`로 직접 import)
- 옵션 B: 파일 내용 SetupWizard로 변경 (rename 안 함)
- **architect 권장 = 옵션 A** (이름이 의미를 잃음 — placeholder가 더 이상 아님)

---

## 4. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 4-1. REST API 시그니처 (spec §4-1 LOCK)

| Endpoint | Request DTO | Response DTO | Status |
|---|---|---|---|
| `GET /api/onboarding/status` | (none) | `OnboardingStatus` { hasUserAccount, hasMockAccount, hasDartKey, hasSlackWebhook } | 200 |
| `POST /api/onboarding/kis/validate` | `KisValidateRequest` { accountNumber, displayName, appKey, secretKey } | `KisValidateResponse` { userId, maskedAccount, displayName, status, validatedAt } | 200 / 400 / 401 / 409 / 503 |
| `POST /api/onboarding/kis/mock` | `KisMockRequest` { userId, mockAccountNumber, appKey, secretKey } | `KisMockResponse` { userId, mockAccountNumber, status } | 200 / 400 / 401 / 404 / 409 / 503 |
| `POST /api/onboarding/system-config` | `SystemConfigRequest` { dartApiKey?, slackWebhookUrl? } | `SystemConfigResponse` { hasDartKey, hasSlackWebhook, updatedAt } | 200 / 400 / 401 / 503 |
| `POST /api/onboarding/complete` | `OnboardingCompleteRequest` { userId } | `OnboardingCompleteResponse` { userId, maskedAccount, redirectTo } | 200 / 400 / 401 / 412 |

### 4-2. ErrorResponse 공통 포맷

```json
{ "code": "ONBOARDING_REQUIRED" | "KIS_AUTH_FAILED" | "ALREADY_REGISTERED" | "KIS_UNAVAILABLE" | "VALIDATION_ERROR" | "SLACK_WEBHOOK_FAILED" | ..., "message": "한국어 메시지" }
```

### 4-3. 예시 페이로드 (양쪽 검증용)

#### KIS 검증 요청
```json
POST /api/onboarding/kis/validate
{
  "accountNumber": "74683223",
  "displayName": "내 실전 계좌",
  "appKey": "PSxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "secretKey": "AbCdEf...(180+ chars)"
}

→ 200
{
  "userId": "74683223",
  "maskedAccount": "7468****",
  "displayName": "내 실전 계좌",
  "status": "VALIDATED",
  "validatedAt": "2026-05-07T10:23:45.123Z"
}

→ 401
{
  "code": "KIS_AUTH_FAILED",
  "message": "KIS 키 검증 실패 — 콘솔에서 키 재발급"
}
```

#### Onboarding status — 빈 DB
```json
GET /api/onboarding/status
→ 200
{ "hasUserAccount": false, "hasMockAccount": false, "hasDartKey": false, "hasSlackWebhook": false }
```

#### Onboarding status — 셋업 완료
```json
GET /api/onboarding/status
→ 200
{ "hasUserAccount": true, "hasMockAccount": false, "hasDartKey": true, "hasSlackWebhook": true }
```

### 4-4. SoT 변경 시 처리

본 spec(`pr-6-onboard-design.md` + 본 dispatch + 충돌 행렬)이 머지된 후 양쪽 sub-agent 진행 중에 시그니처 변경이 필요하면:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-6 작업 중엔 spec 변경 0건 의무 (사용자 본인 결정 외).

---

## 5. 운영·실패 대응

### 5-1. backend-core가 PR-6a 분량 초과 인지 시
- AesGcmCipher + V2 마이그레이션을 PR-6a-1, OnboardingController + Filter를 PR-6a-2로 분할 가능 (orchestrator 사전 동의 필요)
- frontend는 PR-6a-1 머지 후 진행 가능 (status·validate endpoint만으로 SetupWizard 절반 검증 가능)

### 5-2. frontend가 PR-6b 진행 중 PR-6a 미머지 발견 시
- frontend는 OpenAPI 스펙(spec §4-1 + §4-3 예시 페이로드)만 보고 진행 가능 — backend mock 시뮬레이션
- `pnpm dev` proxy가 backend 부재 시 502 → frontend는 msw(Mock Service Worker)로 mock 서버 띄워서 개발 가능
- 단 통합 검증 (DoD #3·#4)은 PR-6a 머지 후
- 즉, **frontend 단독 PR 머지 OK** — backend 부재 시에도 빌드·smoke 통과

### 5-3. CI fail 시
- backend-core·frontend 본인 영역 fail → 본인 PR에서 수정
- spec 자체 결함 발견 → architect 호출 → SoT 갱신 + sub-agent 작업 재개

### 5-4. ADR 정합 누락 발견 시
- backend-core·frontend는 직접 ADR 변경 금지
- architect 호출 → 별도 PR로 ADR 정정 → main 머지 후 sub-agent 작업 재개

### 5-5. KIS 검증 endpoint URL 정정 필요 시
- `kis_devlp.yaml` 또는 KIS 공식 문서가 변경 — architect 호출 → spec §4-6 갱신

---

## 6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-6 sub-agent 분배 명세 v1 발행. PR-6a (backend-core) + PR-6b (frontend) 병렬 권장. 인터페이스 동결 + REST 시그니처 LOCK + 예시 페이로드 (architect, opus 4.7 1M). |
