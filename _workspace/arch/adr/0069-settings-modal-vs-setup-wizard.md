# ADR 0069 — SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 추출 + 미래 확장 구조

- 상태: Accepted
- 작성일: 2026-05-13
- 결정자: architect (사용자 명시 정책 — dogfooding 결함 발견 응답 2026-05-13)
- 관계: ADR 0046 보강(셋업 마법사 7-step + AES 암호화) · ADR 0068 §16-4 정합(RealTradingConsentToggle 이중 confirm UI) · ADR 0044 정합(Slack webhook 저장 위치) · 메모리 `project_kis_key_management.md` 정합 · 메모리 `feedback_kis_key_realonly_for_data.md` 정합 · 헌법 §1 사용자 = 최고 관리자 1인

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0046이 `system_config` + `user_account` + `user_kis_credential` 3 테이블 + 셋업 마법사 7-step + REST 5 endpoint를 LOCK 했지만, **운영 변경 화면(SettingsModal)의 책임 경계·구조·확장 패턴은 미정의**. 2026-05-13 dogfooding 진입 시 사용자(최고 관리자)가 ⚙️ 진입점 부재 직접 확인:

| 발견 항목 | 현재 상태 | 영향 |
|---|---|---|
| `frontend/src/components/settings/` 폴더 | `RealTradingConsentToggle.tsx` 1개만 존재 (208~) | 컴포넌트는 있지만 mount 위치 0건 |
| `SettingsModal.tsx` | 부재 — 파일 자체 없음 | ⚙️ 진입점·layout·section 골격 0건 |
| `AppHeader.tsx` 진입점 | NavLink 9개(대시보드·관심종목·전략 빌더·백테스트·전략 목록·리스크 설정·위반 이력·포지션·주문) — ⚙️ 0건 | T-O Phase 3 (ADR 0068 §16-4)에서 SettingsModal "통합 의무" 명시 → 누락 |
| `RealTradingConsentToggle.tsx` | `// SettingsModal에 통합하여 사용` 주석만 | T-O 자본 보호 검증(케이스 1·2·3) 전제 조건 접근 불가 |

→ **자본 보호 3중 게이트 세 번째 조건(`real_trading_consent_at`)이 UI에서 토글 불가능 상태** — T-O 사이클이 컴포넌트는 만들었지만 통합을 누락하여 dogfooding 차단.

**사용자 명시 정책 (2026-05-13 직접 응답)**:

> "풀세트로 해야하는데 중복되는거 확실히 제거되어야하며, SetupWizard와 충돌을 일으키지 않게끔 고려 잘해야함. SetupWizard은 베이스이면 셋업모달에서 입력할것들 신규 키나 API키로서 쓸게 추가될게 많기때문에 처음에 구조를 잘 잡아야해"

핵심 의제 3건:

1. **SetupWizard와 SettingsModal 책임 분리** — SetupWizard=베이스(첫 셋업), SettingsModal=변경/추가 운영용
2. **중복 제거 의무** — KIS 키 입력 폼 등 공통 입력 컴포넌트 추출
3. **미래 확장성** — 신규 API 키(예: OPENAI·DART·NAVER 키 등) 추가 시 표준 절차 LOCK

**필요한 결정**:

- §1 책임 분리 매트릭스 (SetupWizard ⊥ SettingsModal — 어떤 항목이 첫 셋업 전용 / 운영 변경 가능 / 둘 다)
- §2 공통 입력 컴포넌트 추출 매트릭스 (KIS 키 입력 폼·section header·confirm 토글·secret input)
- §3 SettingsModal 1차 scope (RealTradingConsentToggle + KIS 키 변경) + 미래 슬롯
- §4 신규 API 키 추가 표준 절차 (등록·검증·암호화·표시)
- §5 DB·암호화 정합 (AES 마스터 키 보호 + 테이블 매핑)
- §6 UX 룰 (⚙️ AppHeader 진입점·layout·이중 confirm·키 변경 시 active session 처리)
- §7 backlog 의제 (1차 외 future 항목 — OPENAI·DART·NAVER 키 카테고리 추가 시점)

## 2. 결정

### 2-1. §1 책임 분리 매트릭스 (SetupWizard ⊥ SettingsModal LOCK)

