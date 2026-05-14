# ADR 0074 — SettingsModal 풀세트 — KIS 키 변경 + KillSwitch 수동 토글 + OpenAI/Anthropic 키 카테고리

- 상태: Accepted
- 작성일: 2026-05-14
- 결정자: architect (사용자 자율 위임 — 2026-05-14 "§44~§45부터 하자")
- 관계: **ADR 0069 §3 1차 PR scope 후속 풀세트 (KIS 키 변경 활성화)** · **ADR 0069 §7 backlog 의제 §E·§G 흡수** · ADR 0046 정합(Onboarding Setup + AES 암호화 baseline) · ADR 0043 정합(KillSwitch 행동 — OFF 토글 사용자 수동 only) · ADR 0024 정합(KillSwitch 트리거 정책 + Redis 캐시) · ADR 0070 §2-11 정합(KillSwitch auto-ON 흐름 reuse) · ADR 0068 §15-2 정합(KillSwitchQueryAdapter) · ADR 0038 §2 정합(KIS OAuth 분산락 + 토큰 cache) · ADR 0048 §3 정합(WS graceful reconnect) · ADR 0044 정합(Slack webhook 1차) · **ADR 0073(T-NEWS feasibility 동일자 발행, 별개 트랙 — ADR 번호 0073은 T-NEWS 점유로 본 ADR은 0074 재발행. close된 PR #415 본문 참조 — `agent/architect/settings-kis-change-spec` branch 보존)** · 메모리 `feedback_secret_handling.md`(키 평문 응답 금지) · 메모리 `project_kis_key_management.md`(운영 중 ⚙️ 입력 + AES) · 메모리 `feedback_kis_key_realonly_for_data.md`(REAL 키 단일) · 헌법 §1 사용자=최고 관리자 1인

---

## 1. 컨텍스트 + fitness gap (왜 이 결정이 지금 필요한가)

### 1-1. ADR 0069 1차 PR scope LOCK 후속 의제

ADR 0069 §3 1차 PR scope는 SettingsModal 3 section을 LOCK했고, **KIS 키 변경 submit은 Option A (frontend stub/disabled)** 로 결정했음. 그 결과 PR #389 SettingsModal 1차 구현 머지 후 dogfooding 진입 시:

- `KisCredentialSection` UI 노출됨 (REAL 키 변경 폼 + MOCK 키 추가 폼)
- 그러나 submit 버튼 disabled (`backend endpoint 미구현` 안내 tooltip만 표시)
- 사용자가 `.env` 직접 편집 또는 Postman 등 외부 도구 없이는 KIS 키 변경 path 0건

→ **운영 변경 path 부재** — KIS 키 만료·교체·재발급 시 운영 차단 risk.

### 1-2. ADR 0069 §7 backlog 의제 §E·§G 동시 흡수 동기

ADR 0069 §7 backlog 의제 10건 중 본 사이클(v3.6.5 patch)에서 동시 흡수 2건:

| 의제 | 진입 시점 (ADR 0069 §7 명시) | 본 사이클 흡수 사유 |
|---|---|---|
| **§G KillSwitchSection** | T-J + T-RISK 통합 시 | **T-RECON·T-NOTIFY (ADR 0070) 마감 + T-J·T-PERF (ADR 0072) 마감 → 진입 시점 도래** |
| **§E OpenAiKeySection** | T-AI 트랙 진입 시 (W4) | **T-AI prereq 미리 발행** — env에서 SettingsModal 이관 결정만 본 ADR에서 LOCK + 실구현은 T-AI 사이클로 분리 가능 |

ADR 0070 §2-11 KillSwitch auto-ON 흐름은 backend 단독 완성(drift 검출 → `kill_switch_state.enabled=true` → Slack P0). 그러나 **사용자 수동 OFF 토글**(ADR 0043 §2-3 자동 OFF 0 LOCK 정합) UX는 미구현 — 사용자가 KIS HTS 직접 확인 후 OFF 토글 path 부재.

### 1-3. 자본 보호 fitness (v1/v2 회귀 의심 lens — 메모리 `feedback_v3_skepticism_no_regression.md`)

- 본 ADR은 v3 자동매매 본체에 직접 필요한가? **YES**
  - KIS 키 변경 = 운영 baseline path (PR #389가 1차 stub LOCK했으나 변경 path 부재는 dogfooding 차단)
  - KillSwitch 사용자 수동 토글 = ADR 0043 §2-3 자동 OFF 0 LOCK 정합 = **자본 보호 종착 path** (drift 자동 ON → 사용자 수동 OFF 사이클 완성)
  - OpenAI/Anthropic 키 카테고리 = T-AI 트랙 prereq + 환경 secret 노출 감소 (env → DB AES, 사용자 ⚙️ 입력 path)
- v1/v2 잔재 의심: 0건 — v3.0.0에서 PoC 정리 완료, 본 path는 v3 신규 SettingsModal baseline 후속

### 1-4. 필요한 결정 (12개 §)

§1 fitness · §2 KIS 키 변경 풀세트 (3건 LOCK) · §3 KillSwitch 수동 토글 section · §4 OpenAI/Anthropic 키 카테고리 · §5 DB Flyway V32 필요성 검토 · §6 REST endpoint 매트릭스 · §7 Redis 키 invalidate 패턴 · §8 frontend UI 매트릭스 · §9 Phase 분리 LOCK · §10 검증 시나리오 · §11 한계 + 별도 backlog 흡수 · §12 Rollback 시나리오

## 2. 결정

### 2-1. §1 fitness 결정 — 자본 보호 prereq + 미래 확장 패턴 표준 구현 LOCK

본 ADR 0074은 ADR 0069 baseline 후속 풀세트 의제 1건이며 **세 가지 fitness 동시 만족**:

| Fitness 축 | 본 ADR 결정 |
|---|---|
| **자본 보호** | KillSwitch 사용자 수동 OFF 토글 UX 추가 — drift auto-ON 후 사용자 직접 확인 종착 path 완성. ADR 0043 §2-3 자동 OFF 0 LOCK 정합 (사용자 수동만 OFF 가능) |
| **운영 path** | KIS 키 변경 풀세트 — PR #389 stub LOCK 해소. 키 만료·교체·재발급 운영 path 완성 |
| **미래 확장 표준** | OpenAI/Anthropic 키 카테고리 — ADR 0069 §4 신규 API 키 추가 5 step 표준 절차의 첫 실전 적용 (env → DB AES, 향후 Naver·DART 등 동일 패턴) |

ADR 0070 §2-11 KillSwitch auto-ON + 본 ADR 사용자 수동 OFF = **자본 보호 단일 흐름 완성** (drift 검출 → 자동 ON → Slack P0 → 사용자 KIS HTS 직접 확인 → 수동 OFF + 사유 입력).

### 2-2. §2 KIS 키 변경 풀세트 LOCK (3건 — backlog §44 본문 정합)

#### 2-2-1. PUT endpoint 활성화 (변경 전용, 첫 등록과 분리)

`POST /api/onboarding/kis/validate` (REAL 첫 등록) + `POST /api/onboarding/kis/mock` (MOCK 첫 등록)은 **SetupWizard 전용** (ADR 0046 §2-2 LOCK 정합). 본 ADR에서 변경 전용 endpoint 분리:

| 메서드 | 경로 | 책임 | SetupWizard 호출 가능? | SettingsModal 호출 가능? |
|---|---|---|---|---|
| `POST` | `/api/onboarding/kis/validate` | REAL 첫 등록 (기존, 미변경) | ✅ | ❌ (이미 등록 시 409 반환) |
| `POST` | `/api/onboarding/kis/mock` | MOCK 첫 등록 (기존, 미변경) | ✅ (Step 4) | ❌ (이미 등록 시 409 반환) |
| **`PUT` (신규)** | **`/api/onboarding/kis/{accountType}`** | **변경 전용** — 이미 row 존재 시 UPDATE | ❌ (SetupWizard에서 호출 X) | ✅ |
| **`GET` (신규)** | **`/api/onboarding/kis/credentials`** | **마스킹 응답** — 현재 등록된 키 표시 | ❌ | ✅ |
| **`DELETE` (신규)** | **`/api/onboarding/kis/{accountType}`** | **MOCK row 삭제** — `accountType=MOCK`만 허용 (REAL은 자기 자신 user_id로 금지) | ❌ | ✅ (이중 confirm — ADR 0069 §6 정합) |

**PUT 핵심 흐름** (`accountType=REAL` 예시):

```
[1] frontend KisCredentialSection submit
   ↓ PUT /api/onboarding/kis/REAL { appKey, secretKey } (accountNumber는 read-only)
[2] OnboardingService.updateKisCredential(userId, accountType=REAL, request)
   ↓
[3] KisAuthValidator.validate(appKey, secretKey, accountType=REAL)
   ↓ (KIS /oauth2/tokenP 호출 — 200 + access_token 수신 시 통과)
   ↓ 실패 시 → 401 + frontend toast "KIS 키 검증 실패 — KIS 콘솔에서 재발급" (평문 reason 미반환)
[4] AesCipher.encrypt(appKey) + AesCipher.encrypt(secretKey)
   ↓
[5] UPDATE user_kis_credential SET app_key_enc=?, secret_key_enc=?, last_validated_at=now()
   WHERE user_id=? AND account_type=?
   ↓
[6] KisAuthAdapter.invalidate(userId, accountType) — Redis DEL (§7 정합)
   ↓
[7] (account_type=REAL 시) WebSocket reconnect signal publish
   ↓ Kafka `system.kis-key-changed.v1` → consumer KisWebSocketBridge listen → graceful reconnect 1회
[8] Response 200 — { accountType, maskedAppKey, lastValidatedAt }
[9] frontend toast "KIS 키 변경 완료 — 새 토큰 발급됨" + section last_validated_at 갱신
```

**Atomicity LOCK** (ADR 0069 §6 정합): KIS OAuth tokenP 검증 통과 시에만 UPDATE. 검증 실패 시 기존 row 보존.

**계좌번호 변경 정책**: PUT request body에 `accountNumber` 포함 금지 — read-only. 계좌번호 변경 = 새 row (다른 user) → 별도 DELETE + POST 흐름 (1차 PR scope 외, backlog §H `account_prdt_cd` 흡수 의제로 분리).

#### 2-2-2. 마스킹된 현재 키 표시 (GET 신규)

ADR 0069 §6 마스킹 표시 매트릭스 정합:

| DTO 필드 | 마스킹 형식 | 평문 응답 |
|---|---|---|
| `accountNumber` | `7468****` (DB `masked_account` GENERATED column 직접 사용) | ❌ |
| `accountType` | `REAL` / `MOCK` (mask 불필요) | ✅ |
| `appKeyMasked` | 앞 4자 + `***` + 뒤 4자 — 예: `PS01***wxyz` (backend에서 복호화 후 mask 생성) | ❌ |
| `secretKeyMasked` | `********` (전부 마스킹, 비교 불가) | ❌ |
| `lastValidatedAt` | ISO-8601 instant | ✅ |
| `hasKey` | boolean | ✅ |

**`MaskedKisCredentialResponseDto`** 신규:

```kotlin
data class MaskedKisCredentialResponseDto(
    val userId: String,
    val credentials: List<MaskedKisCredentialItem>,
)

data class MaskedKisCredentialItem(
    val accountType: String,        // "REAL" | "MOCK"
    val accountNumber: String,       // "7468****" (masked_account GENERATED)
    val appKeyMasked: String,        // "PS01***wxyz"
    val secretKeyMasked: String,     // "********"
    val lastValidatedAt: Instant?,
    val hasKey: Boolean,             // false면 row 미등록
)
```

**보안 룰** (메모리 `feedback_secret_handling.md` 정합):
- 응답 body에 평문 appKey·secretKey 절대 미포함 (mask만)
- log INFO 메시지에도 평문 키 미출력 (ADR 0046 §2-5 `MaskingPatternLayout` 정합)
- frontend에서 mask 표시 후 "변경" 버튼 클릭 시 빈 input — 사용자가 다시 입력 (기존 키 자동 채움 금지)

#### 2-2-3. Active session invalidate (Redis + WS 재연결 + 5초 잠금)

ADR 0069 §6 active session 처리 LOCK + ADR 0070 §2-11 정합:

**Redis 캐시 invalidate 매트릭스**:

| Redis 키 | 영향 | invalidate 시점 |
|---|---|---|
| `access_token:{userId}:{accountType}` | KIS OAuth tokenP cache (KisRestPriceMetricsAdapter §138 정합) | PUT 성공 직후 즉시 DEL |
| `kis:approval:{userId}` | WS approval_key cache (KisApprovalAdapter 정합 — `consumer/ws/KisWebSocketBridge.kt` §37) | accountType=REAL PUT 성공 시 즉시 DEL |

**WS 재연결 흐름** (`accountType=REAL`만 대상 — MOCK은 WS 미사용):

```
[1] api 모듈 OnboardingService.updateKisCredential 성공
   ↓
[2] Kafka producer publish — system.kis-key-changed.v1
    {
      "eventId": "<uuid>",
      "userId": "<userId>",
      "accountType": "REAL",
      "occurredAt": "<instant>",
      "schemaVersion": 1
    }
   ↓
[3] consumer KisWebSocketBridge @KafkaListener("system.kis-key-changed.v1")
   ↓
[4] graceful reconnect — 기존 WS connection close → 0.5s 대기 → 신규 connection open
    (ADR 0048 §3 graceful reconnect 정합)
   ↓
[5] approval_key 재발급 (Redis cache DEL되어 있음 → KisApprovalAdapter가 /oauth2/Approval 호출)
   ↓
[6] 활성 구독 종목 재구독 (consumer KisWsSessionRegistry 정합)
```

**5초 잠금 LOCK** (ADR 0069 §6 backlog §D 흡수):
- PUT 성공 직후 backend Redis `SET kis:key-changed-lock:{userId}:{accountType} 1 EX 5`
- T-O OrderRouter에서 주문 발행 직전 Redis GET 후 lock 있으면 **즉시 REJECTED** (사유: "KIS 키 변경 5초 잠금 — 잠시 후 재시도")
- T-O ADR 0068 §15 OrderRouter 4 step에 본 lock 점검 step 추가 (1차 PR backend-core 산출 시 ADR 0068 후속 보강)

**Kafka 이벤트 신규 1건**:

| 토픽 | publish | listen | 보관 | 사이클 |
|---|---|---|---|---|
| `system.kis-key-changed.v1` (신규) | api (OnboardingService) | consumer (KisWebSocketBridge) | 7d (운영 감사 + 1주 추적) | 본 ADR 1차 PR |

### 2-3. §3 KillSwitch 사용자 수동 토글 section

#### 2-3-1. UI 위치 결정

**채택**: `SettingsModal Section 4: KillSwitchSection` (ADR 0069 §3 미래 확장 슬롯 §G 흡수)

**대안 검토**:

| 검토안 | 채택? | 사유 |
|---|---|---|
| (a) SettingsModal Section 4 (현 채택) | ✅ | ADR 0069 §3 미래 슬롯 §G 정합. 모달 내 통일 |
| (b) Risk 페이지 또는 Orders 페이지 우상단 토글 | 폐기 | backlog §46 정합 후속 추가 항목이지만 1차는 SettingsModal 통일 (UX 분산 risk) |
| (c) AppHeader 빨간 점멸 토글 (긴급 차단 버튼) | 폐기 | 잘못 클릭 risk + ADR 0043 §2-3 자동 OFF 0 LOCK 위반 (긴급 ON은 OK지만 OFF는 이중 confirm + 사유 입력 필수) |

#### 2-3-2. UX 매트릭스

```
┌──────────────────────────────────────────────────────────────┐
│ Section 4: 자본 보호 (KillSwitchSection)                       │
├──────────────────────────────────────────────────────────────┤
│  현재 상태: ● 활성 (ON)      —— 비활성 (OFF) 시 회색 점          │
│  사유: drift 자동 ON — 수량 diff ≥ 1주 (TOPIX:5930 + 3주)       │
│  토글된 시각: 2026-05-14T09:31:21+09:00                         │
│  토글된 주체: AUTO (BalanceReconcileJob)                        │
│                                                                │
│  [ KillSwitch OFF 해제 ]                                       │
│                                                                │
│  KIS HTS에서 잔고 직접 확인 후 OFF 해제하세요.                  │
│  자동 매매가 재개됩니다.                                        │
└──────────────────────────────────────────────────────────────┘
```

**상태별 UX**:

| `kill_switch_state.enabled` | UX |
|---|---|
| `true` (활성) | 빨간 점 + "활성 (ON)" + 사유 표시 + 토글된 시각 + 주체 표시 + `[ KillSwitch OFF 해제 ]` 버튼 (`ConfirmDangerToggle` 패턴 + 사유 입력 필수) |
| `false` (비활성) | 회색 점 + "비활성 (OFF)" + 마지막 OFF 시각 표시 (있으면) + `[ KillSwitch ON ]` 버튼 (`ConfirmDangerToggle` 패턴 + 사유 입력 필수) |

**이중 confirm + 사유 입력 LOCK** (ADR 0069 §6 정합 + ADR 0043 정합):
- `ConfirmDangerToggle` 컴포넌트 reuse (`frontend/src/components/settings/forms/ConfirmDangerToggle.tsx` — ADR 0069 §2-2 매트릭스 §3 컴포넌트, 1차 PR에서는 stub일 수 있으므로 본 사이클 frontend Phase에서 실구현)
- 사유 입력 필수 (`textarea` minLength=5 maxLength=500) — `kill_switch_state.reason` 컬럼 영속
- 60초 cooldown 안내 (ADR 0070 §2-11 LOCK 정합 — 1차 PR scope 외 backlog §46 의제 였으나 본 ADR에서 사용자 수동 토글 1차 합류)

#### 2-3-3. REST endpoint 매트릭스 (신규 2건)

| 메서드 | 경로 | 책임 | 요청 body | 응답 |
|---|---|---|---|---|
| `GET` | `/api/system-config/kill-switch` | 현재 상태 조회 | — | `KillSwitchStateResponse { enabled, reason, updatedAt, updatedBy }` |
| `PUT` | `/api/system-config/kill-switch` | 토글 (ON ↔ OFF) | `{ enabled, reason }` (둘 다 필수) | `KillSwitchStateResponse` (응답 동일) |

**`KillSwitchStateResponse` DTO**:

```kotlin
data class KillSwitchStateResponse(
    val enabled: Boolean,
    val reason: String?,                       // null 가능 (초기 등록 X)
    val updatedAt: Instant?,                   // null 가능 (초기)
    val updatedBy: String,                     // "AUTO" | "USER:<userId>" (ADR 0043 baseline)
)
```

**`PUT` 흐름**:

```
[1] frontend ConfirmDangerToggle confirm + 사유 입력 + 60초 cooldown check
   ↓ PUT /api/system-config/kill-switch { enabled: false, reason: "KIS HTS 확인 완료 — 잔고 일치" }
[2] api KillSwitchToggleController.toggle(userId, request)
   ↓
[3] KillSwitchTogglePort.toggle(userId, enabled, reason, updatedBy="USER:${userId}")
   ↓ UPDATE kill_switch_state SET enabled=?, reason=?, updated_at=now(), updated_by=?
   WHERE user_id=?
   ↓
[4] Kafka producer publish — killswitch.toggle.v1 (신규 또는 기존 killswitch.engaged.v1 reuse?)
   ↓
[5] 응답 200 — KillSwitchStateResponse
```

**Kafka 이벤트 결정**: 기존 `killswitch.engaged.v1` (ADR 0070 §2-11 정합)을 reuse — `engaged=false`도 publish 가능하도록 schema 확장. 별도 `killswitch.toggle.v1` 신설 X.

```json
{
  "eventId": "<uuid>",
  "userId": "<userId>",
  "engaged": false,                  // 토글 후 상태 (true=ON, false=OFF)
  "reason": "KIS HTS 확인 완료",
  "updatedBy": "USER:<userId>",      // AUTO=Reconcile, USER=수동
  "occurredAt": "<instant>",
  "schemaVersion": 1
}
```

ADR 0070 §2-11 baseline은 `engaged=true` only publish 가정 — 본 ADR에서 schema 확장 (`engaged: boolean` field로 일관). 기존 listener(SlackP0 dispatcher 등)는 `engaged=true` filter만 추가하여 false 이벤트는 무시 (또는 OFF 알림 송신 — backlog §49 후속 결정).

### 2-4. §4 OpenAI/Anthropic 키 카테고리

#### 2-4-1. env vs DB AES 결정

**채택**: **env 유지 + SettingsModal 입력 UI는 1차 PR 0건** (T-AI 사이클로 실구현 분리)

**대안 검토**:

| 검토안 | 채택? | 사유 |
|---|---|---|
| (a) env 단독 (현 상태) | ✅ 본 ADR 1차 | 사용자 수동 .env 편집 가능 (Step 0 셋업 시). T-AI 진입 전까지 운영 부담 0. env → DB 이관은 T-AI 사이클에서 입력 폼·section·migration 일괄 처리 |
| (b) DB AES + SettingsModal section 1차 합류 | 폐기 | T-AI 미사용 단계에서 입력 폼만 추가 = 데드 코드. ADR 0069 §4 5 step 정합 위반 risk (Step 1~5 모두 한 사이클 처리 의무) |
| (c) env + DB 이중 (env가 우선) | 폐기 | 갱신 path 두 곳 — 동기화 risk |

**본 ADR 1차 결정**:
- `MAGICJAR_AI_ENABLED=false` default 유지 (env baseline)
- `ANTHROPIC_API_KEY` env 유지 (`application.yml` §43 정합)
- SettingsModal `AiCredentialSection` 1차 PR scope 외 → **backlog §E1로 분리** (본 ADR 후속 ADR 0094+ 가칭)
- ADR 0069 §4 5 step 표준 절차 적용 매트릭스 사전 정합 의무만 본 ADR §11에 기록

#### 2-4-2. T-AI 진입 시 5 step 적용 매트릭스 (prereq)

T-AI 트랙 진입 시 ADR 0069 §4 5 step 표준 절차 적용 예시 (사전 정합):

| Step | 본 카테고리 구현 매트릭스 |
|---|---|
| Step 1 (DB) | Flyway VN — `ALTER TABLE system_config ADD COLUMN openai_api_key BYTEA, ADD COLUMN anthropic_api_key BYTEA` |
| Step 2 (port + 어댑터) | `domain/port/AiCredentialPort.kt` 신규 — `getOpenAiKey()`·`updateOpenAiKey(plain: String)`·동상 Anthropic. AES-256-GCM 어댑터 |
| Step 3 (REST) | `PUT /api/system-config/ai-credentials` + `GET /api/system-config/ai-credentials` — mask 응답 (`sk-...AbCd` 형식) |
| Step 4 (frontend forms) | `AiCredentialForm.tsx` + `SecretKeyInput` reuse |
| Step 5 (Section mount) | `<AiCredentialSection />` SettingsModal Section 5에 한 줄 추가 |

본 사전 정합으로 T-AI 사이클 시 ADR 0069 §4 절차 따르면 단순 적용 가능 — 본 ADR 1차 PR scope는 **Section 5 UI 0건** + **REST endpoint 0건** + **Flyway 0건**.

### 2-5. §5 DB Flyway V32 필요성 검토

#### 2-5-1. 결정 — Flyway V32 **0건 (테이블 변경 없음)**

| 영역 | 변경 필요? | 사유 |
|---|---|---|
| `user_kis_credential` | ❌ | ADR 0046 V25에서 이미 생성 (`app_key_enc`·`secret_key_enc`·`last_validated_at`·`masked_account` GENERATED) — PUT은 UPDATE만 |
| `kill_switch_state` | ❌ | V1 baseline에서 이미 생성 (`enabled`·`reason`·`updated_at`·`updated_by`) — PUT은 UPDATE만 |
| `system_config` | ❌ | OpenAI/Anthropic 키 카테고리는 T-AI 사이클로 분리 — 본 ADR 1차 column ALTER 0 |
| audit log 신설 (`kis_credential_audit_log`?) | ❌ | 본 1차 사이클 audit 의제 미흡 — backlog §M (KIS 키 변경 history 추적) 후속 |

**Flyway 신규 0건 LOCK** — backend-core PR이 DB migration 0건이면 정합 검증 부담 ↓.

#### 2-5-2. 향후 audit 의제 (backlog §M — 본 ADR 외)

KIS 키 변경 이력 추적 (누가·언제·왜 키 교체):

```sql
-- 후속 ADR (backlog §M)
CREATE TABLE kis_credential_audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_type VARCHAR(8) NOT NULL,   -- REAL | MOCK
    action VARCHAR(16) NOT NULL,         -- CREATE | UPDATE | DELETE
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(64) NOT NULL,     -- USER:<userId>
    user_agent TEXT,
    ip_address INET
);
```

→ multi-user 진입 시(미래) 본격 필요 — 본 ADR 1차 사이클은 1인 baseline (헌법 §1) 정합으로 미흡.

### 2-6. §6 REST endpoint 매트릭스 종합 (신규 5건)

| # | 메서드 | 경로 | 모듈 | 신규/기존 | Phase |
|---|---|---|---|---|---|
| 1 | `PUT` | `/api/onboarding/kis/{accountType}` | api | 신규 | Phase 1 backend-core |
| 2 | `GET` | `/api/onboarding/kis/credentials` | api | 신규 | Phase 1 backend-core |
| 3 | `DELETE` | `/api/onboarding/kis/{accountType}` | api | 신규 (MOCK만 허용) | Phase 1 backend-core |
| 4 | `GET` | `/api/system-config/kill-switch` | api | 신규 | Phase 1 backend-core |
| 5 | `PUT` | `/api/system-config/kill-switch` | api | 신규 | Phase 1 backend-core |

**Swagger 4단 LOCK** (메모리 `feedback_swagger_pattern.md` 정합):
- @Tag(컨트롤러) + @Operation(메서드) + @Parameter(파라미터) + @Schema(DTO) 4단
- 한국어 description + 200/400/401/404/409/503 ApiResponse 매트릭스
- example 값 포함

**OnboardingController vs SystemConfigController 분리**:
- `/api/onboarding/kis/*` — 기존 `OnboardingController`에 PUT·GET·DELETE 메서드 추가 (controller 신규 0)
- `/api/system-config/kill-switch` — **`SystemConfigController` 신규 발행** (T-AI 진입 시 ai-credentials도 동일 controller mount 가능 — extension point LOCK)

**인증 정책**:
- 본 ADR 1차 사이클 사용자 1인 baseline (헌법 §1) → OnboardingGuardFilter 통과 정책 reuse (PUT·GET·DELETE 모두 통과 허용)
- multi-user 진입 시(미래) 본격 `@PreAuthorize("hasRole('ADMIN')")` 적용 — backlog §M

### 2-7. §7 Redis 키 invalidate 패턴 LOCK

#### 2-7-1. KIS 키 변경 시 Redis 매트릭스

| Redis 키 | 패턴 | 영향 | invalidate 시점 | 책임 모듈 |
|---|---|---|---|---|
| `access_token:{userId}:{accountType}` | TTL 23h, KisRestPriceMetricsAdapter §138 | OAuth tokenP cache | PUT 성공 직후 즉시 `DEL` | api OnboardingService |
| `kis:approval:{userId}` | TTL 23h, KisApprovalAdapter | WS approval_key cache | accountType=REAL PUT 성공 시 즉시 `DEL` | api OnboardingService |
| `kis:key-changed-lock:{userId}:{accountType}` (신규) | TTL 5초, `SET ... EX 5` | T-O 주문 발행 잠금 | PUT 성공 직후 `SET` | api OnboardingService (set), consumer OrderRouter (get) |

**Redis 키 prefix 정책**:
- 기존 `access_token:*` 패턴은 prefix `kis:`가 누락 — ADR 0074에서 보강 0건 (호환성 유지 + 추후 backlog 의제)
- 신규 키 `kis:key-changed-lock:*`는 prefix `kis:` 포함 (정합 의제 우선)

#### 2-7-2. KillSwitch 토글 시 Redis 매트릭스

| Redis 키 | 영향 | invalidate 시점 |
|---|---|---|
| (기존 없음) | KillSwitch 상태는 DB `kill_switch_state` 단일 source — Redis cache 0건 (ADR 0043 §2-5 정합) | — |

KillSwitch 토글은 Redis 영향 0 — DB UPDATE + Kafka publish만으로 종료. consumer Listener가 직접 DB 조회로 일관성 유지 (T-O OrderRouter §15-2 KillSwitchQueryAdapter 정합).

### 2-8. §8 Frontend UI 매트릭스

#### 2-8-1. SettingsModal section 매트릭스 갱신 (ADR 0069 §3 → 본 ADR)

ADR 0069 §3 1차 PR scope (3 section) → 본 ADR로 풀세트 확장 (Section 2 submit 활성화 + Section 4 신설):

```
┌──────────────────────────────────────────────────────────────┐
│ SettingsModal — 운영 변경 화면 (본 ADR v2)                      │
├──────────────────────────────────────────────────────────────┤
│ Section 1: 실거래 모드 동의 (RealTradingConsentSection)         │
│   - 기존 (PR #389 머지 baseline)                              │
├──────────────────────────────────────────────────────────────┤
│ Section 2: KIS 키 관리 (KisCredentialSection)                  │
│   - REAL 키 변경 submit 활성화 ★ (본 ADR Phase 2)              │
│   - MOCK 키 추가·변경 submit 활성화 ★                          │
│   - MOCK 키 DELETE 버튼 추가 ★ (이중 confirm)                  │
│   - GET /credentials 호출 — 마스킹 표시 ★                      │
│   - last_validated_at 표시 + "재검증" 버튼 (기존)              │
├──────────────────────────────────────────────────────────────┤
│ Section 3: 시스템 설정 (SystemConfigSection)                   │
│   - 기존 (PR #389 머지 baseline)                              │
├──────────────────────────────────────────────────────────────┤
│ Section 4: 자본 보호 (KillSwitchSection) ★ 신설                 │
│   - 현재 상태 (ON/OFF 점 + 사유 + 토글 시각 + 주체)              │
│   - 토글 버튼 (ConfirmDangerToggle + 사유 입력 + 60초 cooldown) │
├──────────────────────────────────────────────────────────────┤
│ Section 5: AI 키 (AiCredentialSection)                         │
│   - 본 ADR 1차 PR scope 외 — T-AI 사이클 진입 시 합류            │
└──────────────────────────────────────────────────────────────┘
```

#### 2-8-2. 컴포넌트 파일 매트릭스

| 컴포넌트 | 위치 | 본 ADR 작업 | Phase |
|---|---|---|---|
| `KisCredentialSection.tsx` | `frontend/src/components/settings/sections/` | submit 활성화 + GET 호출 + DELETE 버튼 추가 | Phase 2 frontend |
| `KillSwitchSection.tsx` | `frontend/src/components/settings/sections/` | **신규 발행** — 현재 상태 + 토글 버튼 | Phase 2 frontend |
| `ConfirmDangerToggle.tsx` | `frontend/src/components/settings/forms/` | 1차 stub 가능성 — 본 ADR Phase 2에서 실구현 + 사유 입력 + 60초 cooldown | Phase 2 frontend |
| `useUpdateKisCredential.ts` | `frontend/src/api/queries/` | **신규 hook** — PUT mutation + onSuccess invalidate `useKisCredentials` | Phase 2 frontend |
| `useKisCredentials.ts` | `frontend/src/api/queries/` | **신규 hook** — GET query | Phase 2 frontend |
| `useDeleteKisCredential.ts` | `frontend/src/api/queries/` | **신규 hook** — DELETE mutation | Phase 2 frontend |
| `useKillSwitchState.ts` | `frontend/src/api/queries/` | **신규 hook** — GET query (polling 옵션 30s) | Phase 2 frontend |
| `useToggleKillSwitch.ts` | `frontend/src/api/queries/` | **신규 hook** — PUT mutation | Phase 2 frontend |

#### 2-8-3. UX 룰 LOCK (ADR 0069 §6 정합)

- KIS 키 변경 시 frontend mask 표시 → "변경" 버튼 클릭 시 input 비움 (자동 채움 금지)
- secretKey input — `SecretKeyInput.tsx` reuse (autocomplete=off + unmount 시 zero-fill)
- 변경 성공 토스트 + WS 재연결 안내 ("새 토큰 발급 중 — 5초 후 자동 재개")
- KillSwitch 토글 ON·OFF 모두 사유 입력 필수 + 이중 confirm + 60초 cooldown 안내
- 모든 mutation onSuccess + invalidate query (useKisCredentials·useKillSwitchState)

### 2-9. §9 Phase 분리 LOCK (직렬 의무)

ADR 0070 Phase 분리 LOCK 패턴 정합 (`feedback_orchestrator_active_parallel.md` add-only 충돌 행렬 검토 결과 — 본 ADR도 직렬 필수):

| Phase | sub-agent | 영역 | 산출 | 차단 의제 |
|---|---|---|---|---|
| **Phase 1** | **backend-core** | api + domain | OnboardingController PUT·GET·DELETE 메서드 + OnboardingService 변경 흐름 + SystemConfigController 신규 + KillSwitchTogglePort (이미 도메인 존재 — 재사용) + Kafka producer `system.kis-key-changed.v1` + Redis invalidate 헬퍼 + Swagger 4단 | — |
| **Phase 2** | **frontend** | frontend SPA | KisCredentialSection submit 활성화 + KillSwitchSection 신규 + ConfirmDangerToggle 실구현 + 5 hook 추가 + 통합 verify | Phase 1 endpoint 머지 후만 진행 |
| **Phase 3** | (optional) **consumer** | consumer | KisWebSocketBridge `system.kis-key-changed.v1` @KafkaListener 추가 — graceful reconnect | Phase 1 후 add-only 가능 — Phase 2와 동시 가능 |

**병렬 가능성 검토** (`feedback_orchestrator_active_parallel.md` 정합):
- Phase 1·Phase 3 add-only 충돌 0 — 동시 가능 (backend-core + consumer 병렬 디스패치 OK)
- Phase 2(frontend)는 Phase 1 endpoint 머지 후만 진행 (의존성)

**Decision**: orchestrator가 본 ADR 발행 후 Phase 1 + Phase 3 **병렬 디스패치 가능** + Phase 2 직렬 (Phase 1 후).

### 2-10. §10 검증 시나리오 (3건)

#### 2-10-1. KIS 키 변경 시나리오 (REAL 키 교체)

```
[가정] 사용자 KIS REAL 키 만료 → KIS 콘솔에서 재발급
[1] dashboard → AppHeader ⚙️ → SettingsModal open
[2] Section 2 KisCredentialSection → REAL row 노출 ("PS01***wxyz")
[3] "변경" 버튼 click → input 빔
[4] 새 appKey·secretKey 입력 + submit
[5] PUT /api/onboarding/kis/REAL → KIS tokenP 검증 → 200
[6] backend: Redis access_token DEL + kis:approval DEL + kis:key-changed-lock SET 5초
[7] backend: Kafka system.kis-key-changed.v1 publish
[8] consumer: KisWebSocketBridge WS 재연결 graceful (0.5s 대기 → reconnect → 종목 재구독)
[9] frontend toast "KIS 키 변경 완료 — 새 토큰 발급됨"
[10] Section 2 last_validated_at 갱신
[검증] 5초 잠금 — T-O OrderRouter 주문 시도 시 즉시 REJECTED
[검증] Section 2 GET 재호출 → maskedAppKey 갱신 (새 PS키 prefix)
```

#### 2-10-2. KillSwitch 사용자 수동 OFF 시나리오

```
[가정] T-RECON drift 자동 ON → kill_switch_state.enabled=true (reason="drift detected — TOPIX:5930 +3주")
[1] 사용자 Slack P0 알림 수신 (모바일) → KIS HTS 직접 확인 → 잔고 일치 확인
[2] dashboard → AppHeader ⚙️ → SettingsModal open
[3] Section 4 KillSwitchSection → 활성 (ON) 빨간 점 + 사유 표시
[4] [KillSwitch OFF 해제] 버튼 click
[5] ConfirmDangerToggle → "정말 해제하시겠습니까?" confirm
[6] 사유 입력: "KIS HTS 잔고 확인 완료 — 일치" (minLength 5)
[7] 60초 cooldown 안내 표시 → 60초 후 [OFF 확정] 활성화
[8] PUT /api/system-config/kill-switch { enabled: false, reason: "..." }
[9] backend: UPDATE kill_switch_state SET enabled=false, reason="...", updated_by="USER:<userId>"
[10] backend: Kafka killswitch.engaged.v1 { engaged: false, ... } publish
[11] frontend toast "KillSwitch 해제 완료 — 자동 매매 재개됨"
[12] Section 4 회색 점 + "비활성 (OFF)" 표시
[검증] T-O OrderRouter 다음 주문 시도 시 KillSwitchQueryAdapter step 통과 (REJECTED 안 됨)
```

#### 2-10-3. AI 키 입력 후 T-AI 진입 검증 (본 ADR 1차 scope 외 — 사전 정합)

```
[가정] 본 ADR 1차 PR scope 외 — T-AI 사이클 진입 시 검증 의제
[1] T-AI 사이클 Phase X 사용자 SettingsModal Section 5 AiCredentialSection 진입
[2] OpenAI 키 입력 (sk-...) + Anthropic 키 입력 + 검증 버튼
[3] backend: anthropic API ping (cheap call) + openai API ping → 200 시 통과
[4] PUT /api/system-config/ai-credentials → Flyway VN ALTER 정합
[5] Section 5 마스킹 표시 (sk-...AbCd)
[검증] T-AI 사이클 LLM 호출 시 DB AES 키 reuse (env 우선순위 X — T-AI 사이클 결정)
```

### 2-11. §11 한계 + 별도 backlog 흡수

#### 2-11-1. 본 ADR 1차 PR scope 외 (별도 backlog 잔류)

ADR 0069 §7 backlog 의제 10건 중 본 ADR 1차에서 흡수 2건 (§E·§G) + 잔류 4건:

| 의제 | 상태 |
|---|---|
| §F Naver 뉴스 API 키 | backlog §45 잔류 (T-NEWS 트랙 진입 시) |
| §H account_prdt_cd 변경 | backlog §45 잔류 (사용자 선물옵션 필요 시) |
| §I display_name 변경 | backlog §45 잔류 (multi-user 진입 시) |
| §J `/settings` route 보존 | backlog §45 잔류 (모바일 진입 시 모달 vs 페이지 재논의) |

ADR 0069 §7 추가 잔류:
| 의제 | 상태 |
|---|---|
| §A KIS MOCK 키 DELETE | **본 ADR 1차 흡수** (§2-2-1 DELETE endpoint 추가) |
| §B RealTradingConsentToggle 내부 리팩토링 | backlog §45-B 잔류 (별도 PR) |
| §C 마스터 키 회전 자동화 | ADR 0091+ 후속 (변동 없음) |
| §D 키 변경 직후 주문 발행 잠금 | **본 ADR 1차 흡수** (§2-2-3 5초 잠금) |
| §E OpenAI/Anthropic 키 입력 | **본 ADR 사전 정합 + T-AI 사이클 실구현 분리** |
| §G KillSwitchSection | **본 ADR 1차 흡수** (§3 신설) |

#### 2-11-2. AI 호출 0건 grep 검증 의무 (ADR 0070·0071·0072 정합)

본 ADR 영역(KIS 키 변경 + KillSwitch + AI 키 카테고리 사전 정합)에서:

```bash
grep -rn "anthropic\|openai\|claude" \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/OnboardingController.kt \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/OnboardingService.kt \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemConfigController.kt
# 기대: 0건 (AI provider import 0)
```

본 ADR 1차 PR sub-agent (backend-core + frontend + consumer) 모두 LLM provider import 금지. AI 키 카테고리는 사전 정합만이고 실구현은 T-AI 사이클로 분리.

#### 2-11-3. mock 합성 0건 (메모리 `feedback_no_mock_data.md` 정합)

- backend 0건 또는 에러 시 frontend "데이터 없음" 명시 표시 (KillSwitchSection 초기 row 없을 때 "최초 토글 없음" 표시 + ON 버튼만 노출)
- KIS 키 mask 응답에서 row 미등록 시 `hasKey: false` + UI "등록된 키 없음" 표시 — placeholder mask 금지

### 2-12. §12 Rollback 시나리오

#### 2-12-1. Phase 1 머지 후 결함 발견 시

**시나리오**: PUT endpoint 활성화 후 KIS 키 변경 시 Redis invalidate 실패 → WS 재연결 무한 루프

**Rollback path**:
1. Phase 1 PR revert (`git revert <merge-sha>`) → OnboardingController PUT/GET/DELETE + SystemConfigController + Kafka producer 모두 원복
2. frontend Phase 2는 아직 머지 X (직렬 의무 §9 LOCK) → 영향 0
3. dogfooding 검증 사이클 재진입 — 새 PR 발행으로 보강

**Risk 자기 점검**:
- DB Flyway 0건 LOCK (§5) → migration 회복 부담 0
- Redis 키 새로 발행 1건(`kis:key-changed-lock:*`) → revert 후 키 자연 만료 (5초 TTL) — 영향 0

#### 2-12-2. Phase 2 머지 후 결함 발견 시

**시나리오**: KillSwitchSection 토글 UX 결함 — ConfirmDangerToggle cooldown 60초가 frontend mount 직후 즉시 활성화되어 무방어 토글 가능

**Rollback path**:
1. Phase 2 PR revert → KillSwitchSection·5 hook·UX 변경 모두 원복
2. backend Phase 1은 머지 상태 유지 → 사용자가 Postman/curl 등 외부 도구로 PUT 직접 호출 가능 (1인 baseline에서는 차선책)
3. frontend 보강 PR 신규 발행

#### 2-12-3. 부분 rollback 매트릭스

| 산출물 | 단독 revert 가능? |
|---|---|
| OnboardingController PUT/GET/DELETE | ✅ (메서드 단위 revert) |
| SystemConfigController | ✅ (controller 단독 revert) |
| Kafka producer `system.kis-key-changed.v1` | ✅ (consumer @KafkaListener도 동시 revert 권장 — 미사용 토픽 publish는 영향 0이지만 listen은 잔재) |
| KillSwitchSection | ✅ (Section 4 mount만 revert) |
| ConfirmDangerToggle 실구현 | ❌ (RealTradingConsentSection이 이미 의존 — careful) |

→ ConfirmDangerToggle 결함 시 RealTradingConsentSection 회귀 risk → 보강 PR 발행 권고 (revert 금지).

## 3. 결과·영향

### 3-1. 트랙 영향

- **본 ADR = ADR 0069 §3 1차 PR scope 후속 풀세트** — 트랙 신설 X
- ADR 0069 §7 backlog 의제 흡수 — §A KIS MOCK DELETE, §D 5초 잠금, §G KillSwitchSection 1차 흡수 + §E AI 키 사전 정합
- **자본 보호 흐름 종착** — ADR 0070 §2-11 KillSwitch auto-ON + 본 ADR §3 사용자 수동 OFF = 단일 사이클 완성
- W4 T-AI 진입 path 사전 정합 — T-AI 사이클에서 ADR 0069 §4 5 step 단순 적용

### 3-2. 컨테이너·모듈 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | OnboardingController PUT·GET·DELETE 메서드 추가(controller 단독 1) + SystemConfigController 신규(KillSwitch 전용) + OnboardingService 변경 흐름 + KillSwitchToggleAdapter 재사용(이미 존재) + KisAuthValidator 재사용 + Redis invalidate 헬퍼 + Kafka producer `system.kis-key-changed.v1` + Swagger 4단 |
| `consumer` | KisWebSocketBridge `system.kis-key-changed.v1` @KafkaListener 추가 — graceful reconnect 1회 |
| `domain` | 변경 0건 (KillSwitchTogglePort + KIS credential port 모두 baseline reuse) |
| `frontend` | KisCredentialSection submit 활성화 + KillSwitchSection 신규 + ConfirmDangerToggle 실구현(1차 stub 있을 시) + 5 hook + UX 룰 정합 |
| `batch` | 영향 0건 |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| KIS 키 변경 빈도 | log INFO (관리자=사용자 본인 익명화 불요. 평문 키 절대 미포함 — ADR 0046 §2-5 정합) |
| KIS 키 변경 후 KisAuthValidator 검증 실패 | log WARN + frontend toast "검증 실패 — KIS 콘솔에서 키 재발급" |
| WS 재연결 결함 (Kafka system.kis-key-changed.v1 consumer 처리 실패) | log ERROR + Slack alert |
| KillSwitch 사용자 수동 OFF 빈도 | log INFO + Slack 통보(선택 — backlog §49) |
| 5초 잠금 hit 빈도 | metrics Counter (T-O OrderRouter 정합 — backlog 의제) |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) AI 키 카테고리 본 ADR 1차 PR에서 실구현 합류 | 폐기 | T-AI 미진입 단계 데드 코드 risk + ADR 0069 §4 5 step 한 사이클 의무 위반 |
| (b) AI 키 사전 정합만 본 ADR (현 채택) | ✅ | T-AI 사이클 진입 시 단순 적용 가능 |
| (c) `killswitch.engaged.v1` schema 확장 (`engaged: boolean`) | ✅ | 별도 토픽 신설 회피 + 기존 listener (Slack P0) filter 추가 단순 |
| (d) `killswitch.toggle.v1` 토픽 신설 | 폐기 | (c) 채택으로 회피 — 토픽 fragmentation 회피 |
| (e) Flyway V32 audit log 추가 | 폐기 | multi-user 진입 전 의제 — 1인 baseline에서 audit 가치 ↓, backlog §M 분리 |
| (f) AppHeader 별도 KillSwitch 빨간 점멸 버튼 (긴급 차단) | 폐기 | 잘못 클릭 risk + ADR 0069 §3 SettingsModal 통일 정합 위반 |
| (g) 5초 잠금 Redis vs DB 분기 | Redis 채택 | T-O 주문 발행 hot path에서 DB 부담 회피 — Redis sub-ms |
| (h) KIS REAL 키 DELETE 허용 | 폐기 | 자기 자신 user_id로 row 삭제 = 시스템 진입 불가 risk. REAL은 변경만 허용 (UPDATE), DELETE는 MOCK만 |
| (i) KillSwitch 토글 사유 maxLength | 500자 채택 | reason 컬럼 충분 + UI textarea 자연 |
| (j) 60초 cooldown frontend vs backend | frontend 채택 | backend 강제 시 토글 UX 부담↑ — 1인 baseline에서 사용자 의도 신뢰 + frontend 보호만 충분 |

## 4. 후속 ADR 후보

- **ADR 0094+ (가칭)** — AI 키 카테고리 실구현 (T-AI 사이클 진입 시 — env → DB AES 이관 + Section 5 mount + Flyway VN ALTER)
- **ADR 0095+ (가칭)** — KIS credential audit log (multi-user 진입 시 backlog §M 흡수)
- **ADR 0096+ (가칭)** — Naver 뉴스 API 키 카테고리 (T-NEWS 트랙 진입 시 backlog §45 §F 흡수)
- **ADR 0097+ (가칭)** — `account_prdt_cd` 변경 path (사용자 선물옵션 필요 시 backlog §45 §H 흡수)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-14 | **ADR 0074 신규 발행 (재발행)** — SettingsModal 풀세트 — KIS 키 변경 풀세트(PUT·GET·DELETE + 5초 잠금 + WS 재연결) + KillSwitch 수동 토글 section(GET·PUT, ConfirmDangerToggle reuse, 60초 cooldown) + OpenAI/Anthropic 키 카테고리 사전 정합(T-AI 분리). REST endpoint 신규 5건 + Kafka 신규 1(system.kis-key-changed.v1) + killswitch.engaged.v1 schema 확장(engaged: boolean). Flyway 0건 LOCK. Phase 분리 LOCK(Phase 1 backend-core + Phase 3 consumer 병렬 + Phase 2 frontend 직렬). ADR 0069 §7 backlog 흡수 §A·§D·§G. v3.6.5 patch 사이클. **재발행 사유**: 직전 architect dispatch 시 ADR 번호 충돌 — T-NEWS(PR #414 머지됨, ADR 0073 점유)와 본 사이클(PR #415 close됨) 둘 다 ADR 0073으로 발행되어 PR #415 close. 본 ADR은 0074로 재발행 + cross-ref 정합(ADR 0073=T-NEWS 점유). spec 본문 + 결정 내용 모두 close된 PR #415 (`agent/architect/settings-kis-change-spec` branch 보존)와 동일 — 번호만 갱신. (architect) |
