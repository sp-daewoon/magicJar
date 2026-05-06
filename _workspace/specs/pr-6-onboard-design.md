# PR-6 T-ONBOARD 설계 가이드 (Onboarding Setup — KIS 키 등록 마법사 + AES + 5 REST endpoint)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-6a (backend-core) · PR-6b (frontend) — 병렬 가능 (§A.5 충돌 행렬 참조)
- 진입 prerequisite: PR-5a (4 JVM 모듈 + V1 baseline) MERGED ✅ · PR-5b (Vite SPA) MERGED ✅
- 정합 SoT:
  - **ADR 0046** Onboarding & Credential Registration (1차 권위)
  - `docs/architecture/master-architecture.md` v2 §3-1 부수
  - `docs/architecture/tracks-roadmap.md` §2 T-ONBOARD 행
  - ADR 0006 PostgreSQL 단일 스키마 · ADR 0010 Multi-tenant · ADR 0038 KIS OAuth · ADR 0044 Notification · ADR 0045 Graceful Shutdown
  - `docs/architecture/development-rules.md` §1 룰 5건
- 작업 원칙 (개발 룰북 정합):
  - 룰 2 — 단일 책임 (PR-6a = backend, PR-6b = frontend)
  - 룰 3 — API 빌딩 한 스텝씩 순차 (R1·R2·R3 라운드 분할은 본 PR-6에선 단일 라운드로 통합 — sub-agent별 단일 PR. 다음 트랙 T-W1부터 본격 R1·R2·R3)
  - 룰 4 — 코드 먼저, 테스트 마지막 일괄 (§8 시나리오 골격 — 실 테스트는 sub-agent 본인 PR에 1~2건만 smoke)
  - 룰 5 — AI 호출 0 (W1 트랙 — LLM provider import 절대 금지)

---

## §1. T-ONBOARD 한 줄 목표 + DoD

### 1-1. 목표

> **첫 부팅 시 사용자가 localhost:3000에 접속하면 셋업 마법사 7-step이 자동으로 떠서 KIS 실전 키를 입력 → 백엔드가 KIS OAuth 토큰으로 검증 → AES-256-GCM 암호화 → DB 영속 → 메인 대시보드로 진입할 수 있게 한다. 이후 모든 KIS 호출(T-W1·T-W2·T-MS1·T-O 등)이 동적 키 로드로 동작 가능해진다.**