**원칙 (사용자 명시)**: SetupWizard는 **첫 진입 1회만**, SettingsModal은 **운영 중 언제든 호출**. 두 컴포넌트가 같은 입력 화면을 들고 다니면 분기·테스트 부담 누적 → **공통 입력 컴포넌트 추출 + wrapper 분리**.

| 항목 | SetupWizard (베이스) | SettingsModal (운영) | 사유 |
|---|---|---|---|
| **KIS 실전 키 (`user_kis_credential.account_type=REAL`)** | 필수 (Step 1) — 미등록 시 시스템 진입 불가 | 변경 (재발급·교체) | 첫 등록은 SetupWizard 의무 → 이후 변경은 SettingsModal |
| **KIS 모의 키 (`account_type=MOCK`)** | 선택 (Step 3) — 건너뛰기 가능 | 추가·변경·삭제 | 선택 등록·delete 모두 운영 작업 |
| **DART API 키 (`system_config.dart_api_key`)** | 선택 (Step 4) | 추가·변경·삭제 | 동상 — 1차 SetupWizard 미입력 시 SettingsModal에서 채움 |
| **Slack webhook (`system_config.notification_slack_webhook`)** | 선택 (Step 4) | 추가·변경·삭제 | 동상 |
| **실거래 동의 (`user_account.real_trading_consent_at`)** | ❌ 미노출 | 토글 (이중 confirm) | SetupWizard 단계에서 ON 시 자본 보호 게이트 무력화 risk — **운영 진입 후 명시 토글만 허용** (T-O ADR 0068 §16-4 LOCK 정합) |
| **`account_prdt_cd` 변경 (종합/연금/선물옵션)** | ❌ default `01` 자동 | 향후 추가 (선택) | 1차 default 운영 — 향후 SettingsModal slot |
| **사용자 닉네임 (`user_account.display_name`)** | 입력 (Step 1) | 변경 | 단순 라벨 — UI 표시용 |
| **마스터 키 (`MAGICJAR_SECRET_MASTER_KEY` env)** | ❌ 부팅 직전 사용자가 .env 직접 편집 | ❌ 화면 등록 절대 금지 | 자기 자신 암호화 불가 (ADR 0046 §2-4) — env 단일 예외 LOCK 유지 |
| **미래 OPENAI·NAVER 키 (예시)** | ❌ — SetupWizard 확장 금지 | ✅ slot 추가 (§4 표준 절차) | SetupWizard는 시스템 진입 최소 요건만 유지 — 부가 키는 SettingsModal slot 표준화 |

**핵심 분기 LOCK**:

- **SetupWizard 진입 trigger**: `GET /api/onboarding/status` 응답에서 `hasUserAccount === false` (ADR 0046 §2-2 Step 1) → `/setup` 라우트 자동 라우트
- **SettingsModal 진입 trigger**: `hasUserAccount === true` 운영 상태에서 AppHeader ⚙️ 버튼 클릭 — 항상 mount된 dashboard 위에 modal overlay
- **두 컴포넌트 동시 mount 금지**: SetupWizard 라우트(`/setup`)에서는 AppHeader 비표시(`App.tsx`가 라우트 기반 제어, ADR 0046 §2-2 정합) → ⚙️ 버튼 자체 미노출 → 동시 mount 불가

**경로 분기 다이어그램**:

```
첫 진입 (사용자 새 설치)
   │
   ▼
GET /api/onboarding/status
   │
   ├── hasUserAccount=false → SetupWizard(/setup) → 5 step → POST /onboarding/complete → "/"
   │                                                                                       │
   │                                                                                       ▼
   └── hasUserAccount=true ─────────────────────────────────────────────► Dashboard ("/")
                                                                                          │
                                                                                          ▼
                                                                              AppHeader ⚙️ click
                                                                                          │
                                                                                          ▼
                                                                              SettingsModal overlay
                                                                              (5 section — §3)
```

### 2-2. §2 공통 입력 컴포넌트 추출 매트릭스 (중복 제거 LOCK)

**현재 중복 risk** (SetupWizard Step 1·Step 3 + SettingsModal 동일 KIS 키 입력 폼 운영 시):

