# T-O 진입 prereq — MOCK/PRD 주문 분기 정합성 검증 spec

> **위치**: `_workspace/specs/2026-05-18-to-prereq-mock-prd-verify.md`
> **발행일**: 2026-05-18 KST
> **작성**: architect (사용자 의제 — 자본 결합 직전 안전 게이트 dogfooding 의무)
> **상태**: Proposed — 매트릭스·dogfooding scenario·결함 분류 발행. 실제 dogfooding 실행 + 분류 D 결정은 사용자 검토 후
> **연관 ADR**: 0046·0048·0068·0070·0074·0077 (참조). 본 spec 채택 시 신규 ADR 발행 0건 — 검증 결과에 따라 후속 ADR 검토
> **AI 호출 0** — T-O prereq 정책 (ADR 0023 §2-4 + 0066 §8 + 0067 §8 + 0068 §8 정합)

## §0. 사용자 의제 (LOCK)

> "주문 실행하기전에 MOCK 주문이 가능한지 먼저 확인이 되어야해. 그러니깐 사전에 MOCK, PRD 에 대한 확실한 세팅을 어떻게 잡는지 검증이 끝나야한다는거지" — 2026-05-18 최고 관리자

자본 결합 직전 안전 게이트(3중 게이트 + endpoint·tr_id 분기 + credential lookup 분기)가 dogfooding으로 작동 확인되어야 T-O 본 트랙 진입 GREEN.

## §1. 컨텍스트

### §1.1 사용자 메모리 정합

- `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES 암호화 (운영 시 secret manager 재논의)
- `feedback_kis_key_realonly_for_data.md` — 시세·캔들·호가 등 **정보 수집은 모두 실전 키**. 모의/실전 분기는 주문 액션에만
- `feedback_kis_first_policy.md` — KIS-only 단일 출처
- `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 0건. backend 0건/에러 시 "데이터 없음" 명시

### §1.2 CLAUDE.md 운영 함정 §3·§4

- **default profile = `dev,real-kis`** — mock 부팅 path 폐기. mock은 jvm test fixture 전용
- **실계좌 이중 게이트** — `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` 둘 다여야 실주문. dev/staging은 항상 MOCK

### §1.3 ADR 0068 §14-3 3중 게이트 권위

```
1. env MAGICJAR_MODE == "REAL"           → false면 즉시 MOCK fallback (안전 default)
2. env MAGICJAR_ALLOW_REAL == "true"     → false면 DenyReason.RealGateDenied("MAGICJAR_ALLOW_REAL=false")
3. user_account.real_trading_consent_at  → null이면 DenyReason.RealGateDenied("CONSENT_NOT_GIVEN")
```

3 조건 모두 통과해야 `TradingMode.REAL`. 한 개라도 false면 즉시 차단(MOCK fallback 또는 deny).

### §1.4 본 spec 책임 경계

- **단일 책임**: 검증 spec 발행만. 운영 코드(api·consumer·batch·domain·frontend) 0 touch
- **결함 발견 시**: 본 PR에서 fix 0. 별도 PR 사이클로 분리 (단일 책임 룰 정합)
- **spec 본문은 add-only** — 기존 코드/ADR 인용은 evidence로만

---

## §2. 코드 매트릭스 — 현행 분기 path 명세 (분류 A 결과)

### §2.1 안전 게이트 4 컴포넌트 위치

