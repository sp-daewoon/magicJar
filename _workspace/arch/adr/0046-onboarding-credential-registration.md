# ADR 0046 — Onboarding & Credential Registration (테이블 3종 분리 · 마스터 키 단일 예외)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · PR-3b 운영·UX fitness 갭 3번 · ⭐ 사용자 명시 정책)
- 관계: ADR 0010 보강(Multi-tenant — user_id 정의) · ADR 0011 정합(주문 실행기 3-mode) · ADR 0038 정합(KIS OAuth 24h 토큰) · ADR 0044 정합(통지 Slack URL 저장 위치) · 메모리 `feedback_secret_handling.md` 정합 · 메모리 `project_kis_key_management.md` 정합

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0010 Multi-tenant는 `user_id` 컬럼만 명시하고 **user_id의 정체·등록 방법·KIS 키 저장 위치 정의 부재**. 현재 PoC 잔재 운영:

- KIS 키가 `.env`에 평문 (`KIS_APP_KEY`·`KIS_APP_SECRET`) — git 누출 위험
- DART 키도 `.env` 평문
- `user_id`가 KIS 계좌번호인지 별도 식별자인지 모호 — chart user_id="74683223"(메모리 `project_v2_baseline.md`) 정합 미완
- 모의계좌·실전계좌 키 분리 컬럼 없음 — `trading_mode` enum만 존재
- 사용자가 화면에서 등록할 위치·UX 미정의

사용자 명시 결정 (2026-05-07 PR-3b 진입):

> "환경변수 X + 첫 로그인/셋업 시 등록 + user_id=실전계좌번호 + 옵션 모의계좌 + DART는 system_config + 테이블 3종 분리"

**필요한 결정**:
- 테이블 3종 스키마 (system_config / user_account / user_kis_credential)
- 셋업 마법사 7-step 흐름
- KIS REST 토큰 발급으로 키 검증 절차
- 마스터 키 정책 (AES-256-GCM 암호화 키 — 환경변수 단일 예외)
- 보안 정책 (메모리 `feedback_secret_handling.md` 정합)

## 2. 결정

### 2-1. 테이블 3종 스키마 (Flyway V25)

#### A. `system_config` — 시스템 단일 설정 (관리자 = 사용자 본인 동일, 화면만 분리)

```sql
CREATE TABLE system_config (
    id              SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- singleton
    dart_api_key                BYTEA,           -- AES-256-GCM 암호화
    notification_slack_webhook  BYTEA,           -- AES-256-GCM 암호화 (ADR 0044 정합)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 초기 row (모든 키 NULL — 사용자 등록 전)
INSERT INTO system_config (id) VALUES (1);
```

**왜 단일 row**:
- 1차 single-tenant 운영 가정 (사용자 본인 1명 + 옵션 1~2명)
- DART·Slack은 시스템 전역 — user별 분리 불필요
- `id=1 CHECK` constraint로 row 1개 강제 → singleton 패턴
- multi-tenant 본격화 시 별도 `user_system_config` 추가 가능

#### B. `user_account` — 사용자 = 실전 계좌번호 (multi-tenant 기준)

```sql
CREATE TABLE user_account (
    user_id         VARCHAR(20)  PRIMARY KEY,  -- KIS 실전 계좌번호 8자리 (예: 74683223)
    display_name    VARCHAR(100),              -- UI 표시용 (사용자 본인 닉네임)
    masked_account  VARCHAR(20)  GENERATED ALWAYS AS
                                  (substring(user_id, 1, 4) || '****') STORED,  -- "7468****"
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ
);

CREATE INDEX idx_user_account_last_login ON user_account(last_login_at);
```

**왜 user_id = 실전 계좌번호 (사용자 명시)**:
- 자연 키 (KIS 계좌번호는 영구 unique)
- multi-tenant 분리 자명 (다른 user_id = 다른 KIS 계좌)
- 모의계좌는 옵션 — 실전계좌 보유 = 시스템 사용 자격
- `chart user_id="74683223"` (메모리 `project_v2_baseline.md`) 정합