| 중복 영역 | SetupWizard 위치 | SettingsModal 운영 시 중복 risk |
|---|---|---|
| KIS 계좌번호 8자리 + 정규식 `/^\d{8}$/` | `KisRealStep.tsx` L100~119 + `KisMockStep.tsx` L126~141 | 동일 검증 로직 3중 발생 |
| App Key + Secret Key (password input + autocomplete=off + unmount 시 zero-fill) | `KisRealStep.tsx` L142~177 + `KisMockStep.tsx` L143~180 | 보안 룰(ADR 0046 §2-5) 3중 발생 + 룰 갱신 시 동기화 누락 risk |
| DART API key + Slack webhook URL (https URL pattern) | `SystemConfigStep.tsx` L79~116 | SettingsModal 동일 입력 발생 시 2중 |
| 이중 confirm UX | `RealTradingConsentToggle.tsx` L37~55 | 향후 다른 위험 토글(예: AI auto-trade 가능 시) 추가 시 패턴 재사용 |

**추출 컴포넌트 6종 매트릭스** (`frontend/src/components/settings/forms/` 신규 폴더 + section/forms 분리):

| 컴포넌트 | 위치 | 책임 | SetupWizard 재사용 | SettingsModal 재사용 |
|---|---|---|---|---|
| **`KisCredentialForm`** | `forms/KisCredentialForm.tsx` | KIS 계좌번호 + appKey + secretKey 입력 + 8자리 검증 + autocomplete=off + unmount 시 zero-fill — `accountType: 'REAL' \| 'MOCK'` prop으로 분기 | KisRealStep + KisMockStep | KisCredentialSection (REAL 변경 / MOCK 추가) |
| **`SecretKeyInput`** | `forms/SecretKeyInput.tsx` | type=password + autocomplete=off + ref 기반 unmount 시 input value zero-fill 헬퍼 | KisCredentialForm 내부 | SystemConfigSection (DART/Slack) 내부 |
| **`SettingsSection`** | `SettingsSection.tsx` | section header + 본문 wrapper + 변경 사항 status (저장 됨·변경 중·실패) | ❌ (Step 단위) | 모든 section common |
| **`ConfirmDangerToggle`** | `forms/ConfirmDangerToggle.tsx` | 이중 confirm UX 추상화 + danger 토글 layout (`RealTradingConsentToggle`가 이를 사용하도록 리팩토링) | ❌ | RealTradingConsentSection 내부 (향후 자동매매 ON 토글 등 동종 패턴 재사용) |
| **`SystemConfigForm`** | `forms/SystemConfigForm.tsx` | DART + Slack URL 입력 + https 검증 | SystemConfigStep | SystemConfigSection |
| **`SettingsModal`** | `SettingsModal.tsx` | overlay modal frame + section 컨테이너 + 닫기 버튼 + Esc 키 처리 | ❌ (단독) | 진입점 |

**SetupWizard 리팩토링 범위**:

- `KisRealStep.tsx` — `KisCredentialForm` 사용으로 단순화 (입력 검증 로직은 forms로 이관). step layout(헤더·진행률) + 검증 mutation 결합은 step 단독 유지.
- `KisMockStep.tsx` — 동상.
- `SystemConfigStep.tsx` — `SystemConfigForm` 사용으로 단순화.
- `RealTradingConsentToggle.tsx` — `ConfirmDangerToggle` 사용하도록 wrapper 변환 (1차 PR에서는 위치만 SettingsModal로 이동, 내부 로직 리팩토링은 2차 PR — backlog §B 후속).

### 2-3. §3 SettingsModal 1차 scope + 미래 확장 슬롯

**1차 scope (이번 사이클 PR)** — 사용자 자본 보호 검증 prereq + 최소 운영 변경:

```
┌──────────────────────────────────────────────────────────────┐
│ SettingsModal — 운영 변경 화면                                  │
├──────────────────────────────────────────────────────────────┤
│ Section 1: 실거래 모드 동의 (RealTradingConsentSection)         │
│   - RealTradingConsentToggle 본문 (이중 confirm)              │
│   - 1차 자본 보호 검증 prereq (T-O dogfooding 케이스 1·2·3)    │
├──────────────────────────────────────────────────────────────┤
│ Section 2: KIS 키 관리 (KisCredentialSection)                  │
│   - REAL 키 변경 (계좌번호 read-only + appKey·secretKey 재입력) │
│   - MOCK 키 추가·변경 (KisCredentialForm accountType=MOCK)     │
│   - last_validated_at 표시 + "재검증" 버튼                      │
├──────────────────────────────────────────────────────────────┤
│ Section 3: 시스템 설정 (SystemConfigSection)                   │
│   - DART API key (등록·변경·삭제)                              │
│   - Slack webhook URL (등록·변경·삭제 + test message 발송)     │
└──────────────────────────────────────────────────────────────┘
```