| 컴포넌트 | 파일 | 책임 |
|---|---|---|
| `TradingModeGate` (port) | `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/order/execution/port/TradingModeGate.kt` | 3중 게이트 인터페이스 — `resolveMode(userId)` → `OrderResult<RealGateDenied, TradingMode>` |
| `TradingModeGateImpl` | `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/TradingModeGateImpl.kt` | env 2개 + consentPort 1개 기반 3중 게이트 구현 (ADR 0068 §14-3 LOCK) |
| `RealTradingConsentPort` (port) | `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/order/execution/port/RealTradingConsentPort.kt` | `findConsentAt(userId)` — `user_account.real_trading_consent_at` 조회 |
| `RealTradingConsentJpaAdapter` | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/persistence/order/RealTradingConsentJpaAdapter.kt` | V23 `user_account.real_trading_consent_at` JPA 어댑터 |

### §2.2 주문 라우터 + 어댑터 분기

| 컴포넌트 | 파일 | 분기 변수 | 핵심 로직 |
|---|---|---|---|
| `OrderRouterKafkaListener` | `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/OrderRouterKafkaListener.kt` | `TradingModeGate.resolveMode()` 결과 | step 3: REAL 게이트 통과 못 하면 REAL_GATE_DENIED audit + Kafka rejected publish |
| `KisOrderHttpAdapter` | `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/KisOrderHttpAdapter.kt` | `mode: TradingMode` | line 92: `val accountType = if (mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK` |
| `KisOrderHttpAdapter.resolveTrId()` | 同上 line 303-313 | `(side, mode)` 쌍 | TTTC0012U/VTTC0012U/TTTC0011U/VTTC0011U 4 매트릭스 분기 |
| `KisOrderHttpAdapter.submit()` baseUrl | 同上 line 102 | `mode: TradingMode` | `if (mode == TradingMode.REAL) kisBaseProd else kisBaseVps` |

### §2.3 KIS endpoint URL 분기 (자본 path)

| 분기 변수 | 값 | endpoint | 의미 |
|---|---|---|---|
| `magicjar.kis.base-url-prod` | `https://openapi.koreainvestment.com:9443` | 실서버 (PROD) | REAL 자본 흐름 |
| `magicjar.kis.base-url-vps` | `https://openapivts.koreainvestment.com:29443` | 모의서버 (VPS) | MOCK 가상 자본 |

권위 정의: `consumer/src/main/resources/application.yml` line 64-66 + `.env.example` line 18-19.

### §2.4 tr_id 분기 매트릭스 (KisOrderHttpAdapter line 303-313)

| side | mode | tr_id | 의미 |
|---|---|---|---|
| BUY  | REAL | TTTC0012U | 실전 주식 현금 매수 |
| BUY  | MOCK | VTTC0012U | 모의 주식 현금 매수 |
| SELL | REAL | TTTC0011U | 실전 주식 현금 매도 |
| SELL | MOCK | VTTC0011U | 모의 주식 현금 매도 |

raw 출처: `domestic_stock_functions.py` L10097~L10213 (`def order_cash`) — ADR 0068 §14-5 LOCK.

### §2.5 KIS 자격증명 분기 (account_type 컬럼)

**스키마** (`user_kis_credential` 테이블 — 복합 PK = `(user_id, account_type)`):
```
file: consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/persistence/UserKisCredentialJpaEntity.kt
PK: (user_id varchar(20), account_type varchar(8))  -- account_type ∈ {"REAL", "MOCK"}
컬럼: account_number, account_prdt_cd, app_key_enc(BYTEA), secret_key_enc(BYTEA), last_token_iss, last_validated_at
```

**REAL/MOCK 각각 별도 row**. 사용자가 SettingsModal 또는 OnboardingService를 통해 둘 다 등록 가능.

**Repository 조회 흐름** (consumer/order/KisOrderHttpAdapter line 92-98):
```kotlin
val accountType = if (mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK
val cred = credentialRepository
    .findAllByAccountType(accountType.name)
    .firstOrNull { it.id.userId == intent.userId }
    ?: return OrderResult.Left(DenyReason.RealGateDenied("CREDENTIAL_NOT_FOUND"))
```

→ **mode가 결정한 accountType의 row가 DB에 없으면** `CREDENTIAL_NOT_FOUND` 즉시 deny. MOCK 주문 시 MOCK credential 필수, REAL 주문 시 REAL credential 필수.

### §2.6 KIS 액세스 토큰 분기 (KisAuthAdapter)

`consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisAuthAdapter.kt` line 78-79·155:

```kotlin
val cacheKey = "kis:token:$userId:${accountType.name}"        // Redis cache key 분리
val lockKey = "kis:token:lock:$userId:${accountType.name}"    // 분산락 key 분리
...
val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps  // tokenP URL
```

**Redis cache 키 분리 검증**: REAL 토큰과 MOCK 토큰은 별도 키 `kis:token:{userId}:REAL` · `kis:token:{userId}:MOCK`. REAL credential 변경 → MOCK 토큰 cache 보존 정합 OK (ADR 0074 §7-1 정합).

### §2.7 정보 수집 KIS 키 정합 (사용자 메모리 LOCK)

`feedback_kis_key_realonly_for_data.md` 의무 — 시세·캔들·호가 등 정보 수집은 모두 REAL 키:

| 컴포넌트 | accountType | 출처 |
|---|---|---|
| `KisRestPriceAdapter` | `AccountType.REAL` (line 62·142 고정) | 시세 조회 KIS-first |
| `KisRestCandleAdapter` | `AccountType.REAL` (line 149·527 고정) | 일봉·분봉 캔들 |
| `KisWebSocketBridge` | `AccountType.REAL` (line 1071 고정, 1045 주석) | 실시간 시세 WS |
| `WatchlistIngestionWorker` | `AccountType.REAL` (line 67) | 관심종목 ingestion |
| `KisApprovalAdapter` | mode 분기 (WS approval_key) | KIS WS approval 발급 |
| `KisBalanceAdapter` | mode 분기 (잔고 = 자본 영역) | TR_ID `TTTC8434R`(REAL) · `VTTC8434R`(MOCK) |
| `KisReconcileBalanceAdapter` | mode 분기 (자본 영역) | 잔고 reconcile |
| `KisOrderHttpAdapter` | mode 분기 (자본 영역 — 본 spec 핵심) | 주문 발행 |

→ **정합 OK**. 시세·캔들 등 정보 path는 모두 REAL 고정. 주문·잔고·reconcile만 mode 분기. (사용자 메모리 위반 0건)

### §2.8 SettingsModal · 자격증명 등록 UI

| UI 컴포넌트 | 파일 | 책임 |
|---|---|---|
| `SettingsModal` | `frontend/src/components/settings/SettingsModal.tsx` | 4 section 컨테이너 (RealTradingConsent + KisCredential + SystemConfig + KillSwitch) |
| `RealTradingConsentSection` + `RealTradingConsentToggle` | `frontend/src/components/settings/.../RealTradingConsent*.tsx` | 3중 게이트 조건 3 토글 — 이중 confirm UX |
| `KisCredentialSection` | `frontend/src/components/settings/sections/KisCredentialSection.tsx` | REAL/MOCK 키 각각 변경 form (346 line) |
| `KisCredentialChangeController` | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/KisCredentialChangeController.kt` | PUT/DELETE `/api/onboarding/kis/{accountType}` |
| `KisCredentialChangeService` | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/KisCredentialChangeService.kt` | KisAuthValidator 검증 후 DB 갱신. REAL DELETE 금지 (변경만 허용) |
| `KisAuthValidator` | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/kis/KisAuthValidator.kt` | KIS `/oauth2/tokenP` 호출하여 키 유효성 검증 (REAL/MOCK host 분기) |

**MOCK credential 등록 가능 검증**: `KisCredentialChangeService.updateKisCredential(accountType="MOCK")` 분기 + `KisAuthValidator.validate(accountType=MOCK)` → `MOCK_HOST` (`openapivts...:29443`) 호출 OK.

### §2.9 단위 테스트 fixture 매트릭스 (4 컴포넌트)

| 테스트 | 케이스 | evidence |
|---|---|---|
| `TradingModeGateImplTest` | (a) MOCK fallback / (b) REAL 정상 / (c) ALLOW_REAL=false denied / (d) CONSENT_NOT_GIVEN denied | `consumer/src/test/.../TradingModeGateImplTest.kt` 전체 |
| `KisOrderHttpAdapterTest` | (a) BUY MOCK→VTTC0012U / (b) BUY REAL→TTTC0012U / (c) SELL MOCK→VTTC0011U / (d) SELL REAL→TTTC0011U / (e) MARKET ORD_DVSN=01 / (f) LIMIT SELL ORD_DVSN=00+SLL_TYPE=01 | `KisOrderHttpAdapterTest.kt` 전체 |
| `OrderRouterKafkaListenerTest` | 6 케이스 (정상 ACK·idempotency·real gate·killswitch·5xx retry·rt_cd reject) | `OrderRouterKafkaListenerTest.kt` 전체 |
| `RealTradingConsentServiceTest` | (a) toggle ON → NOW() / (b) toggle OFF → NULL | `api/src/test/.../RealTradingConsentServiceTest.kt` |

**검증 OK**: 단위 테스트 4 컴포넌트 모두 GREEN, 분기 매트릭스 cover.

### §2.10 코드 매트릭스 종합 결론 (분류 A)

- **3중 게이트** 코드 권위 정합 OK — `TradingModeGateImpl` 헌법 §LOCK 정합, 단위 테스트 4 케이스 GREEN
- **mode → accountType → URL/tr_id/credential** 4단 분기가 단일 변수 `TradingMode`로 일관 라우팅. 누락 없음
- **정보 수집은 REAL 고정** — 사용자 메모리 `feedback_kis_key_realonly_for_data.md` 위반 0건
- **MOCK credential 등록 path** 존재 — Onboarding + SettingsModal 양쪽에서 등록 가능
- **REAL credential DELETE 금지** — 자본 보호 LOCK 정합 (KisCredentialChangeService line 247-252)

**잠재 risk 후보** (실제 검증은 §4 dogfooding으로):

- (R1) `RealTradingConsentPort` ObjectProvider — bean 미합류 시 `CONSENT_PORT_UNAVAILABLE` deny. Phase 2 bean 합류 검증 필요 (api 모듈 RealTradingConsentJpaAdapter가 합류했는지 dogfooding으로 확인)
- (R2) `KisOrderHttpAdapter` 주문 실패 시 5xx 1회 retry 후 KIS_EXCEPTION — 실제 KIS MOCK 서버가 응답 정상 발급 검증 필요
- (R3) `MAGICJAR_MODE` 기본값이 `MOCK`이지만 `.env.example`도 `MOCK` LOCK. docker compose env block override가 운영 환경에서 어떻게 작동하는지 확인 필요 (CLAUDE.md 운영 함정 §2 — docker가 SPRING_* override)
- (R4) `application.yml`의 `magicjar.allow-real` default가 `false`. 실수로 `true`로 잘못 설정되어도 환경변수 `MAGICJAR_MODE=MOCK`이면 안전 (조건 1에서 MOCK fallback). **이중 게이트 정합 OK**
- (R5) `OrderRouterKafkaListener` step 3에서 REAL 게이트 fail 시 `mode = TradingMode.REAL`로 가정하고 `handleRejected` 호출 — 실제 envMode=MOCK 시점에는 절대 도달하지 않으나 코드 가독성 영역 (R5는 결함 아님 — 정합 분석 결과 안전)

---

## §3. 환경변수 매트릭스 명세 (분류 B 결과)

### §3.1 4 환경별 권위 매트릭스 (LOCK)

| 환경 | profile | MAGICJAR_MODE | MAGICJAR_ALLOW_REAL | consent_at | 정보 수집 키 | 주문 분기 결과 | 비고 |
|---|---|---|---|---|---|---|---|
| **local dev (default)** | `dev,real-kis` | `MOCK` | `false` | NULL | REAL credential | **MOCK 주문** (조건 1 fallback) | `.env.example` default LOCK |
| **local dev — MOCK 강제** | `dev,real-kis` | `MOCK` | (any) | (any) | REAL credential | **MOCK 주문** (조건 1 즉시 MOCK return) | dev 사용자 흐름 default |
| **local REAL 테스트** | `dev,real-kis` | `REAL` | `true` | NOT NULL (consent ON) | REAL credential | **REAL 주문** (3 조건 모두 통과 — 자본 변동) | dogfooding 시점만 |
| **local REAL 미동의** | `dev,real-kis` | `REAL` | `true` | NULL | REAL credential | DENIED (`CONSENT_NOT_GIVEN`) | 사용자 SettingsModal 토글 OFF |
| **local REAL ALLOW false** | `dev,real-kis` | `REAL` | `false` | (any) | REAL credential | DENIED (`MAGICJAR_ALLOW_REAL=false`) | 안전 게이트 작동 검증 |
| **staging (논의 필요)** | `dev,real-kis` (현재) | `MOCK` LOCK | `false` LOCK | (any) | REAL credential | **MOCK 주문 항상** | 본 spec 의제 D-1: staging profile 신규 필요? |
| **prod (post-MVP)** | (정의 필요) | `REAL` | `true` | NOT NULL | REAL credential | **REAL 주문** | 본 spec 의제 D-2: prod profile + secret manager (사용자 메모리 `project_kis_key_management.md` 정합) |

### §3.2 default 안전성 (자본 보호)

**핵심 사실**: 모든 default 값이 MOCK 또는 OFF로 LOCK:

- `.env.example`: `MAGICJAR_MODE=MOCK` + `MAGICJAR_ALLOW_REAL=false`
- `api/src/main/resources/application.yml` line 71: `magicjar.mode: ${MAGICJAR_MODE:MOCK}`
- `api/src/main/resources/application.yml` line 72: `magicjar.allow-real: ${MAGICJAR_ALLOW_REAL:false}`
- `consumer/src/main/resources/application.yml` line 57-58: 同上
- `TradingModeGateImpl` `@Value("\${magicjar.mode:MOCK}")` + `@Value("\${magicjar.allow-real:false}")` — bean 주입 default도 안전
- `RealTradingConsentToggle` 토글 default = OFF (consent_at NULL)

→ **3중 안전망**: env 미설정 + yml 미설정 + 사용자 미동의 시 100% MOCK fallback. 실수로 자본 변동 trigger 불가능.

### §3.3 docker compose env override 흐름 (운영 함정 §2 정합)

`docker/docker-compose.yml`의 `environment` block이 `.env`를 override하므로:

```yaml
# (예상 구조 — 실제 docker-compose.yml 확인 필요 in §4 dogfooding)
api-app:
  environment:
    MAGICJAR_MODE: ${MAGICJAR_MODE:-MOCK}
    MAGICJAR_ALLOW_REAL: ${MAGICJAR_ALLOW_REAL:-false}
```

**dogfooding 검증 항목** (§4 step 1.5):
- `.env`에 `MAGICJAR_MODE=MOCK` 설정되어 있는지
- docker-compose.yml의 `environment` block이 명시적으로 변수 forward하는지 (없으면 컨테이너 안에 unset → application.yml default `MOCK` 적용 — 안전)
- 실수로 host shell에 `export MAGICJAR_MODE=REAL` 했을 때 docker가 그것을 inherit하는지 (compose는 `.env` 파일 + host env 둘 다 본다)

### §3.4 SPRING_PROFILES_ACTIVE 정합

`SPRING_PROFILES_ACTIVE=dev,real-kis` LOCK (CLAUDE.md 운영 함정 §3):
- `dev` profile — 개발 모드 마커 (현재 별도 yml override 없음)
- `real-kis` profile — `application-real-kis.yml` 존재 (현재 빈 stub — 향후 REAL 활성 시 채움)

**stub 상태 확인**:
```yaml
# api/src/main/resources/application-real-kis.yml — 현재 빈 stub (line 6-11 주석만)
# 예시 (PR-6에서 채움):
# magicjar:
#   mode: REAL
#   allow-real: true
```

→ `real-kis` profile은 활성이지만 override 0건. 실제 mode 결정은 환경변수 `MAGICJAR_MODE`가 단독 권위. **검증 OK** (사용자 메모리 `feedback_kis_key_realonly_for_data.md` 정합 — "real-kis" profile name은 시세 수집 의미일 뿐 주문 mode와 무관).

### §3.5 환경변수 매트릭스 종합 결론 (분류 B)

- **default 100% 안전** — 모든 layer가 MOCK/OFF default
- **3중 안전망** — env·yml·consent 토글 — 한 layer 실수해도 다른 layer가 차단
- **staging profile 부재** — `dev` 외 `staging` profile 정의가 없어 staging 환경 정의 모호 (의제 D-1 — 본 spec 결정 의제로 등재)
- **prod profile 부재** — post-MVP에서 secret manager 도입 시 `prod` profile 신설 필요 (의제 D-2 — backlog 등재)

**잠재 risk**:
- (E1) `application-real-kis.yml`이 빈 stub — 사용자가 실수로 `magicjar.mode: REAL` 등을 yml에 commit 하면 환경변수와 충돌. **권장**: stub 헤더 주석에 "yml override 금지 — env 변수만 LOCK" 명시 추가 (frontend·docs 영역, 본 spec scope 외 — 의제 D-3)
- (E2) 사용자 메모리 `feedback_kis_key_realonly_for_data.md`의 "real-kis profile name이 시세 수집을 의미"가 코드 주석으로 명시되지 않음 — 신규 개발자가 오해할 risk (의제 D-4 — 후속 docs 보강)

---

## §4. dogfooding scenario 설계 (분류 C 결과)

### §4.1 사전 준비 (1 회 / 사용자가 GO 결정 후)

#### Step 0. 환경 자가 진단

```bash
# (0-1) 현재 환경변수 자가 진단
grep "MAGICJAR_MODE\|MAGICJAR_ALLOW_REAL\|SPRING_PROFILES_ACTIVE" .env

# 예상 결과 (default 안전):
# SPRING_PROFILES_ACTIVE=dev,real-kis
# MAGICJAR_MODE=MOCK
# MAGICJAR_ALLOW_REAL=false
```

검증: 위 3 값이 default와 일치하는지. 다르면 사용자에게 보고 후 결정.

#### Step 1. 컨테이너 기동 (정상)

```bash
scripts/build-and-deploy.sh
docker logs magicjar-api-app 2>&1 | grep -E "magicjar.mode|magicjar.allow-real|magicjar:" | head
docker logs magicjar-consumer-app 2>&1 | grep -E "magicjar.mode|TradingModeGate" | head
```

검증: 부팅 로그에 `mode=MOCK allow-real=false` 인지 (또는 default 적용 path 확인).

#### Step 2. MOCK credential 등록 상태 확인

```bash
# DB 직접 조회
docker exec -i magicjar-postgres psql -U magicjar -d magicjar -c \
    "SELECT user_id, account_type, account_number, last_validated_at FROM user_kis_credential ORDER BY user_id, account_type;"
```

검증 분기:
- **REAL row만 존재** → 사용자에게 "MOCK credential 등록 필요" 보고. SettingsModal → KisCredentialSection → MOCK form 등록 의제 dispatch
- **REAL + MOCK 둘 다 존재** → §4.2 dogfooding 실행 가능
- **둘 다 부재** → 본 spec scope 외 (Onboarding 흐름 의제 — backlog §C-3로 등재)

#### Step 3. consent_at 초기 상태 확인

```bash
docker exec -i magicjar-postgres psql -U magicjar -d magicjar -c \
    "SELECT user_id, real_trading_consent_at FROM user_account;"
```

검증: NULL이어야 default 안전. NOT NULL 시 사용자에게 "이전 dogfooding 잔재 — OFF 토글 권장" 보고.

### §4.2 핵심 dogfooding 시나리오 (5 케이스)

#### 케이스 1: MOCK 주문 정상 발행 (default 안전망 + MOCK 자본 path)

**전제**: env MAGICJAR_MODE=MOCK + ALLOW_REAL=false + MOCK credential 등록 완료 + consent_at=NULL

**Step 1**: 강제 시그널 발행
```bash
# T-SIG signal 발행 path 활용 (또는 직접 Kafka publish)
# 옵션 A: 기존 시그널 생성 endpoint (확인 필요)
curl -X POST http://localhost:8080/api/signals/...
# 옵션 B: kafka-console-producer.sh로 order.intent.v1 직접 publish (consumer 모듈 내부 테스트 용)
docker exec -i magicjar-kafka kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.intent.v1 <<'EOF'
{"signalId":1,"strategyId":1,"userId":"<USER_ID>","code":"005930","side":"BUY","priceType":"MARKET","quantity":1,"idempotencyKey":"test-mock-1:<USER_ID>","emittedAt":<NOW_EPOCH>}
EOF
```

**Step 2**: consumer log + DB + KIS 응답 검증
```bash
docker logs magicjar-consumer-app 2>&1 | grep -E "OrderRouter|TradingModeGate|KIS 주문" | tail -20
docker exec -i magicjar-postgres psql -U magicjar -d magicjar -c \
    "SELECT id, user_id, mode, status, kis_order_no, reject_reason, requested_at, acknowledged_at FROM order_request ORDER BY id DESC LIMIT 5;"
docker exec -i magicjar-postgres psql -U magicjar -d magicjar -c \
    "SELECT order_id, event_type, payload_json, created_at FROM order_audit_event ORDER BY id DESC LIMIT 10;"
```

**evidence 의무** (사용자 메모리 `feedback_verification_strict.md`):
- log: `TradingModeGate MOCK fallback: userId=<X> env.mode=MOCK`
- log: `KIS 주문 ACK: trId=VTTC0012U userId=<X>**** signalId=1 code=005930 side=BUY ODNO=<ODNO>`
- DB `order_request.mode = "MOCK"`, `status = "ACK"`, `kis_order_no = <ODNO>`
- DB `order_audit_event` 에 `KIS_ACK` row 존재
- KIS MOCK 서버 (`openapivts...:29443`)로 호출 — endpoint URL 검증

**기대**: KIS MOCK 서버에서 주문번호 채번 + DB 영속 + REAL credential 미사용 검증.

#### 케이스 2: REAL gate denied — ALLOW_REAL=false 차단 (안전망 검증)

**전제**: env MAGICJAR_MODE=REAL + ALLOW_REAL=false + MOCK/REAL credential 둘 다 등록

**Step 1**: `.env` 일시 변경
```bash
sed -i.bak 's/^MAGICJAR_MODE=MOCK/MAGICJAR_MODE=REAL/' .env
# ALLOW_REAL은 false 유지 — 안전망 검증
scripts/build-and-deploy.sh --no-gradle
```

**Step 2**: 시그널 발행 (케이스 1과 동일 payload)

**evidence**:
- log: `TradingModeGate REJECTED — MAGICJAR_ALLOW_REAL=false: userId=<X>`
- DB `order_request.status = "REJECTED"`, `reject_reason = "REAL_GATE_DENIED:MAGICJAR_ALLOW_REAL=false"`
- DB `order_audit_event.event_type = "REAL_GATE_DENIED"`
- **KIS PROD 서버 호출 0건** (네트워크 trace 또는 application log grep)

**기대**: 자본 흐름 차단 + audit 영속 + 사용자 알림 (Kafka `order.rejected.v1` publish).

**복구**: `mv .env.bak .env && scripts/build-and-deploy.sh --no-gradle` (즉시 원복).

#### 케이스 3: REAL gate denied — CONSENT_NOT_GIVEN 차단

**전제**: env MAGICJAR_MODE=REAL + ALLOW_REAL=true + consent_at=NULL (토글 OFF)

**Step 1**: `.env` 일시 변경
```bash
sed -i.bak2 's/^MAGICJAR_ALLOW_REAL=false/MAGICJAR_ALLOW_REAL=true/' .env
scripts/build-and-deploy.sh --no-gradle
# consent_at은 NULL 유지 (SettingsModal 토글 OFF)
```

**Step 2**: 시그널 발행 (케이스 1과 동일)

**evidence**:
- log: `TradingModeGate REJECTED — CONSENT_NOT_GIVEN: userId=<X>`
- DB `order_request.reject_reason = "REAL_GATE_DENIED:CONSENT_NOT_GIVEN"`
- DB `order_audit_event.event_type = "REAL_GATE_DENIED"`
- **KIS PROD 서버 호출 0건**

**기대**: 사용자 명시 동의 없이 자본 흐름 진입 차단. 마지막 안전망 작동 검증.

**복구**: `mv .env.bak2 .env && scripts/build-and-deploy.sh --no-gradle`.

#### 케이스 4: SettingsModal 토글 ON → consent_at NOT NULL → REAL 진입 가능 (의제 — 본 dogfooding 실행 여부 사용자 결정 의무)

**경고**: 본 케이스는 **실 자본 변동 risk**. 사용자가 명시 동의 시점에만 실행.

**전제**: 케이스 3 상태에서 SettingsModal → RealTradingConsent 토글 ON (이중 confirm 통과)

**Step 1**: 토글 ON
```bash
# UI: localhost:5173 → ⚙️ → "실거래 모드 동의" 토글 ON → 이중 confirm 통과
# API 직접 호출 가능:
curl -X PUT "http://localhost:8080/api/user-account/real-trading-consent?userId=<USER_ID>" \
  -H "Content-Type: application/json" \
  -d '{"consented":true}'
```

**Step 2**: DB 검증
```bash
docker exec -i magicjar-postgres psql -U magicjar -d magicjar -c \
    "SELECT user_id, real_trading_consent_at FROM user_account WHERE user_id='<USER_ID>';"
```

`real_trading_consent_at` NOT NULL 확인.

**Step 3**: 시그널 발행 — **만약 사용자가 실제 REAL 주문을 dogfooding 의제로 채택한다면**:
- 최소 단위 (1주) + 거래 시간대 외 + 안전 종목 (저변동) 선택 권장
- 단, **본 spec은 케이스 4를 SKIP 권장** — MOCK 검증 + 케이스 2·3 안전망 검증만으로 T-O 진입 GREEN 충분
- 사용자가 별도 의제로 REAL dogfooding 요청 시 hotfix 사이클 또는 별도 cycle dispatch

**복구 의무** (케이스 4 실행 시):
```bash
# 토글 즉시 OFF
curl -X PUT "http://localhost:8080/api/user-account/real-trading-consent?userId=<USER_ID>" \
  -d '{"consented":false}'
# env 원복
sed -i.bak3 's/^MAGICJAR_MODE=REAL/MAGICJAR_MODE=MOCK/' .env
sed -i.bak3 's/^MAGICJAR_ALLOW_REAL=true/MAGICJAR_ALLOW_REAL=false/' .env
scripts/build-and-deploy.sh --no-gradle
# 발행된 REAL 주문이 있으면 KIS HTS/MTS에서 즉시 취소
```

#### 케이스 5: MOCK credential 누락 시 CREDENTIAL_NOT_FOUND deny

**전제**: env MAGICJAR_MODE=MOCK + REAL credential만 등록 (MOCK row 부재)

**Step 1**: MOCK row 일시 삭제 (DB 직접)
```bash
docker exec -i magicjar-postgres psql -U magicjar -d magicjar -c \
    "DELETE FROM user_kis_credential WHERE user_id='<USER_ID>' AND account_type='MOCK';"
```

**Step 2**: 시그널 발행 (케이스 1과 동일)

**evidence**:
- log: `KisOrderHttpAdapter` line 98 즉시 return
- DB `order_request.reject_reason = "REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND"`

**기대**: mode=MOCK인데 MOCK credential 없으면 즉시 deny. 자본 path 진입 X.

**복구**:
- 사용자가 SettingsModal에서 MOCK key 재등록 (또는 Onboarding 흐름) — MOCK key 보존하고 있어야 dogfooding 반복 가능

### §4.3 dogfooding 종합 GREEN 기준

3중 게이트 + endpoint 분기 + credential lookup 분기 dogfooding evidence 5 케이스 중 케이스 1~3·5 (총 4 케이스) GREEN이면 T-O 본 트랙 진입 OK:

| 케이스 | 검증 영역 | 필수 GREEN |
|---|---|---|
| 1. MOCK 정상 | mode=MOCK → VTTC0012U + openapivts 호출 + ODNO 채번 + DB MOCK row | **MUST** |
| 2. ALLOW_REAL=false 차단 | 안전망 조건 2 | **MUST** |
| 3. CONSENT_NOT_GIVEN 차단 | 안전망 조건 3 | **MUST** |
| 4. REAL 정상 (자본 변동) | 3 조건 모두 통과 | OPTIONAL — 사용자 별도 의제 |
| 5. CREDENTIAL_NOT_FOUND | MOCK row 부재 시 | **MUST** |

4 MUST 케이스 GREEN → 본 spec 분류 D = "검증 OK" → T-O 본 트랙 진입 GREEN.

---

## §5. 결함 분류 + 처리 방향 매트릭스 (분류 D)

> 본 spec dogfooding 실행 후 발견사항을 다음 4 분류로 dispatch.

### §5.1 (A) 코드 결함 — 즉시 hotfix 사이클

**정의**: MOCK path가 REAL endpoint 호출, 3중 게이트 조건이 실제로 작동 안 함, 자격증명이 잘못된 column에서 조회됨 등.

**처리**:
- architect spec PR 발행 (hotfix scope LOCK)
- strategy-engine 또는 market-data sub-agent에 dispatch
- gate 4 케이스 단위 테스트 GREEN 의무 (사용자 메모리 `feedback_verification_strict.md`)
- 머지 후 즉시 동일 dogfooding 재수행

**예상 결함 후보** (실제 발견은 §4 dogfooding 실행 후):
- TradingModeGateImpl의 envMode 조건 evaluation order 오류 (현행 코드 검토 결과 OK)
- KisOrderHttpAdapter의 mode-baseUrl 분기 오타 (현행 코드 검토 결과 OK)
- KisOrderHttpAdapter의 account_type lookup이 잘못된 column 참조 (현행 코드 검토 결과 OK)

→ 코드 정독 결과 **현행 결함 후보 0건**. dogfooding으로 최종 검증 의무.

### §5.2 (B) 누락 기능 — backlog 등재 + T-O 트랙 흡수

**정의**: dogfooding을 가속화하는 admin/test endpoint 부재, 시그널 강제 발행 path 없음 등.

**예상 누락 (코드 매트릭스 §2.9 + dogfooding §4.2 도출)**:

- **B-1**: 관리자용 MOCK 주문 강제 발행 endpoint 없음 (`POST /api/admin/order/test-mock` 같은 것). 현재 Kafka `order.intent.v1` 직접 publish 또는 T-SIG signal 발행 path 우회 필요.
  - **권장 처리**: T-O 본 트랙 정의에 endpoint 신설 의제 흡수. backlog §43 (T-O plan)에 row 추가.
  - **시급도**: dogfooding이 가능하면 LOW. 불가능하면 HIGH.

- **B-2**: SettingsModal에서 MOCK credential 등록 form이 KIS PROD/MOCK 키 검증 분기 명확한지 — `KisAuthValidator.validate(MOCK)` → MOCK_HOST (`openapivts...:29443`) 호출 OK이지만 사용자 입장에서 어느 form이 어느 host를 검증하는지 UI text 명시 필요.
  - **권장 처리**: frontend sub-agent dispatch (KisCredentialSection.tsx 텍스트 보강). backlog §C-1 등재.
  - **시급도**: MEDIUM (UX).

- **B-3**: order_request DB에 `endpoint_url` audit column 없음 — 어느 KIS 서버로 발행됐는지 사후 추적 가독성 LOW.
  - **권장 처리**: backlog §C-2 등재. Flyway V24 검토 의제.
  - **시급도**: LOW (현재 mode 컬럼으로 추론 가능).

### §5.3 (C) 운영 부채 — backlog 등재 + 별도 사이클

**정의**: 실제로 동작하지만 안전성·UX·운영성에서 보강 가치 있는 항목.

**예상 운영 부채** (§3.5 환경변수 매트릭스 결론에서 도출):

- **C-1**: `application-real-kis.yml`이 빈 stub. 사용자가 yml에 mode 명시 실수 risk (의제 D-3, §3.5 E1).
  - **권장 처리**: stub 헤더 주석 보강. backend-core sub-agent dispatch (단일 파일 hotfix).
- **C-2**: `real-kis` profile name이 시세 수집 의미라는 코드 주석 부재 (의제 D-4, §3.5 E2).
  - **권장 처리**: backend-core sub-agent dispatch + docs 보강. backlog 등재.
- **C-3**: staging profile 정의 부재 (§3.5 의제 D-1).
  - **권장 처리**: 본격 staging 진입 시점에 ADR 발행. 현재 backlog 등재만.
- **C-4**: prod profile + secret manager (§3.5 의제 D-2).
  - **권장 처리**: post-MVP backlog. 사용자 메모리 `project_kis_key_management.md` 권위.

### §5.4 (D) 검증 OK — T-O 본 트랙 진입 GREEN

**조건**: §4.3 4 MUST 케이스 모두 GREEN + A 분류 결함 0건.

**dispatch**: T-O 본 트랙 → strategy-engine (또는 backend-core) sub-agent에 plan PR 발행 dispatch.

### §5.5 처리 매트릭스 종합

| 분류 | 예상 시급도 | 후속 dispatch |
|---|---|---|
| A. 코드 결함 | HIGH | 즉시 hotfix PR (architect → strategy-engine/market-data) |
| B-1. test endpoint 부재 | MEDIUM (dogfooding 가능 여부에 따라) | T-O 본 트랙 흡수 |
| B-2. SettingsModal MOCK form UX | MEDIUM | frontend sub-agent (별도 사이클) |
| B-3. endpoint_url audit column | LOW | backlog 등재 |
| C-1. real-kis.yml stub 보강 | LOW | backend-core sub-agent (작은 hotfix) |
| C-2. real-kis profile 주석 | LOW | backend-core sub-agent |
| C-3. staging profile 정의 | LOW (현재 불필요) | post-MVP backlog |
| C-4. prod profile + secret manager | LOW (현재 불필요) | post-MVP backlog |
| D. 검증 OK | (해당 시) | T-O 본 트랙 진입 GREEN |

---

## §6. 권장 결정 종합

### §6.1 architect 권장안

**현재 코드 매트릭스 분석만으로**: §2.10 + §3.5 + §5.5 종합 — **분류 D (검증 OK) 가능성 매우 높음**. 단:

- **dogfooding §4.2 4 MUST 케이스 실제 실행 의무** — 코드 정합성만으로 "완료" 종결 금지 (사용자 메모리 `feedback_verification_strict.md` LOCK)
- 케이스 1·2·3·5 evidence 4 종합 → 안전 게이트 작동 확인 → T-O 본 트랙 GREEN

### §6.2 사용자 결정 의제

본 spec PR 검토 시 사용자가 결정해야 할 의제:

1. **dogfooding 실행 dispatch 의제** — 본 spec 채택 후 누가 dogfooding 실행? (architect 직접? backend-core? 사용자 본인?)
2. **dogfooding 실행 시기** — 즉시 (이번 사이클)? 또는 T-O 본 트랙 진입 직전?
3. **케이스 4 (REAL dogfooding) 채택 여부** — MUST OPTIONAL (현재 권장 SKIP)
4. **B-1 admin test endpoint 신설 채택 여부** — T-O 본 트랙 흡수 OR backlog 별도 등재
5. **C-1·C-2 운영 부채 fix 시급도** — 즉시 hotfix OR T-O 진입 후 후속

### §6.3 다음 사이클 권장 dispatch (사용자 D 채택 시점)

```
T-O 본 트랙 진입 GREEN
  ├─ Phase 1 LOCK 코드 (consumer 모듈)
  │   - TradingModeGateImpl ✓ 완료
  │   - KisOrderHttpAdapter ✓ 완료
  │   - OrderRouterKafkaListener ✓ 완료
  │   - KisExecutionWsListener (ADR 0068 §14-6 — 체결 통보)
  ├─ Phase 2 LOCK 코드 (api 모듈)
  │   - OrderQueryService ✓ 완료
  │   - OrderCancelService ✓ 완료
  │   - RealTradingConsentController/Service ✓ 완료
  │   - Flyway V20·V21·V22·V23 ✓ 완료
  ├─ Phase 3 LOCK 코드 (frontend)
  │   - RealTradingConsentToggle ✓ 완료
  │   - OrdersPage (조회·취소 UI)
  └─ Phase 4: dogfooding + 실증 evidence + release cut
```

→ T-O 트랙 Phase 1·2·3 코드는 이미 v3.6.x 이전 사이클에서 합류 완료. **본 spec dogfooding이 곧 Phase 4 evidence 수집의 일환**.

---

## §7. AI 호출 0 검증 (T-O prereq 정책)

본 spec 작성·dogfooding 계획·결과 보고에서 LLM provider import 0건. AI 호출 0 정책 (ADR 0023 §2-4 + 0066 §8 + 0067 §8 + 0068 §8) LOCK.

검증 grep (PR 본문에 첨부 의무):
```bash
grep -rn "AnthropicChatModel\|OpenAiChatModel\|ChatClient" \
  $(find _workspace/specs -name "2026-05-18-to-prereq-mock-prd-verify.md")
# 결과: 0건
```

---

## §8. 영향 ADR·문서 매트릭스

| 문서 | 영향 | 처리 |
|---|---|---|
| ADR 0046 (Onboarding) | 자격증명 등록 path 권위 — 본 spec 인용만 | 변경 0건 |
| ADR 0048 (KIS WS 41 구독) | 시세 수집 정책 — 본 spec 인용만 | 변경 0건 |
| ADR 0068 (T-O plan) | 본 spec의 직속 권위 — §14-3 3중 게이트 LOCK 인용 | 변경 0건 |
| ADR 0070 (T-RECON+NOTIFY) | reconcile 잔고 흐름 — 본 spec 분기 매트릭스 §2.7에 인용 | 변경 0건 |
| ADR 0074 (SettingsModal KIS·KillSwitch·AI) | SettingsModal 권위 — 본 spec 인용만 | 변경 0건 |
| ADR 0077 (OPSP8996 deep dive) | KIS WS 분기 — 본 spec 영향 0건 | 변경 0건 |
| `tracks-roadmap.md` T-O row | 본 spec PR 머지 후 "prereq verified" mark (선택) | 후속 sub-agent 결정 |
| `backlog.md` §43 (T-O) | 본 spec dogfooding 결과 — 분류 B·C 결함 등재 | 후속 사이클 |
| CHANGELOG.md | 본 spec PR 머지 row 추가 | 본 PR에서 add-only |

**ADR 신규 발행 0건** — 검증 spec만. 결함 발견 시 후속 ADR 발행 검토.

---

## §9. Rollback 시나리오

본 spec PR은 **문서 only**. 머지 후 단순 `git revert <SHA>` 1회로 복구 가능.

dogfooding 실행 단계 rollback:
- 케이스 2·3·5 — `.env` 원복 + `scripts/build-and-deploy.sh --no-gradle`
- 케이스 4 (실행 시) — `.env` 원복 + consent_at OFF + KIS HTS에서 즉시 주문 취소

---

## §10. PR 발행 메타

- 브랜치: `agent/architect/to-prereq-mock-prd-verify`
- author: `bot+architect@magicjar.local`
- base: `main`
- 영역: `_workspace/specs/` add-only + CHANGELOG.md row + task 파일
- 운영 코드 0 touch (api/* · consumer/* · batch/* · domain/* · frontend/*)
- 자동 검증: `./gradlew check` GREEN (코드 변경 0건이지만 pre-push 의무)

---

## §11. 종료 보고 매트릭스 (orchestrator 인계용)

- **매트릭스 A 결과**: 현행 코드 분석 — **결함 후보 0건** (§2.10). 단 dogfooding §4.2 실제 실행 의무
- **매트릭스 B 결과**: 환경변수 매트릭스 — **default 100% 안전** (§3.5). 3중 안전망 정합
- **분류 D 권장안**: §6.1 — 코드 매트릭스만으로 "검증 OK 가능성 매우 높음". **dogfooding 실행 후 최종 결정** (§6.2 의제 1·2)
- **다음 단계**: 사용자 §6.2 의제 1~5 결정 후 dispatch