**masked_account GENERATED 컬럼**:
- 로그·STOMP push payload·에러 메시지에 user_id 평문 출력 금지
- 항상 `masked_account` 사용 ("7468****" 형태)
- DB 측에서 자동 생성 → 코드 실수 방지

#### C. `user_kis_credential` — KIS 키 실전·모의 분리 (account_type 복합키)

```sql
CREATE TABLE user_kis_credential (
    user_id         VARCHAR(20)  NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    account_type    VARCHAR(10)  NOT NULL CHECK (account_type IN ('REAL', 'MOCK')),
    account_number  VARCHAR(20)  NOT NULL,  -- 실전 = user_id와 동일, 모의 = 별도
    account_prdt_cd VARCHAR(2)   NOT NULL DEFAULT '01',  -- KIS ACNT_PRDT_CD (01=종합)
    app_key_enc     BYTEA        NOT NULL,  -- AES-256-GCM
    secret_key_enc  BYTEA        NOT NULL,
    last_token_iss  TIMESTAMPTZ,             -- 마지막 OAuth 토큰 발급 시각 (ADR 0038)
    last_validated_at TIMESTAMPTZ,           -- 마지막 검증 통과 시각
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, account_type)
);

CREATE INDEX idx_user_kis_credential_user ON user_kis_credential(user_id);
```

**왜 account_type 복합키**:
- user별 실전 + 모의 동시 보유 가능 (사용자 명시)
- `trading_mode` enum (REAL_KIS / MOCK_KIS)와 1:1 매핑
- multi-tenant 정합 — user_id로 cascading delete

**`account_prdt_cd`** — KIS API request 필수 파라미터 (`ACNT_PRDT_CD`). 종합계좌(01)·국내선물옵션(03)·해외선물옵션(08)·개인연금(22)·퇴직연금(29). 1차 default=01, 향후 셋업 화면에서 사용자 선택 가능.

**`account_number` vs `user_id`**:
- 실전: `account_number = user_id` (동일)
- 모의: `account_number = 별도` (KIS 모의 발급 계좌)

### 2-2. 셋업 마법사 7-step 흐름

```
┌──────────────────────────────────────────────────────────────┐
│ Step 0: 컨테이너 부팅                                          │
│   docker compose up -d → 인프라(postgres·redis·kafka) +       │
│   JVM 4 모듈 모두 idle (사용자 0명 OK)                         │
│   user_account 테이블 비어 있음 → 모든 worker 0 user 처리       │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 1: localhost:3000 첫 진입                                │
│   frontend가 GET /api/onboarding/status 호출                  │
│   응답: {hasUserAccount: false}                               │
│   → "최초 셋업 마법사" 화면 자동 라우트                          │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 2: KIS 실전 키 입력 화면                                  │
│   입력 필드:                                                   │
│     - 실전 계좌번호 8자리 (예: 74683223)                        │
│     - APP_KEY (KIS 콘솔 발급)                                  │
│     - SECRET_KEY                                              │
│     - display_name (UI 표시용 닉네임)                           │
│   HTTPS·CSRF 토큰 보장. autocomplete=off                       │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 3: 백엔드 검증 — POST /api/onboarding/kis/validate       │
│   1. KIS REST POST /oauth2/tokenP 호출                        │
│        host: https://openapi.koreainvestment.com:9443          │
│        body: {grant_type, appkey, appsecret}                  │
│   2. 응답 200 + access_token → 검증 성공                       │
│   3. 메모리에서 즉시 AES-256-GCM 암호화 (master key 사용)        │
│   4. user_account row INSERT                                  │
│   5. user_kis_credential (account_type=REAL) row INSERT       │
│   6. last_token_iss=now, last_validated_at=now                │
│   7. 응답 메모리 zero-fill                                      │
│   8. frontend로 200 OK + masked_account 반환                   │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 4: (옵션) 모의계좌 추가                                    │
│   "모의계좌도 등록하시겠습니까?" 토글                            │
│   YES → KIS 모의 계좌번호 + APP_KEY + SECRET_KEY 입력           │
│   백엔드 검증 — KIS 모의 endpoint:                              │
│     host: https://openapivts.koreainvestment.com:29443         │
│     POST /oauth2/tokenP (동일 path, 다른 host)                 │
│   성공 시 user_kis_credential (account_type=MOCK) row INSERT  │
│   NO → Step 5로 skip                                           │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 5: (옵션) DART 키 + Slack webhook 등록                    │
│   "관리자 메뉴 — 시스템 설정"                                   │
│   - DART API key (open.dart.fss.or.kr 발급)                   │
│   - Slack incoming webhook URL (ADR 0044 정합)                │
│   백엔드: POST /api/onboarding/system-config                   │
│   AES 암호화 → system_config UPDATE                           │
│   Slack URL은 검증용 test message 1회 발송                     │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 6: 셋업 완료                                              │
│   POST /api/onboarding/complete                               │
│   → ApplicationEvent: UserOnboardedEvent(userId)              │
│   → consumer WatchlistIngestionWorker가 user_id polling 시작  │
│   → ADR 0042 Position Bootstrap 자동 trigger                  │
│   → frontend 메인 대시보드로 리다이렉트                          │
└──────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 7: 정상 운영                                              │
│   재진입 시 GET /api/onboarding/status → {hasUserAccount: true}│
│   → 메인 대시보드 직행                                          │
│   user_account.last_login_at 갱신                             │
└──────────────────────────────────────────────────────────────┘
```