**미래 확장 슬롯** (1차 PR scope 밖 — backlog 의제 §B로 분리):

| 슬롯 | 책임 | 예상 진입 시점 |
|---|---|---|
| `OpenAiKeySection` | AI 트리거 분석 보조 (ADR 0026) 키 입력 — 표 §4 절차 정합 | T-AI 트랙 진입 시 (W4) |
| `NaverApiSection` | 종목 뉴스·메타 정보 (선택 부가 출처 — KIS-only 정책 ADR 0057 LOCK 위반 안 함, 보조 정보용) | 사용자 요청 시 |
| `KillSwitchSection` | KillSwitch 상태 표시 + 수동 해제 (ADR 0043) | T-J + T-RISK 통합 시 |
| `AccountPrdtCdSection` | `user_kis_credential.account_prdt_cd` 변경 (종합/연금/선물옵션) | 사용자가 선물옵션 필요 시 (ADR 0046 §2-1 §C 정합) |
| `AccountDisplayNameSection` | `user_account.display_name` 변경 | 다중 user 보유 시 (현재 1인 baseline — backlog §16) |
| `KisCredentialDeleteSection` | MOCK 키 삭제 (`account_type=MOCK` row DELETE) — REAL 삭제는 금지(자기 자신 user_id) | 1차에서 변경만 허용 → 삭제는 2차 PR |
| `MasterKeyRotationSection` | AES 마스터 키 회전 (현재 수동 — backlog 의제 §C) | ADR 0091+ 후속 |

### 2-4. §4 신규 API 키 추가 표준 절차 LOCK (확장 패턴)

미래 추가될 API 키(예: OpenAI·Naver·기타)를 SettingsModal에 추가할 때 일관된 절차 따라야 함. 5 step LOCK:

#### Step 1 — DB 컬럼 추가 (Flyway VN)

`system_config` (시스템 단일 키) 또는 `user_kis_credential` 유사 별도 테이블 (사용자별 키) 결정 후 BYTEA AES 컬럼 추가:

```sql
-- 예시 — OpenAI 키 추가 (system 단일)
ALTER TABLE system_config ADD COLUMN openai_api_key BYTEA;  -- AES-256-GCM
```

| 키 분류 | 저장 위치 | 사례 |
|---|---|---|
| 시스템 전역 (1 user = 1 key) | `system_config.*_key` BYTEA | DART · Slack webhook · (예시) OpenAI · Anthropic |
| 사용자별 (multi-user 진입 후) | 별도 테이블 (`user_kis_credential` 유사) | (예시) 사용자별 Slack 채널 webhook |

**원칙**:
- 1차는 모두 `system_config` (사용자=최고 관리자 1인 — 헌법 §1 + 메모리 `project_overview.md`)
- multi-user 진입 시(미래) 사용자별 분리 가능 — ADR 0010 정합

#### Step 2 — domain port + AES 암호화 어댑터 추가

`domain/port/CredentialPort.kt` (ADR 0046 §3-2 명시) 인터페이스에 `getXxx()` / `updateXxx(byte[])` 메서드 추가 + `infra/CredentialAdapter.kt` 구현체에서 AES-256-GCM 암복호화.

**금지 패턴**:
- Repository interface 안에 body 가진 fun (헌법 §6 ADR 0051 정합 — Spring Data query inference 함정)
- 평문 로그 출력 — `MaskingPatternLayout` (ADR 0046 §2-5) 패턴 추가 의무

#### Step 3 — REST endpoint 추가

`/api/settings/<category>/<key-name>` PUT + (선택) GET. 응답에는 **마스킹된 값만** 표시:

```kotlin
data class XxxKeyResponseDto(
    val hasKey: Boolean,
    val maskedValue: String?,  // 예: "sk-...AbCd" — 앞 3자 + 뒤 4자만
    val updatedAt: Instant?,
)
```

**원칙**: 평문 값은 사용자가 입력한 시점만 hold (메모리 → AES → DB → memory zero-fill) — ADR 0046 §2-5 LOCK.