### 1-2. DoD (Definition of Done) — PR-6 전체 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | 셋업 마법사 자동 라우팅 | 빈 DB 부팅 → `curl http://localhost:3000` | SetupWizard 화면 (PR-5b placeholder가 실 wizard로 교체됨) |
| 2 | onboarding status — 빈 DB | `curl http://localhost:8080/api/onboarding/status` | `{"hasUserAccount":false,"hasMockAccount":false,"hasDartKey":false,"hasSlackWebhook":false}` |
| 3 | KIS 실전 키 검증 + 영속 | `curl -X POST .../api/onboarding/kis/validate -d '{...}'` (테스트 키 — 사용자 본인) | 200 + `{"userId":"7468****","status":"VALIDATED"}` (masked) |
| 4 | DB 영속 | `psql -c "SELECT user_id FROM user_account; SELECT account_type FROM user_kis_credential"` | 1 row + 'REAL' 1 row |
| 5 | AES 암호화 검증 | `psql -c "SELECT octet_length(app_key_enc) FROM user_kis_credential"` | > 16 (IV+ciphertext+tag — 평문 36자보다 큼) |
| 6 | onboarding status — 등록 후 | `curl .../api/onboarding/status` | `{"hasUserAccount":true,...}` |
| 7 | 마법사 → 대시보드 자동 전이 | 마법사 Step 6 완료 → frontend 라우팅 | `/` (Dashboard placeholder) |
| 8 | 재진입 시 마법사 skip | 재 부팅 후 `curl http://localhost:3000` | `/` 직행 (마법사 X) |
| 9 | 마스터 키 부재 시 부팅 거부 | `MAGICJAR_SECRET_MASTER_KEY` 미설정 → `docker compose up api-app` | 컨테이너 EXITED 1 + log "MAGICJAR_SECRET_MASTER_KEY 부재 — 부팅 거부" |
| 10 | 셋업 미완 인증 차단 | `curl .../api/watchlist` (빈 DB) | 401 또는 503 + "셋업 마법사 완료 필요" |
| 11 | 로그 마스킹 | 마법사 진행 중 application 로그 grep | KIS APP_KEY·SECRET_KEY 평문 0건 (마스킹 패턴 적용) |
| 12 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer}/src/main` | 0건 |

### 1-3. 비즈니스 가치

- T-W1·T-W2·T-MS1·T-O 모든 후속 트랙의 KIS 호출 진입점 — 사용자 본인의 키가 DB에 등록되어 있어야 KIS REST·WS 호출이 가능
- 환경변수 평문 KIS 키 폐기 — git 누출 위험 0
- multi-tenant 자연 확장 (사용자 메모리 — 1차 single-tenant이지만 user_account 테이블이 받침)

---

## §2. ADR 0046 준수 항목 체크리스트

ADR 0046 본문(`_workspace/arch/adr/0046-onboarding-credential-registration.md`)의 모든 결정 사항을 PR-6에서 구현. 누락 점검:

| ADR 0046 §위치 | 결정 사항 | PR-6 구현 위치 | 상태 |
|---|---|---|---|
| §2-1 A | system_config singleton (id=1 CHECK) | V1 baseline 이미 존재 ✅ | 추가 작업 0 |
| §2-1 B | user_account.user_id PK + masked_account GENERATED | V1 baseline에 PK·user_id는 있으나 **masked_account가 NOT NULL DEFAULT ''로 됨 — GENERATED 컬럼 X**. PR-6 V2 마이그레이션에서 GENERATED ALWAYS AS로 전환 | **V2 필요** |
| §2-1 C | user_kis_credential.account_type 복합 PK + FK CASCADE | V1 baseline 이미 존재 ✅ | 추가 작업 0 |
| §2-1 C | account_prdt_cd VARCHAR(2) DEFAULT '01' | V1 baseline은 VARCHAR(4) — ADR 0046은 VARCHAR(2). **V2에서 VARCHAR(2) 정정 OR 그대로 유지(VARCHAR(4)는 호환)** | **V2 검토** (architect 권장: 그대로 유지 — ADR 0046 §2-1 C와 호환 가능, KIS 표준은 2자리지만 정합 비용 < 가치) |
| §2-1 C | last_token_iss·last_validated_at TIMESTAMPTZ | V1 baseline 이미 존재 ✅ | 추가 작업 0 |
| §2-2 | 셋업 마법사 7-step 흐름 (Step 0~7) | frontend SetupWizard 컴포넌트 + backend 5 REST endpoint | **PR-6 핵심** |
| §2-3 | KIS OAuth tokenP endpoint (실전 host:9443 / 모의 host:29443) | api 모듈 KisAuthValidator (KisAuthAdapter는 PR-7+ 운영용 별도) | **PR-6a 핵심** |
| §2-4 | MAGICJAR_SECRET_MASTER_KEY env var 단일 예외 | api·batch·consumer config + AesGcmCipher 서비스 + 부팅 시 검증 | **PR-6a 핵심** |
| §2-5 | HTTPS·CSRF·autocomplete=off·메모리 zero-fill·로그 마스킹 | frontend 입력 화면 + backend MaskingPatternLayout(Logback) + 메모리 zero-fill | **PR-6 양쪽** |
| §2-6 | 셋업 미완 = 활성 user 0명 = worker idle | 본 PR-6에선 worker 자체가 없음 (T-W2부터). 단 OnboardingGuardFilter로 셋업 미완 시 `/api/onboarding/*` 외 401 | **PR-6a** |
| §2-7 | Flyway V25 — 본 V1 통합 정합 (V2~V24 결번 OK) | V1 이미 통합 / V2는 GENERATED 컬럼 정정만 | **V2 필요** |
| §2-8 | REST 5 endpoint 신규 | OnboardingController 5 메서드 | **PR-6a 핵심** |

**누락 점검 결과**: ADR 0046 결정 사항 모두 PR-6에서 커버 가능. 단 V1 baseline의 `masked_account VARCHAR(20) NOT NULL DEFAULT ''` 컬럼이 ADR 0046 §2-1 B의 `GENERATED ALWAYS AS (substring(user_id, 1, 4) || '****') STORED`와 형태 차이 — V2에서 보정.

---

## §3. DB 스키마 LOCK

### 3-1. 현황 — V1 baseline에 이미 존재하는 컬럼

V1 baseline (`api/src/main/resources/db/migration/V1__baseline.sql`)에 ADR 0046 테이블 3종이 이미 모두 존재. PR-6에서 **테이블 신규 생성 0건**. 정정·인덱스 추가만.

| 테이블 | 현재 컬럼 (V1) | 비고 |
|---|---|---|
| `system_config` | id·dart_api_key·notification_slack_webhook·created_at·updated_at + singleton CHECK + 초기 row | ✅ 그대로 사용 |
| `user_account` | user_id·display_name·**masked_account VARCHAR(20) NOT NULL DEFAULT ''**·created_at·last_login_at | ⚠ masked_account를 GENERATED 컬럼으로 전환 필요 |
| `user_kis_credential` | user_id·account_type·account_number·account_prdt_cd VARCHAR(4) DEFAULT '01'·app_key_enc BYTEA·secret_key_enc BYTEA·last_token_iss·last_validated_at + 복합 PK + FK CASCADE | ✅ 그대로 사용 (account_prdt_cd VARCHAR(4)는 ADR 0046 VARCHAR(2)와 호환 — 정합 비용 회피) |

### 3-2. V2 마이그레이션 LOCK — `V2__onboarding_refinements.sql`

위치: `api/src/main/resources/db/migration/V2__onboarding_refinements.sql` (단일 파일)

**총 변경 항목 4개**:

#### 3-2-1. user_account.masked_account를 GENERATED ALWAYS AS로 전환

```sql
-- ADR 0046 §2-1 B 정합 — masked_account 자동 생성 (코드 실수 방지)
ALTER TABLE user_account DROP COLUMN masked_account;
ALTER TABLE user_account ADD COLUMN masked_account VARCHAR(20) GENERATED ALWAYS AS
    (substring(user_id, 1, 4) || '****') STORED;
```

**왜 DROP + ADD**: PostgreSQL 16은 기존 컬럼을 GENERATED로 ALTER 불가. DROP 후 ADD가 표준. V1 baseline에서 row 0건이라 데이터 손실 없음.

#### 3-2-2. user_account.last_login_at 인덱스 추가

```sql
-- ADR 0046 §2-1 B 정합 — 마지막 로그인 시각 조회 인덱스
CREATE INDEX IF NOT EXISTS idx_user_account_last_login ON user_account (last_login_at);
```

V1 baseline에는 인덱스 부재. ADR 0046 §2-1 B 명시 인덱스.

#### 3-2-3. user_kis_credential.user_id 인덱스 추가

```sql
-- ADR 0046 §2-1 C 정합 — user_id 조회 인덱스 (FK 자동 인덱스 외 명시)
CREATE INDEX IF NOT EXISTS idx_user_kis_credential_user ON user_kis_credential (user_id);
```

복합 PK (user_id, account_type)의 leftmost가 user_id이므로 사실상 자동 인덱스 충분 — **architect 결정: SKIP** (PostgreSQL은 복합 PK leftmost를 single-column 인덱스로 자동 활용). ADR 0046 §2-1 C가 명시했으므로 V2에 명시 추가 권장이나, 기능 영향 0. **최종 결정: SKIP** (필요 시 PR-7+에서 트래픽 분석 후 추가).

#### 3-2-4. user_kis_credential.updated_at·created_at 트리거 부재 보완

ADR 0046 §2-1 C는 `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()` 명시했으나 V1 baseline에 컬럼 부재. **추가 필요**:

```sql
-- ADR 0046 §2-1 C 정합 — created_at·updated_at 컬럼 추가
ALTER TABLE user_kis_credential ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE user_kis_credential ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
```

### 3-3. V2 LOCK — 전체 SQL

```sql
-- =============================================================================
-- magicJar V2 — Onboarding refinements (PR-6 T-ONBOARD)
-- ADR 0046 정합 — masked_account GENERATED · 인덱스 · created_at/updated_at
-- =============================================================================

-- 1. user_account.masked_account → GENERATED ALWAYS AS (ADR 0046 §2-1 B)
ALTER TABLE user_account DROP COLUMN masked_account;
ALTER TABLE user_account ADD COLUMN masked_account VARCHAR(20) GENERATED ALWAYS AS
    (substring(user_id, 1, 4) || '****') STORED;

-- 2. user_account.last_login_at 인덱스 (ADR 0046 §2-1 B)
CREATE INDEX IF NOT EXISTS idx_user_account_last_login ON user_account (last_login_at);

-- 3. user_kis_credential.created_at·updated_at 추가 (ADR 0046 §2-1 C)
ALTER TABLE user_kis_credential ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE user_kis_credential ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
```

### 3-4. Flyway 버전 결정 — 단일 V2 vs V2~V4 분리

**architect 결정 = 단일 V2 1개 파일**. 사유:
- 모두 ADR 0046 정합 — 하나의 논리적 변경 단위
- V1 baseline의 단일 트랜잭션 패턴 정합
- backend-core가 V2 1개만 작성 → 작업 단순
- 후속 트랙(T-W1·T-W2·...)은 V3·V4·... 순차 사용

### 3-5. 정합 검증 게이트

- [ ] `psql -c "\d user_account"` → masked_account 컬럼이 `generated always` 표시
- [ ] `psql -c "INSERT INTO user_account (user_id) VALUES ('12345678'); SELECT user_id, masked_account FROM user_account WHERE user_id='12345678'"` → `12345678 | 1234****`
- [ ] `psql -c "\d user_kis_credential"` → created_at·updated_at 컬럼 존재
- [ ] `psql -c "\di idx_user_account_last_login"` → 인덱스 존재

---

## §4. REST API 시그니처 LOCK

### 4-1. 엔드포인트 5개 전수 명세

위치: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/OnboardingController.kt` (신규).
인증 정책 (§4-7 별도 절): 셋업 미완 단계 = `/api/onboarding/*`만 통과, 나머지는 401.

#### 4-1-1. `GET /api/onboarding/status`

| 항목 | 명세 |
|---|---|
| HTTP method | GET |
| Path | `/api/onboarding/status` |
| 인증 | **불요** (셋업 미완·완료 양쪽에서 호출 가능) |
| Request body | 없음 |
| Query params | 없음 |
| Response 200 body | `OnboardingStatus` |
| 에러 | 없음 (DB 다운 시 500) |

```kotlin
// Response DTO
data class OnboardingStatus(
    val hasUserAccount: Boolean,    // user_account 1행 이상 존재
    val hasMockAccount: Boolean,    // user_kis_credential.account_type='MOCK' 1행 이상
    val hasDartKey: Boolean,        // system_config.dart_api_key NOT NULL
    val hasSlackWebhook: Boolean,   // system_config.notification_slack_webhook NOT NULL
)
```

**구현 노트**:
- `EXISTS (SELECT 1 FROM user_account)` 4번 — Spring Data JPA `existsBy*`로 매핑
- 응답 시간 ≤ 50ms 기대

#### 4-1-2. `POST /api/onboarding/kis/validate`

| 항목 | 명세 |
|---|---|
| HTTP method | POST |
| Path | `/api/onboarding/kis/validate` |
| 인증 | **불요** (셋업 진입점 — 첫 등록 시 인증 미보유) |
| Request body | `KisValidateRequest` |
| Response 200 body | `KisValidateResponse` |
| 에러 codes | 400 (validation), 401 (KIS 검증 실패), 409 (이미 등록), 503 (KIS 다운) |

```kotlin
// Request DTO
data class KisValidateRequest(
    val accountNumber: String,    // 8자리 KIS 실전 계좌번호 (예: "74683223") — @Pattern("^\\d{8}$")
    val displayName: String?,     // UI 닉네임 (선택, 최대 100자)
    val appKey: String,           // KIS 콘솔 발급 — 검증 후 메모리 zero-fill
    val secretKey: String,        // 검증 후 메모리 zero-fill
)

// Response DTO (성공)
data class KisValidateResponse(
    val userId: String,           // 8자리 (frontend는 표시 시 마스킹)
    val maskedAccount: String,    // "7468****"
    val displayName: String?,
    val status: String,           // "VALIDATED"
    val validatedAt: Instant,
)
```

**구현 노트**:
- KIS REST `POST https://openapi.koreainvestment.com:9443/oauth2/tokenP` 호출
- Content-Type: `application/json; charset=utf-8`
- Body: `{"grant_type":"client_credentials","appkey":"...","appsecret":"..."}`
- 200 + access_token 수신 시 검증 통과 (별도 ping 불필요)
- 검증 통과 즉시 AesGcmCipher.encrypt()로 BYTEA 변환
- user_account row INSERT (created_at default)
- user_kis_credential (account_type='REAL') INSERT — last_token_iss=now, last_validated_at=now
- 메모리 zero-fill: `appKey.toByteArray().fill(0)` + `secretKey.toByteArray().fill(0)`
- audit_trail INSERT — actor='user', action='ONBOARD_KIS_REAL', entity_type='user_kis_credential', entity_id=userId

**에러 응답**:
```kotlin
// 401 (KIS 검증 실패)
data class ErrorResponse(
    val code: String,       // "KIS_AUTH_FAILED"
    val message: String,    // "KIS 키 검증 실패 — 콘솔에서 키 재발급" (KIS 응답 평문 reflect 금지)
)
// 409 (이미 등록)
// code="ALREADY_REGISTERED", message="이미 등록된 사용자입니다"
// 503 (KIS 다운)
// code="KIS_UNAVAILABLE", message="KIS 서비스 일시 장애 — 잠시 후 재시도"
```

#### 4-1-3. `POST /api/onboarding/kis/mock`

| 항목 | 명세 |
|---|---|
| HTTP method | POST |
| Path | `/api/onboarding/kis/mock` |
| 인증 | 셋업 진행 토큰 OR 첫 등록 후 user 세션 (§4-7 정합) |
| Request body | `KisMockRequest` |
| Response 200 body | `KisMockResponse` |
| 에러 codes | 400, 401, 404 (실전 등록 선행 필요), 409, 503 |

```kotlin
data class KisMockRequest(
    val userId: String,            // 실전 계좌번호 (이미 등록되어 있어야 함)
    val mockAccountNumber: String, // KIS 모의 발급 계좌번호 (8자리)
    val appKey: String,
    val secretKey: String,
)

data class KisMockResponse(
    val userId: String,
    val mockAccountNumber: String,
    val status: String,            // "VALIDATED"
)
```

**구현 노트**:
- KIS REST `POST https://openapivts.koreainvestment.com:29443/oauth2/tokenP` (모의 host)
- 동일 path·body 구조
- user_account가 이미 존재해야 함 (없으면 404)
- user_kis_credential (account_type='MOCK') INSERT
- account_number는 별도 (실전 ≠ 모의)

#### 4-1-4. `POST /api/onboarding/system-config`

| 항목 | 명세 |
|---|---|
| HTTP method | POST |
| Path | `/api/onboarding/system-config` |
| 인증 | user 세션 (실전 등록 후) |
| Request body | `SystemConfigRequest` |
| Response 200 body | `SystemConfigResponse` |
| 에러 codes | 400, 401, 503 (Slack webhook 검증 실패) |

```kotlin
data class SystemConfigRequest(
    val dartApiKey: String?,        // null = 변경 안 함, ""(empty) = 삭제, 비어있지 않음 = 등록·갱신
    val slackWebhookUrl: String?,   // 동일 정책 + URL validation
)

data class SystemConfigResponse(
    val hasDartKey: Boolean,
    val hasSlackWebhook: Boolean,
    val updatedAt: Instant,
)
```

**구현 노트**:
- system_config (id=1) UPDATE
- DART 키는 검증 없이 그대로 AES 암호화 (dart.fss.or.kr 검증 endpoint 부재 — 사용 시점에 검증)
- Slack webhook은 등록 시 test message 1회 발송 (POST `<webhook_url>` body=`{"text":"magicJar 셋업 — Slack webhook 검증 OK"}`) → 200 OK 시 등록, 실패 시 503 반환

#### 4-1-5. `POST /api/onboarding/complete`

| 항목 | 명세 |
|---|---|
| HTTP method | POST |
| Path | `/api/onboarding/complete` |
| 인증 | user 세션 (실전 등록 후) |
| Request body | `OnboardingCompleteRequest` |
| Response 200 body | `OnboardingCompleteResponse` |
| 에러 codes | 400, 401, 412 (실전 등록 미완) |

```kotlin
data class OnboardingCompleteRequest(
    val userId: String,
)

data class OnboardingCompleteResponse(
    val userId: String,
    val maskedAccount: String,
    val redirectTo: String,    // "/" (Dashboard)
)
```

**구현 노트**:
- 실전 등록 미완 시 412 (Precondition Failed)
- 성공 시 ApplicationEvent `UserOnboardedEvent(userId)` publish (Spring `ApplicationEventPublisher`)
- consumer 모듈 worker는 본 이벤트 listen → polling 시작 (PR-7+ T-W2 구현)
- audit_trail INSERT — action='ONBOARDING_COMPLETED'
- user_account.last_login_at = now()

### 4-2. REST 응답 시간 SLO

| Endpoint | SLO | 비고 |
|---|---|---|
| `GET /api/onboarding/status` | p95 ≤ 50ms | 4 EXISTS 쿼리 |
| `POST /api/onboarding/kis/validate` | p95 ≤ 3000ms | KIS REST 호출 포함 (Resilience4j Circuit Breaker timeout 5s) |
| `POST /api/onboarding/kis/mock` | p95 ≤ 3000ms | 동 |
| `POST /api/onboarding/system-config` | p95 ≤ 1500ms | Slack webhook test 시 1500ms 여유 |
| `POST /api/onboarding/complete` | p95 ≤ 100ms | DB 1 row UPDATE + ApplicationEvent |

### 4-3. Swagger 문서화 (사용자 메모리 정합)

`feedback_swagger_pattern.md` 정합 — 컨트롤러/메서드/파라미터/DTO 4단 어노테이션 + 한국어 설명 + example 값.

```kotlin
@Tag(name = "온보딩", description = "셋업 마법사 — KIS 키 등록 7-step 흐름")
@RestController
@RequestMapping("/api/onboarding")
class OnboardingController(...) {

    @Operation(
        summary = "셋업 진행 상태 조회",
        description = "user_account·user_kis_credential·system_config 4 항목의 등록 여부를 반환합니다. 첫 부팅 시 frontend가 본 endpoint로 마법사 분기 결정.",
    )
    @ApiResponse(responseCode = "200", description = "성공")
    @GetMapping("/status")
    fun status(): OnboardingStatus = ...
}
```

DTO에 `@Schema(example=...)` 의무 (KIS 계좌번호 example "74683223" 등).

### 4-4. ApplicationEventPublisher · UserOnboardedEvent

```kotlin
// domain/.../user/UserOnboardedEvent.kt
package kr.spdaewoon.magicjar.domain.user

data class UserOnboardedEvent(
    val userId: String,
    val onboardedAt: Instant,
)
```

**구현 노트**:
- domain 모듈에 정의 (Spring 의존 0 — POJO data class)
- api 모듈 OnboardingService가 `ApplicationEventPublisher.publishEvent(...)` 호출
- consumer 모듈은 PR-7+ T-W2에서 `@EventListener`로 listen — PR-6에선 listener 0건 (event 발행만)

### 4-5. Crypto 서비스 — AesGcmCipher

위치: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/secret/AesGcmCipher.kt` (api 모듈에 우선 — consumer·batch는 PR-7+에서 동일 logic 재사용 또는 공통 모듈로 이동)

**아키텍처 결정**: PR-6 단계는 api 모듈에만 cipher 구현. domain 모듈에는 `SecretCipher` port (이미 PR-5a에 있음). PR-7+에서 batch·consumer가 KIS 키 복호화 필요할 때 공통 모듈 추출 가능.

```kotlin
// domain/.../secret/port/SecretCipher.kt — PR-5a에 이미 존재
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray  // returns IV + ciphertext + tag
    fun decrypt(cipher: ByteArray): ByteArray
}

// api/.../secret/AesGcmCipher.kt — PR-6 신규
@Component
class AesGcmCipher(
    @Value("\${magicjar.secret.master-key:}") private val masterKeyBase64: String,
) : SecretCipher {
    init {
        require(masterKeyBase64.isNotBlank()) {
            "MAGICJAR_SECRET_MASTER_KEY 부재 — 부팅 거부"
        }
        val key = Base64.getDecoder().decode(masterKeyBase64)
        require(key.size == 32) { "마스터 키는 32 bytes (base64 44자) — 현재 ${key.size} bytes" }
    }

    override fun encrypt(plain: ByteArray): ByteArray { ... }
    override fun decrypt(cipher: ByteArray): ByteArray { ... }
}
```

**부팅 시 검증 의무**:
- ApplicationContext 시작 시 `init` 블록에서 `require()` 위반 → 부팅 거부 (Spring `ApplicationContextException`)
- log: `ERROR MAGICJAR_SECRET_MASTER_KEY 부재 — .env에 base64(32 bytes) 등록 후 재시작. 생성: openssl rand -base64 32`

상세 spec은 §6.

### 4-6. KIS 검증 어댑터 — KisAuthValidator

위치: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/kis/KisAuthValidator.kt` (api 모듈)

**왜 api 모듈**:
- 셋업 진입점이라 사용자 1인 호출 = TPS 0.001 미만 → ADR 0037 RateLimiter·ADR 0038 Redis 분산락 의무 X (단발 호출)
- 운영용 KisAuthAdapter (consumer — 토큰 cache + 분산락)는 별도 PR-7+ T-W2에서 구현
- 본 어댑터는 검증 1회만 — 토큰 받은 즉시 폐기 (last_token_iss·last_validated_at만 기록)

```kotlin
@Component
class KisAuthValidator(
    private val webClient: WebClient,
) {
    fun validate(accountType: AccountType, appKey: String, secretKey: String): ValidationResult {
        val baseUrl = when (accountType) {
            AccountType.REAL -> "https://openapi.koreainvestment.com:9443"
            AccountType.MOCK -> "https://openapivts.koreainvestment.com:29443"
        }
        // POST /oauth2/tokenP
        // body: {"grant_type":"client_credentials","appkey":appKey,"appsecret":secretKey}
        // 200 + access_token → ValidationResult.Success
        // 401·기타 → ValidationResult.Failed (KIS 응답 reflect 금지)
        // timeout 5s, retry 0 (검증은 1회만)
    }
}

sealed class ValidationResult {
    data class Success(val tokenIssuedAt: Instant) : ValidationResult()
    data class Failed(val httpStatus: Int) : ValidationResult()
    data object Unavailable : ValidationResult()  // KIS 다운 (5xx · timeout)
}
```

**KIS 정합 (kis-first 의무 — ADR 0046 §2-3 출처 인용)**:
- host: 위 §4-6 baseUrl
- path: `/oauth2/tokenP`
- method: POST
- Content-Type: `application/json; charset=utf-8`
- 출처: `_workspace/arch/adr/0046-onboarding-credential-registration.md` §2-3 + `docs/operations/kis-api-reference.md` §1 + `kis_devlp.yaml` prod/vps

### 4-7. 인증 정책 — 셋업 미완 vs 완료

**핵심 결정**: 첫 부팅 단계 = 인증 미보유 → `/api/onboarding/*`만 통과 + 나머지 401.

#### 4-7-1. OnboardingGuardFilter (Spring Filter — api 모듈 config/)

위치: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/config/OnboardingGuardFilter.kt`

```kotlin
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class OnboardingGuardFilter(
    private val userAccountRepository: UserAccountRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val path = req.requestURI
        // /api/onboarding/* 또는 /actuator/* 또는 / (frontend) → 통과
        if (path.startsWith("/api/onboarding/") || path.startsWith("/actuator/") || !path.startsWith("/api/")) {
            chain.doFilter(req, res); return
        }
        // 그 외 /api/* → 셋업 완료 여부 확인
        if (userAccountRepository.existsAny()) {
            chain.doFilter(req, res)
        } else {
            res.status = 401
            res.contentType = "application/json"
            res.writer.write("""{"code":"ONBOARDING_REQUIRED","message":"셋업 마법사 완료 후 사용 가능합니다"}""")
        }
    }
}
```

**왜 401**:
- HTTP 표준상 "인증 필요"
- frontend가 401 수신 시 `/setup`으로 자동 라우팅 (axios interceptor — frontend가 PR-6b에서 구현)
- 503은 "서비스 다운"이라 의미 다름

**user 세션 정책 (PR-6 단순화)**:
- 1차 single-tenant — 사용자 1명만 — 별도 세션·JWT 미도입
- user_account가 1행 이상 존재 = 셋업 완료 = 모든 API 통과
- multi-user 본격화 시 (post-MVP) Spring Security + OAuth2 추가
- 모의계좌 등록(`/api/onboarding/kis/mock`)·system_config·complete는 user_account 존재 후 호출 → 본 Filter는 `/api/onboarding/*`을 모두 통과시킴 (해당 endpoint 내부에서 user_id로 검증)

#### 4-7-2. CSRF·CORS·HTTPS 정책

| 항목 | dev | prd |
|---|---|---|
| **CSRF** | 비활성 (Spring Security 미도입) — Origin 검증으로 대체 | post-MVP |
| **CORS** | `localhost:3000` allowed origin | localhost·Tailscale IP만 |
| **HTTPS** | dev는 HTTP OK (localhost) | nginx reverse proxy + Let's Encrypt (post-MVP) |

ADR 0046 §2-5는 HTTPS 의무이나 dev localhost는 예외. prd 진입 시 nginx + cert 추가는 별도 ADR.

#### 4-7-3. autocomplete=off + 메모리 zero-fill (frontend)

frontend SetupWizard 입력 폼:
```html
<input type="password" autocomplete="off" name="appKey" />
<input type="password" autocomplete="off" name="secretKey" />
```

frontend는 axios payload 전송 후 `setState('')` 즉시 호출 + ref clear. Kotlin backend는 service 메서드 종료 시 `appKey.toByteArray().fill(0)`.

---

## §5. 마법사 7-step 계약 LOCK

각 step의 frontend 화면 책임 + 백엔드 호출 + 검증 룰 + 다음 step 전이 조건 + 취소 가능 여부.

### 5-1. Step 0 — 부팅 (시스템)

| 항목 | 값 |
|---|---|
| 책임 | docker compose up -d → 인프라 3 + JVM 4 + frontend 컨테이너 healthy |
| frontend 화면 | (없음 — 시스템) |
| 백엔드 호출 | (없음) |
| 검증 룰 | api/actuator/health UP, V2 마이그레이션 적용 |
| 다음 step 전이 | 사용자가 localhost:3000 진입 |
| 취소 | (해당 없음) |

### 5-2. Step 1 — 첫 진입 + 마법사 자동 라우팅

| 항목 | 값 |
|---|---|
| 책임 | frontend가 root path(`/`) 진입 시 onboarding status 조회 → 미완 시 `/setup` 자동 라우팅 |
| frontend 화면 | App.tsx 라우팅 가드 (URL 변경 없이 조건부 라우팅) |
| 백엔드 호출 | `GET /api/onboarding/status` |
| 응답 처리 | `hasUserAccount=false` → `<Navigate to="/setup" />` |
| 검증 룰 | 응답 200 OK + JSON 파싱 |
| 다음 step 전이 | `/setup` 화면 표시 → Step 2 시작 |
| 취소 | (없음 — 자동 분기) |

**구현 노트 (frontend)**:
- React Router의 `BrowserRouter` + `<Routes>` 외부에 라우팅 가드 컴포넌트 배치
- TanStack Query `useQuery({queryKey:['onboarding','status'],queryFn:...})` 1회
- `isLoading` 시 splash, `hasUserAccount=false` 시 `<Navigate>`, `true` 시 자식 라우트

### 5-3. Step 2 — KIS 실전 키 입력 화면

| 항목 | 값 |
|---|---|
| 책임 | 입력 폼 표시 + 클라이언트 측 validation |
| frontend 화면 | `<KisRealStep />` 컴포넌트 (`pages/setup/steps/KisRealStep.tsx`) |
| 입력 필드 | 4개 — accountNumber·displayName·appKey·secretKey |
| validation 룰 | accountNumber: `^\d{8}$`, displayName: 0~100자, appKey: 비어있지 않음, secretKey: 비어있지 않음 |
| 백엔드 호출 | (없음 — Step 3에서) |
| 다음 step 전이 | "검증 시작" 버튼 클릭 시 Step 3 |
| 취소 | "초기화" 버튼 — 폼 비우기 |
| 진행률 표시 | 1/4 (또는 1/5 모의 등록 시) |

**보안 (ADR 0046 §2-5 정합)**:
- input type="password" + autocomplete="off"
- 화면 떠날 때 `useEffect` cleanup으로 state 비우기
- 클립보드 복사 알림 (KIS 키는 클립보드 잔존 위험)

### 5-4. Step 3 — 백엔드 검증 (KIS OAuth tokenP)

| 항목 | 값 |
|---|---|
| 책임 | 백엔드가 KIS REST 검증 → AES 암호화 → DB 영속 |
| frontend 화면 | "검증 중..." 스피너 + 진행 상태 표시 (예상 1~3초) |
| 백엔드 호출 | `POST /api/onboarding/kis/validate` |
| 검증 룰 (server) | request body validation + KIS OAuth 200 + AES encrypt 성공 + DB INSERT 성공 |
| 응답 처리 (frontend) | 200 → maskedAccount 표시 + Step 4로 / 401·400·503 → Step 2로 + 에러 메시지 |
| 다음 step 전이 | 200 OK → Step 4 |
| 취소 | "검증 중" 화면에서 취소 불가 (in-flight 요청) |

### 5-5. Step 4 — 모의계좌 추가 (옵션)

| 항목 | 값 |
|---|---|
| 책임 | 사용자 선택 — "모의계좌도 등록하시겠습니까?" 토글 |
| frontend 화면 | `<KisMockStep />` — 라디오 버튼 (Yes/No) + Yes 시 입력 폼 |
| 입력 필드 (Yes 시) | mockAccountNumber·appKey·secretKey (실전과 동일 구조) |
| 백엔드 호출 | Yes 시 `POST /api/onboarding/kis/mock` (모의 host:29443) |
| validation 룰 | accountNumber: `^\d{8}$`, 키 비어있지 않음 |
| 다음 step 전이 | Yes 검증 통과 OR No → Step 5 |
| 취소 | "건너뛰기" 버튼 — Step 5로 직행 |

### 5-6. Step 5 — DART + Slack (옵션)

| 항목 | 값 |
|---|---|
| 책임 | 시스템 전역 설정 — DART 키 + Slack webhook URL |
| frontend 화면 | `<SystemConfigStep />` — 2개 입력 필드 + "건너뛰기" 버튼 |
| 입력 필드 | dartApiKey (선택) + slackWebhookUrl (선택, https URL pattern) |
| 백엔드 호출 | "다음" 버튼 시 `POST /api/onboarding/system-config` (값 둘 다 비어있어도 호출 OK) |
| 검증 룰 (server) | URL pattern (https://hooks.slack.com/...) + Slack test message 발송 200 |
| 다음 step 전이 | 200 OK → Step 6 |
| 취소 | "건너뛰기" — Step 6로 직행 (system_config UPDATE 호출도 안 함) |

### 5-7. Step 6 — 셋업 완료 화면

| 항목 | 값 |
|---|---|
| 책임 | 등록 요약 + "시작하기" 버튼 |
| frontend 화면 | `<CompleteStep />` — masked account · 등록 항목 체크 표시 |
| 백엔드 호출 | "시작하기" 버튼 시 `POST /api/onboarding/complete` |
| 응답 처리 | 200 → `<Navigate to="/" replace />` (Dashboard 진입) |
| 다음 step 전이 | / (Dashboard placeholder) |
| 취소 | 불가 (셋업 완료 상태) |

### 5-8. Step 7 — 정상 운영 (재진입)

| 항목 | 값 |
|---|---|
| 책임 | 재 부팅 시 마법사 skip |
| frontend 화면 | / (Dashboard) — 마법사 미표시 |
| 백엔드 호출 | App 진입 시 `GET /api/onboarding/status` (Step 1과 동일 — `hasUserAccount=true`) |
| 검증 룰 | Step 1 라우팅 가드가 자동 분기 |
| 다음 step 전이 | (해당 없음) |
| 취소 | (해당 없음) |

### 5-9. Step별 진행 상태 저장 (frontend Zustand)

```typescript
// frontend/src/stores/onboarding.ts (신규)
interface OnboardingState {
  currentStep: 1 | 2 | 3 | 4 | 5 | 6 | 7
  registeredUserId: string | null
  hasMockRegistered: boolean
  hasSystemConfig: boolean
  setStep: (step: number) => void
  setRegistered: (userId: string) => void
  reset: () => void
}
```

**왜 Zustand**: PR-5b에 이미 의존성 추가됨. 단순 클라이언트 상태 — TanStack Query는 server state, Zustand는 wizard step 추적용.

### 5-10. 진행률 시각화

frontend는 Tailwind 기반 progress bar — `(currentStep - 1) / 6 * 100%`. step 7은 표시 안 함 (셋업 완료).

---

## §6. AES-256-GCM Crypto 명세

ADR 0046 §2-4 정합. 사용자 메모리 `project_kis_key_management.md` 정합 — 1차는 DB AES, 본격 운영 시 secret manager.

### 6-1. 마스터 키 source

| 항목 | 값 |
|---|---|
| **환경변수 이름** | `MAGICJAR_SECRET_MASTER_KEY` |
| **Spring property** | `magicjar.secret.master-key` (api·batch·consumer 공통) |
| **포맷** | base64(32 bytes) — 결과 문자열 길이 44자 (44번째는 패딩 `=`) |
| **생성 명령** | `openssl rand -base64 32` |
| **저장 위치** | `.env` 파일 (호스트 + docker compose env_file) — 단일 예외 |
| **저장 금지** | DB · git · log · STOMP payload · docker-compose.yml 평문 · 코드 |

**왜 단일 예외**:
- AES 암호화 자체에 필요한 키 — DB에 저장 시 자기 자신 암호화 못 함 (chicken-and-egg)
- 환경변수가 유일한 부팅 시 read 경로

**`.env.example` 갱신 필요 (ADR 0046 §2-4)**:
```bash
# .env.example (PR-6 갱신)
MAGICJAR_SECRET_MASTER_KEY=  # 생성: openssl rand -base64 32

# DEPRECATED — 셋업 마법사로 등록하세요 (ADR 0046)
# KIS_APP_KEY=
# KIS_APP_SECRET=
# DART_API_KEY=
```

### 6-2. AES-256-GCM 알고리즘

| 항목 | 값 |
|---|---|
| 알고리즘 | AES-256-GCM (Galois Counter Mode) |
| 키 길이 | 256 bits (32 bytes) |
| IV (nonce) 길이 | 12 bytes (96 bits — GCM 표준) |
| Tag 길이 | 16 bytes (128 bits — GCM 표준 maximum) |
| Java provider | `Cipher.getInstance("AES/GCM/NoPadding")` (JDK 21 표준) |
| Library | JDK 표준 — 외부 라이브러리 0 |

### 6-3. 저장 포맷

```
[ IV (12 bytes) ][ ciphertext (가변) ][ tag (16 bytes) ]
```

**구현 노트**:
- IV는 매 encrypt마다 `SecureRandom.nextBytes(12)` (재사용 절대 금지 — GCM nonce 재사용 = 키 노출)
- Java GCM API는 ciphertext + tag를 합친 byte array 반환 — IV만 prepend
- BYTEA 컬럼 저장: `IV(12) + JCipher.doFinal(plain)` byte array concat
- 복호화: 첫 12 bytes = IV, 나머지 = JCipher.doFinal에 그대로 전달

### 6-4. KIS APP_KEY·SECRET_KEY 평문 길이 vs 암호문 길이

| 평문 | 평문 길이 | 암호문 길이 (IV+ct+tag) |
|---|---|---|
| KIS APP_KEY | ~36 chars | 12 + 36 + 16 = 64 bytes |
| KIS SECRET_KEY | ~180 chars | 12 + 180 + 16 = 208 bytes |
| DART API key | ~40 chars | 12 + 40 + 16 = 68 bytes |
| Slack webhook URL | ~80 chars | 12 + 80 + 16 = 108 bytes |

검증 게이트 (DoD #5): `octet_length(app_key_enc) > 16` 의미 — 최소 IV + tag보다 크면 암호화 적용 확인.

### 6-5. SecureRandom 시드 정책

- JDK 21 default `SecureRandom` (`/dev/urandom` on Linux) 그대로 사용
- 별도 시드 주입 X
- 부팅 시 1회 `SecureRandom.getInstanceStrong()` 인스턴스 — Cipher 호출마다 reuse OK (thread-safe)

### 6-6. 키 회전 전략 (post-MVP — 본 PR 비범위)

| 단계 | 동작 |
|---|---|
| 새 마스터 키 생성 | `openssl rand -base64 32` |
| 모든 BYTEA 컬럼 재암호화 | 옛 키로 decrypt → 새 키로 encrypt → UPDATE (한 트랜잭션) |
| 옛 키 폐기 | `.env` 갱신 + 전 컨테이너 재시작 |

**본 PR-6 비범위** — 후속 ADR 0090+ 후보 (ADR 0046 §4 정합).

### 6-7. 부팅 시 마스터 키 검증

**api 모듈 — 의무**:
- `AesGcmCipher.init { require(masterKeyBase64.isNotBlank()) }` — Spring Bean 생성 시 fail-fast
- ApplicationContext 시작 거부 → 컨테이너 EXITED 1
- log: `ERROR MAGICJAR_SECRET_MASTER_KEY 부재 — .env에 등록 후 재시작. 생성: openssl rand -base64 32`

**batch·consumer 모듈 — PR-6 단계는 선택**:
- batch·consumer는 PR-6에선 KIS 키 호출 0 (T-W2부터 호출 시작) → 마스터 키 없어도 부팅 가능
- 단 ADR 0046 §2-4에 의하면 **모든 모듈 부팅 거부**가 일관성 — architect 결정: **api만 강제 + batch·consumer는 PR-7에서 추가**
- 근거: PR-6은 단일 책임 — 셋업만. batch·consumer 부팅 거부는 T-W2 진입 시 자연스럽게 추가

### 6-8. 로깅 마스킹 (ADR 0046 §2-5 정합)

위치: `api/src/main/resources/logback-spring.xml` (신규 또는 갱신)

```xml
<configuration>
    <conversionRule conversionWord="masked" converterClass="kr.spdaewoon.magicjar.api.config.MaskingPatternLayout"/>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %masked%n</pattern>
        </encoder>
    </appender>
</configuration>
```

`MaskingPatternLayout` (Kotlin):
- 정규식 — `(app_key|app_secret|secret_key|webhook_url|access_token)["':\s=]+[\w\-./]{8,}` 매칭 시 `[REDACTED]` 치환
- KIS 계좌번호 8자리 — `\b\d{8}\b` 매칭 시 처음 4자리만 표시 (`74683223` → `7468****`)

---

## §7. 비범위 (post-MVP)

본 PR-6에 명시적으로 포함하지 않는 항목:

| 항목 | 다음 PR/트랙 | 사유 |
|---|---|---|
| Spring Security + JWT/OAuth2 | post-MVP | 1차 single-tenant — user_account 존재 = 인증 통과 |
| 마스터 키 회전 자동화 | 후속 ADR 0090+ | 1차 수동 재암호화 가이드 |
| Vault·AWS Secrets Manager | 후속 ADR 0091+ | 본격 운영 시 |
| consumer·batch 부팅 시 마스터 키 검증 | PR-7 (T-W2 진입) | KIS 키 호출 시작 시 추가 |
| KisAuthAdapter 운영용 (토큰 cache + Redis 분산락) | PR-7 (T-W2) | ADR 0038 — 운영 호출용 |
| KIS REST RateLimiter (ADR 0037 — 토큰 버킷) | PR-7 (T-W2) | 셋업 검증은 단발 호출 — 본 PR에선 불필요 |
| user 권한 분리 (admin vs normal) | post-MVP (ADR 0092+) | 1차 본인 1명 |
| KIS 키 만료·재발급 알림 | post-MVP (ADR 0093+) | KIS 측 정책 통합 |
| WatchlistIngestionWorker UserOnboardedEvent listener | PR-8 (T-W2) | 본 PR-6은 event publish만 |
| Position Bootstrap ApplicationRunner | PR-9 (T-W3 또는 T-O) | ADR 0042 — KIS 잔고 워밍 |
| frontend Dashboard 실 화면 | PR-7 (T-W1) | 본 PR-6은 placeholder 그대로 |
| HTTPS + nginx reverse proxy + cert | post-MVP | dev localhost는 HTTP OK |
| CSRF 토큰 + Spring Security | post-MVP | 1차 Origin 검증 |

---

## §8. 테스트 시나리오 골격 (R3 일괄 — 룰 4 정합)

본 PR-6 sub-agent 본인 PR에는 smoke test 1~2건만 (룰 4 정합 — 단순 단위 테스트 OK). R3 일괄 테스트는 **다음 트랙 T-W1 R3에 통합**.

### 8-1. backend smoke (PR-6a 본인 PR)

| # | 시나리오 | 검증 |
|---|---|---|
| 1 | OnboardingController status — 빈 DB | `GET /api/onboarding/status` → `hasUserAccount=false` |
| 2 | AesGcmCipher round-trip | encrypt(plain) → decrypt(cipher) === plain |

위치: `api/src/test/kotlin/.../OnboardingControllerTest.kt` + `AesGcmCipherTest.kt` (Spring Boot Test + Testcontainers PostgreSQL)

### 8-2. frontend smoke (PR-6b 본인 PR)

| # | 시나리오 | 검증 |
|---|---|---|
| 1 | OnboardingStatus 200 → 라우팅 가드 | `hasUserAccount=false` mock → `/setup` 라우팅 검증 (vitest + react-testing-library) |

위치: `frontend/src/__tests__/onboarding-routing.test.tsx`

### 8-3. R3 일괄 시나리오 골격 (T-W1 R3에 통합)

데이터 흐름·사용자 시퀀스 기반 통합 시나리오 4건 — backend·frontend·DB·docker compose 통합. **T-W1 R3 PR에서 작성**:

| # | 시나리오 | 검증 |
|---|---|---|
| 1 | 빈 DB 부팅 → 마법사 자동 라우팅 | `docker compose up` → `curl localhost:3000` → SetupWizard HTML |
| 2 | KIS 실전 키 등록 → DB 영속 | wizard Step 2~3 시뮬레이션 → `psql ... user_account` 1행 |
| 3 | 셋업 완료 → 대시보드 진입 → 재 부팅 시 마법사 skip | wizard Step 6 → restart api-app → `curl localhost:3000` Dashboard HTML |
| 4 | 마스터 키 부재 시 부팅 거부 | env_file에서 MAGICJAR_SECRET_MASTER_KEY 제거 → `docker compose up api-app` → EXITED 1 |

**룰 4 정합**: 본 PR-6 양쪽 PR은 코드 우선 + smoke 1~2건만. 통합 테스트는 T-W1 R3 진입 시 일괄.

---

## §9. 트레이드오프·결정 요약

| # | 결정 항목 | 채택 | 폐기/후속 | 사유 |
|---|---|---|---|---|
| 1 | Flyway 버전 — V2 1개 vs V2~V4 분리 | **V2 1개** | 분리 | 모두 ADR 0046 정합 — 하나의 논리적 단위. backend-core 작업 단순화 |
| 2 | masked_account — V2 GENERATED 전환 | ✅ | V1 그대로 (NOT NULL DEFAULT '') | ADR 0046 §2-1 B 명시 + 코드 실수 방지 (사용자 user_id 반복 입력 시 자동 마스킹) |
| 3 | account_prdt_cd VARCHAR(2) vs (4) 정합 | **V1 그대로 (4)** | V2에서 VARCHAR(2) ALTER | 정합 비용 < 가치. KIS 표준은 2자리지만 4자리는 호환. ALTER COLUMN TYPE은 DB lock 발생 |
| 4 | 셋업 미완 인증 차단 — Filter vs Spring Security | **Filter (OncePerRequestFilter)** | Spring Security | 1차 single-tenant — Spring Security overkill. PR-6은 단순 — Filter 1개로 충분 |
| 5 | KisAuthValidator — api 모듈 vs domain 통합 어댑터 | **api 모듈** | domain SecretCipher port에 KIS도 통합 | 단발 검증용 — RateLimiter·분산락 불필요. 운영용 KisAuthAdapter는 PR-7+ T-W2에서 별도 |
| 6 | AesGcmCipher 위치 — api vs domain 공통 | **api 모듈** (1차) | domain·shared 모듈 | PR-6 단계는 api만 사용. PR-7+에서 batch·consumer 사용 시 공통 모듈 추출 OK |
| 7 | 마스터 키 부팅 검증 — 모든 모듈 vs api만 | **api만 (1차)** | 모든 모듈 강제 | ADR 0046 §2-4는 모든 모듈 명시이나 — batch·consumer는 PR-6에서 KIS 키 호출 0. T-W2 진입 시 자연스럽게 추가 |
| 8 | UserOnboardedEvent — Spring vs Kafka | **Spring ApplicationEvent** | Kafka topic | 1차 단일 컨테이너 내 — 같은 JVM에서 시그널 충분. Kafka 발행은 multi-instance 본격화 시 (post-MVP) |
| 9 | DART 키 검증 endpoint | (없음 — 사용 시점 검증) | open.dart.fss.or.kr ping | DART는 검증용 endpoint 부재 — 사용 시점에 검증 (T-DART 트랙) |
| 10 | Slack webhook 검증 — test message 1회 | ✅ | 검증 X | 등록 시 사용자 확인용 — Slack 측에서 즉시 메시지 받아서 확인 가능 |

---

## §10. PR-6 검증 게이트 (sub-agent 머지 전)

### 10-1. PR-6a (backend-core) 머지 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | Gradle 빌드 | `./gradlew :api:check` | BUILD SUCCESSFUL |
| 2 | bootJar | `./gradlew :api:bootJar` | jar 생성 |
| 3 | ktlint | `./gradlew ktlintCheck` | violation 0 |
| 4 | V2 마이그레이션 적용 | `docker compose up -d api-app` 후 `psql -c "\d user_account"` | masked_account `generated always` 표시 |
| 5 | masked_account 동작 | `psql -c "INSERT INTO user_account (user_id) VALUES ('12345678'); SELECT masked_account FROM user_account WHERE user_id='12345678'"` | `1234****` |
| 6 | onboarding status 응답 | `curl http://localhost:8080/api/onboarding/status` | 200 + JSON `hasUserAccount` field |
| 7 | OnboardingGuardFilter 동작 | `curl http://localhost:8080/api/watchlist` (빈 DB) | 401 + `ONBOARDING_REQUIRED` |
| 8 | AesGcmCipher round-trip | `:api:test --tests "*AesGcmCipher*"` | PASS |
| 9 | 마스터 키 부재 시 부팅 거부 | `MAGICJAR_SECRET_MASTER_KEY=` (빈 값) → `docker run` | EXITED 1 + log "부재" |
| 10 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {domain,api,batch,consumer}/src/main` | 0건 |
| 11 | 로그 마스킹 | api 부팅 후 `docker logs api-app | grep -i "appkey\|secretkey"` | 평문 0건 (또는 `[REDACTED]`만) |

### 10-2. PR-6b (frontend) 머지 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | TS 타입체크 | `cd frontend && pnpm tsc --noEmit` | 0 error |
| 2 | Vite 빌드 | `pnpm build` | dist/ 생성 |
| 3 | 라우팅 가드 동작 | `pnpm test` (vitest) | smoke test PASS |
| 4 | autocomplete=off 적용 | `grep -r 'autocomplete="off"' frontend/src/pages/setup/` | 입력 컴포넌트마다 1건 이상 |
| 5 | SetupWizard 7-step 라우팅 | dev server 기동 후 manual `/setup` 진입 | 7-step UI 보임 |
| 6 | 401 interceptor → /setup 자동 전이 | mock 401 응답 시 axios interceptor | `/setup` 라우팅 |

### 10-3. PR-6 통합 게이트 (양쪽 머지 후)

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | 전체 통합 기동 | `scripts/build-and-deploy.sh` | 7 컨테이너 healthy |
| 2 | 빈 DB → 마법사 자동 라우팅 | `curl localhost:3000` | `<div id="root">` SetupWizard 분기 |
| 3 | KIS 검증 → DB 영속 (수동 — 사용자 키 필요) | wizard Step 2~3 시뮬레이션 | `user_account` 1행 + `user_kis_credential` REAL 1행 |
| 4 | 셋업 완료 → Dashboard 진입 | wizard Step 6 → 리다이렉트 | `/` Dashboard placeholder |
| 5 | 재 부팅 → 마법사 skip | docker restart → `curl localhost:3000` | `/` 직행 |

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-6 T-ONBOARD 설계 가이드 v1 발행 (architect, opus 4.7 1M). ADR 0046 정합 — V2 마이그레이션 (masked_account GENERATED·인덱스·created_at/updated_at) + REST 5 endpoint + 7-step 마법사 + AES-256-GCM 명세 + OnboardingGuardFilter + UserOnboardedEvent. PR-6a (backend-core) + PR-6b (frontend) 병렬 가능 (분배 명세 + 충돌 행렬은 별도 문서). |