### 2-3. KIS 토큰 발급 endpoint 정합 (kis-first 의무 — 검증 출처)

| 항목 | 값 | 출처 |
|---|---|---|
| 실전 host | `https://openapi.koreainvestment.com:9443` | `/Users/ak-song/tools/kis-open-trading-api/kis_devlp.yaml` `prod` |
| 모의 host | `https://openapivts.koreainvestment.com:29443` | 동 `vps` |
| OAuth path | `/oauth2/tokenP` | KIS 표준 + magicJar `docs/operations/kis-api-reference.md` §1 |
| HTTP method | `POST` | KIS 표준 |
| Content-Type | `application/json; charset=utf-8` | KIS 표준 |
| Request body | `{"grant_type": "client_credentials", "appkey": "...", "appsecret": "..."}` | KIS 표준 |
| Response | `{"access_token": "...", "token_type": "Bearer", "expires_in": 86400}` | 24h TTL (ADR 0038 정합) |
| 검증 endpoint (ping) | OAuth 발급 자체로 충분 | 별도 ping 불필요 |

**모의·실전 분기 구현 표준** (consumer KisAuthAdapter):
```kotlin
fun resolveBaseUrl(accountType: AccountType): String = when (accountType) {
    AccountType.REAL -> "https://openapi.koreainvestment.com:9443"
    AccountType.MOCK -> "https://openapivts.koreainvestment.com:29443"
}
```

**계좌번호 분해**:
- KIS API request body: `CANO` (8자리 계좌번호) + `ACNT_PRDT_CD` (2자리 종합계좌 코드)
- 사용자 입력: 8자리 (`74683223`) → `user_id` + `account_number` = 8자리
- `account_prdt_cd` = `01` default (사용자가 추가 입력 가능)

### 2-4. 마스터 키 정책 (사용자 명시 단일 예외)

**원칙 (사용자 명시)**: 사용자 secret(KIS 키·DART 키·Slack URL)은 환경변수 X · DB AES 저장.

**예외**: AES-256-GCM 암호화에 필요한 **마스터 키 1개만 환경변수**.

| 변수 | 형식 | 사유 |
|---|---|---|
| `MAGICJAR_SECRET_MASTER_KEY` | base64(32 bytes) | DB 암호화 시 메모리 hold + 부팅 시 1회 read. DB에 저장 불가 (자기 자신 암호화 못 함) |