#### Step 4 — frontend `forms/XxxKeyForm.tsx` 추가 + Section wrapper

`forms/` 폴더에 입력 폼 1개 + `XxxKeySection.tsx` wrapper. `SecretKeyInput` 공통 컴포넌트 의무 사용.

#### Step 5 — SettingsModal에 section mount

`SettingsModal.tsx` 본문에 `<XxxKeySection />` 한 줄 추가. AppHeader 변경 0건.

**검증 의무**:
- 입력 폼 unmount 시 메모리 zero-fill (`useEffect` cleanup 의무 — `KisCredentialForm` 참조)
- 응답 DTO에 평문 키 절대 포함 금지 (mask만)
- E2E smoke: 입력 → DB AES 영속 → 재진입 시 mask 표시 + last 4자 일치 확인

### 2-5. §5 DB·암호화 정합 (AES 마스터 키 + 테이블 매핑)

ADR 0046 §2-1·§2-4·§2-5 LOCK 정합 — 본 ADR이 supersede 하지 않고 reuse:

| 테이블·컬럼 | SettingsModal section | 입력 → 저장 흐름 |
|---|---|---|
| `system_config.dart_api_key` (BYTEA) | SystemConfigSection | 입력 → AES-256-GCM 암호화 → UPDATE singleton row → 메모리 zero-fill |
| `system_config.notification_slack_webhook` (BYTEA) | SystemConfigSection | 동상 + test message 1회 발송 (ADR 0044 정합) |
| `user_kis_credential (REAL, *).app_key_enc`·`secret_key_enc` | KisCredentialSection (변경) | 입력 → KIS OAuth tokenP 재검증 → 성공 시 UPDATE row (`last_validated_at = now()`) |
| `user_kis_credential (MOCK, *)` (row 신규 또는 변경) | KisCredentialSection | 동상 — MOCK endpoint(openapivts) 검증 |
| `user_account.real_trading_consent_at` (TIMESTAMPTZ NULL) | RealTradingConsentSection | 이중 confirm → PUT `/api/user-account/real-trading-consent` → `consented=true`면 `now()`, false면 `NULL` |

**암호화 마스터 키**:
- `MAGICJAR_SECRET_MASTER_KEY` env 단일 예외 (ADR 0046 §2-4)
- SettingsModal에서 절대 입력 받지 않음 (자기 자신 암호화 불가)
- 회전은 backlog 의제 §C (MasterKeyRotationSection 후속 ADR 0091+)

**Flyway 영향**:
- 1차 PR — Flyway 신규 0건 (테이블 모두 V25 ADR 0046에서 이미 생성 완료)
- 미래 신규 키 추가 시 — VN 1건씩 추가 (Step 1 절차 정합)

### 2-6. §6 UX 룰 LOCK

#### ⚙️ AppHeader 진입점

`AppHeader.tsx` NavLink 10번째에 ⚙️ 아이콘 버튼 추가:

```tsx
{/* SettingsModal 진입점 — ADR 0069 §6 */}
<button
  type="button"
  onClick={() => setSettingsOpen(true)}
  aria-label="설정"
  className="ml-2 rounded-lg px-2.5 py-1.5 text-gray-400 hover:bg-gray-800/60 hover:text-gray-200"
>
  <SettingsIcon className="h-4 w-4" />
</button>
```

- `useState<boolean>` 로컬 hook으로 modal 열림 상태 관리
- SetupWizard 라우트(`/setup`)에서는 AppHeader 자체 비표시 → ⚙️ 자동 비노출 (현 `App.tsx` 라우팅 분기 정합)

#### Modal layout (overlay)

- **overlay**: `fixed inset-0 z-[60] bg-black/70` (z-index AppHeader=50 위)
- **panel**: 중앙 + `max-w-2xl` + `max-h-[85vh] overflow-y-auto`
- **닫기**: 우상단 X 버튼 + Esc 키 + overlay click (단 form dirty 상태면 confirm "변경 사항이 저장되지 않습니다. 닫을까요?")
- **section 구분**: `divide-y divide-gray-800` — 각 section 위·아래 1px border
- **저장 버튼**: 각 section 내부 (글로벌 저장 버튼 ❌ — section마다 독립 mutation)

#### 이중 confirm 정책