**부재 시 동작**:
- 부팅 시 변수 부재 → log ERROR + 부팅 거부 (`Application.exit(1)`)
- magicJar **부팅 직전** 사용자가 1회 .env에 등록
- 키 회전 시 모든 BYTEA 컬럼 재암호화 → 후속 ADR 운영 가이드

**키 생성 가이드** (.env.example):
```bash
# .env.example
MAGICJAR_SECRET_MASTER_KEY=  # 생성: openssl rand -base64 32
```

**KIS·DART·Slack 키는 절대 .env에 두지 말 것** — `.env.example`에 KIS_APP_KEY·KIS_APP_SECRET·DART_API_KEY 변수 자체를 deprecated 처리:

```bash
# .env.example (사용자 명시 — DEPRECATED)
# KIS_APP_KEY=     ← 더 이상 사용하지 않음. 셋업 마법사로 등록 (ADR 0046).
# KIS_APP_SECRET=
# DART_API_KEY=
```

### 2-5. 보안 정책 (메모리 `feedback_secret_handling.md` 정합)

| 항목 | 정책 |
|---|---|
| **입력 화면** | HTTPS 의무 + CSRF 토큰 + autocomplete=off + input type=password |
| **메모리 hold** | 입력 직후 메모리에서 잠시만 hold → AES 암호화 → DB 저장 → 메모리 zero-fill (Kotlin: `password.toCharArray().fill(' ')`) |
| **로그 출력 금지** | `MaskingPatternLayout` (Logback) — `app_key`·`secret_key`·`webhook_url`·`access_token` 패턴 자동 `[REDACTED]` 치환 |
| **STOMP push 금지** | `/topic/*` payload에 키 평문 포함 절대 금지 (frontend는 `masked_*` 컬럼만 receive) |
| **에러 메시지** | KIS API 검증 실패 시 응답에 키 평문 reflect 금지. "검증 실패 — KIS 콘솔에서 키 재발급" 일반 메시지만 |
| **IP 화이트리스트** | KIS 콘솔 측 정책 (사용자 본인 직접 등록). magicJar는 안내 문구만 표시 |
| **채팅·email 평문 처리** | 메모리 `feedback_secret_handling.md` 정합 — 키 평문 발견 시 즉시 재발급 권고 + 응답에 그 키 재출력 금지 |

### 2-6. 셋업 미완 시 동작 (사용자 0명 OK)

- 모든 worker(WatchlistIngestionWorker · BalanceReconcileJob · SignalGenerator)는 **활성 user 0명이면 idle**
- `for (userId in activeUsers) { ... }` 패턴 — 0건 loop = no-op
- log INFO "활성 사용자 0명 — 작업 idle" (1시간 1회)
- 부팅 자체는 정상 완료 → frontend localhost:3000 진입 가능

### 2-7. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| `system_config` (신규) | 위 §2-1 A | **V25** (T-ONBOARD 진입 시) |
| `user_account` (신규) | 위 §2-1 B | **V25** (동) |
| `user_kis_credential` (신규) | 위 §2-1 C | **V25** (동) |

**기존 정합**:
- `watchlist`·`position`·`orders` 등 `user_id` 컬럼은 모두 `user_account(user_id)` FK 의무 (V25에서 ALTER TABLE ... ADD CONSTRAINT)
- `chart user_id="74683223"` (PoC) → V25 마이그레이션 시 `user_account` row 자동 backfill

### 2-8. REST API 신규 (api 모듈)

| Endpoint | 메서드 | 용도 |
|---|---|---|
| `/api/onboarding/status` | GET | `{hasUserAccount: boolean, hasMockAccount: boolean, hasDartKey: boolean, hasSlackWebhook: boolean}` |
| `/api/onboarding/kis/validate` | POST | KIS REST 토큰 발급 검증 → 성공 시 user_account + user_kis_credential 영속 |
| `/api/onboarding/kis/mock` | POST | 모의계좌 추가 등록 (옵션) |
| `/api/onboarding/system-config` | POST | DART·Slack URL 등록 (system_config UPDATE) |
| `/api/onboarding/complete` | POST | 셋업 완료 신호 → UserOnboardedEvent publish |