**대상**: 자본 손실 risk 있는 모든 토글·삭제 (T-O 자본 보호 LOCK 정합):

- RealTradingConsentToggle ON (이미 ADR 0068 §16-4 LOCK)
- 미래: KIS MOCK 키 DELETE (자본 영향 0건이지만 운영 차단 risk — 1차 PR scope 외 — backlog 의제 §A)
- 미래: KillSwitch 수동 해제 (ADR 0043 정합)

→ `ConfirmDangerToggle` 공통 컴포넌트로 패턴 추출 (§2-2 매트릭스 정합)

#### 키 변경 시 active session 처리

KIS 키 변경(REAL 또는 MOCK) 성공 시:

1. backend: 새 키로 KIS OAuth tokenP 재발급 + `last_token_iss = now()` 갱신 (ADR 0038 §2 정합)
2. consumer 측 KisAuthAdapter Redis 캐시 invalidate (`SET kis:token:<user>:<accountType> ...` DEL)
3. KIS WS 활성 구독 있으면 WS 재연결 (1회 — graceful reconnect, ADR 0048 §3 정합)
4. frontend: 성공 토스트 "KIS 키 변경 완료 — 새 토큰 발급됨" + section last_validated_at 갱신

**금지**:
- 키 변경 후 즉시 주문 발행 (잠금 5초 — backend 측 정합 의무, 1차 PR scope 외 — backlog 의제 §D)
- 키 변경 실패 시 기존 row UPDATE (검증 통과 시에만 UPDATE — 원자성 LOCK)

#### 마스킹 표시

응답 DTO에 평문 키 절대 미포함 (§4 Step 3 LOCK). 표시 형식:

| 키 | 마스킹 형식 | 예시 |
|---|---|---|
| KIS App Key | 앞 4자 + `***` + 뒤 4자 | `PS01***wxyz` |
| KIS Secret Key | 전부 `********` | `********` (절대 비교 불가) |
| KIS 계좌번호 | `masked_account` (DB GENERATED) | `7468****` |
| DART API key | 앞 4자 + `***` | `abcd***` |
| Slack webhook | `https://hooks.slack.com/services/***/***/***` | (URL 구조만 표시) |

### 2-7. §7 backlog 의제 (1차 PR scope 외 — 후속 처리)

| 의제 | 설명 | 진입 시점 |
|---|---|---|
| §A KIS MOCK 키 DELETE | `account_type=MOCK` row DELETE + 이중 confirm + active session 정리 | 2차 PR (사용자 명시 요청 시) |
| §B `RealTradingConsentToggle` 내부 리팩토링 | `ConfirmDangerToggle` 공통 컴포넌트로 추출 + 2개 confirm 메시지 prop로 분리 | 1차 PR 머지 후 별도 PR (영역 분리) |
| §C 마스터 키 회전 자동화 | `MAGICJAR_SECRET_MASTER_KEY` 변경 시 모든 BYTEA 컬럼 재암호화 자동 | ADR 0091+ 후속 |
| §D 키 변경 직후 주문 발행 잠금 | KIS 키 UPDATE 후 5초 backend 잠금 (KisAuthAdapter Redis 캐시 invalidate 동안 race condition 회피) | T-O 보강 사이클 |
| §E `OpenAiKeySection` | OpenAI/Anthropic 키 입력 + AI 기능 enable 토글 | T-AI 트랙 진입 시 (W4) |
| §F `NaverApiSection` | 종목 뉴스·메타 정보 보조 출처 키 | 사용자 요청 시 |
| §G `KillSwitchSection` | KillSwitch 상태 + 수동 해제 | T-J + T-RISK 통합 시 |
| §H `AccountPrdtCdSection` | `account_prdt_cd` 변경 (종합 → 선물옵션 등) | 사용자 선물옵션 필요 시 |
| §I `AccountDisplayNameSection` | display_name 변경 | multi-user 진입 시 |
| §J SettingsModal route URL 보존 | `/settings` route 추가로 새로고침 시 modal 재진입 가능 | 사용자 요청 시 (modal vs page 분기 재논의) |

## 3. 결과·영향

### 3-1. 트랙 영향

- **본 ADR = T-ONBOARD 보강** — ADR 0046 LOCK 위에 SettingsModal 진입 path + 공통 컴포넌트 추출 명시. 트랙 신설 X.
- **T-O 자본 보호 검증 unblock** — RealTradingConsentToggle UI 접근 가능 → dogfooding 케이스 1·2·3 (ADR 0068 §16-5) 검증 진행 가능.
- **W4 T-AI 진입 prereq** — OpenAI/Anthropic 키 등록 path가 §4 표준 절차로 정의됨 → T-AI 사이클 시 단순히 절차 따르면 됨.

### 3-2. 컨테이너·모듈 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | SettingsController 신규 (KIS 키 변경 PUT + DART/Slack PUT — 5 endpoint 후보. 1차 PR scope는 backend-core 영역 후속 디스패치) |
| `consumer` | KIS 키 변경 시 KisAuthAdapter Redis 캐시 invalidate + WS 재연결 1회 (§6 active session 처리) — 1차 PR scope 외 |
| `domain` | 변경 0건 (도메인은 ADR 0046 baseline reuse — `CredentialPort` interface 시그니처 추가만 미래에 필요 시) |
| `frontend` | **본 ADR 1차 구현 영역** — SettingsModal 본체 + 5 추출 컴포넌트 + AppHeader ⚙️ 진입점 + SetupWizard 3 Step 리팩토링 (`KisRealStep`·`KisMockStep`·`SystemConfigStep` → forms 사용으로 단순화) |
| `batch` | 영향 0건 |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| SettingsModal 진입 빈도 | log INFO (관리자 = 사용자 본인이므로 익명화 불필요 — KIS 키 평문은 절대 포함 금지, ADR 0046 §2-5 정합) |
| 키 변경 실패 (KIS OAuth tokenP 검증 fail) | log WARN + frontend toast "검증 실패 — KIS 콘솔에서 키 재발급" 일반 메시지 |
| 키 변경 성공 후 active session 결함 | log ERROR + Slack alert (KisAuthAdapter Redis 캐시 invalidate 실패 시 — 1차 PR scope 외 — backlog §D) |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) SettingsModal = SetupWizard 단순 reuse (5 step 그대로 모달 안에 띄움) | 폐기 | 단계별 진행 UX(progress bar)가 운영 변경에 부적합 — section 독립 저장 필요. 책임 분리 의무(사용자 명시) 위반 |
| (b) SetupWizard + SettingsModal 완전 별도 코드 (공통 추출 0) | 폐기 | 사용자 명시 "중복 제거" 위반. 보안 룰(ADR 0046 §2-5) 동기화 risk |
| (c) **SetupWizard + SettingsModal wrapper 분리 + forms/ 6 공통 컴포넌트 추출** (채택) | ✅ | 사용자 3 핵심 의제(분리·중복 제거·확장성) 모두 만족. dogfooding unblock + 미래 §4 표준 절차 LOCK |
| (d) SettingsModal을 별도 page route (`/settings`) | 후속(§7 §J) | 1차는 modal — UX 부담 ↓. URL 보존 필요 시 §J |
| (e) section 글로벌 저장 버튼 | 폐기 | section마다 독립 mutation이 trace·rollback에 유리. 부분 실패 처리 자연 |
| (f) 평문 키 응답 reflect (DEBUG 용) | 폐기 | ADR 0046 §2-5 LOCK 위반 — 절대 금지 |

## 4. 후속 ADR 후보

- **ADR 0091+ (가칭)** — 마스터 키 회전 자동화 (현재는 수동 재암호화 — backlog §C)
- **ADR 0092+ (가칭)** — multi-user 진입 시 사용자별 권한 분리 (admin = system_config 편집, normal = 본인 KIS만, ADR 0046 §4 정합)
- **ADR 0093+ (가칭)** — `RealTradingConsentToggle` 패턴을 `ConfirmDangerToggle` 추상화 + KillSwitch·AI auto-trade 등 동종 위험 토글 공통 표준 (backlog §B)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-13 | ADR 0069 신규 발행 — SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 6종 추출 + 미래 신규 API 키 추가 5 step 표준 절차 + 1차 PR scope LOCK (RealTradingConsentSection + KisCredentialSection + SystemConfigSection 3 section) + backlog 의제 10건. 사용자 명시 정책 정합 (2026-05-13 dogfooding 결함 발견 응답). T-O 자본 보호 검증(케이스 1·2·3) unblock. (architect) |