**인증**: 셋업 미완 시 `/api/onboarding/*`만 접근 가능 (다른 endpoint는 401). 셋업 완료 후 user_id 기반 세션.

## 3. 결과·영향

### 3-1. 트랙 영향

- **신규 트랙 후보 — T-ONBOARD (W1 ★)** — 셋업 마법사 + 테이블 3종 + AES 암호화 + REST endpoint. 사이즈 **L**. T-W1 진입 직전 의무. **T-W1·T-W2·T-S1보다 먼저 진입**.
- **T-W1 (W1)** — `user_account` FK 의무 통합. WatchlistIngestionWorker가 활성 user 0명 idle 처리.
- **T-W2 (W1)** — KIS WS 구독 시 `user_kis_credential` 조회로 키 동적 로드.
- **T-MS1 (W1)** — KIS REST 호출 시 키 동적 로드.
- **모든 트랙** — `user_id` = `user_account.user_id` 8자리 표준 정합.

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | OnboardingController 신규 (5 endpoint) + KisAuthAdapter (검증용) + AesGcmCipher 서비스 |
| `consumer` | KisAuthAdapter (운영용 — 토큰 캐시 + 분산락 ADR 0038 정합) + 모든 KIS 호출에 동적 키 로드 |
| `batch` | 모든 KIS REST 호출에 동적 키 로드 |
| `domain` | `UserAccount`·`UserKisCredential`·`SystemConfig` 도메인 + `CredentialPort` interface |
| `frontend` | OnboardingWizard 컴포넌트 (Step 1~6 화면) + SettingsModal에 system_config 통합 |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| `user_kis_credential.last_validated_at` lag | 24h 초과 시 ADR 0038 토큰 재발급 자동 trigger. 5분 내 3회 실패 시 ADR 0044 통지 |
| 셋업 미완 부팅 | log INFO "활성 사용자 0명 — onboarding 화면 대기" (의도된 정상 상태) |
| AES 복호화 실패 | log ERROR + Slack alert (마스터 키 변경 의심 — 운영 점검) |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **환경변수 평문 (PoC 잔재)** | 폐기 | 사용자 명시 — git 누출 위험. 운영 부담 |
| (b) **외부 secret manager (Vault·AWS SM)** | 후속 | 본격 운영 시 재논의 (메모리 `project_kis_key_management.md`). 1차는 DB AES |
| (c) **DB AES + 셋업 마법사 + 테이블 3종 분리** (채택) | ✅ | 사용자 명시 정합 + 운영 부담 최소 + multi-tenant 자연 확장 |
| (d) user_id = UUID + 별도 매핑 | 폐기 | KIS 계좌번호가 자연 키 — 사용자 명시 |
| (e) KIS·DART·Slack 단일 테이블 통합 | 폐기 | DART·Slack은 시스템 단일 / KIS는 user별 → 분리 필연. 단일 테이블 시 NULL 컬럼 다수 |

## 4. 후속 ADR 후보

- **0090+ (가칭)** — 외부 secret manager 통합 (Vault·AWS Secrets Manager — 본격 운영 시)
- **0091+ (가칭)** — 마스터 키 회전 자동화 (현재는 수동 재암호화)
- **0092+ (가칭)** — 사용자별 권한 분리 (admin = system_config 편집, normal = 본인 KIS만)
- **0093+ (가칭)** — KIS 키 만료·재발급 알림 (KIS 측 만료 정책 통합)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0046 신규 발행 — PR-3b 운영·UX fitness 갭 3번. ⭐ 사용자 명시 정책 — 테이블 3종 분리 + user_id=실전계좌번호 + 옵션 모의계좌 + DART system_config + 환경변수 X + 마스터 키 단일 예외. 셋업 마법사 7-step + KIS OAuth tokenP 검증 + AES-256-GCM 암호화. (architect) |
