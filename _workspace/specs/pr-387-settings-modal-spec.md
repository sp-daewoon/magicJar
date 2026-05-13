# PR #387 — SettingsModal vs SetupWizard 책임 분리 + 1차 frontend 구현 spec

- **상태**: spec 발행 (architect)
- **작성일**: 2026-05-13
- **base ADR**: 0069 (SettingsModal vs SetupWizard 책임 분리)
- **관계**: ADR 0046 §2-2 (셋업 마법사 7-step reuse) · ADR 0068 §16-4 (RealTradingConsentToggle UI LOCK) · ADR 0044 (Slack webhook 저장 위치) · 헌법 §1·§5·§6
- **선행**: 본 PR (architect 단독 spec) → 후속 디스패치 frontend sub-agent 단독 (Phase 1 frontend)

---

## §1. 사이클 scope LOCK (1차 PR 한정)

ADR 0069 §3 1차 scope 매트릭스 정합 — **이번 PR scope 외 항목은 ADR 0069 §7 backlog로 분리**:

| 작업 항목 | 1차 PR scope 포함 | 사유 |
|---|---|---|
| SettingsModal.tsx 본체 (modal frame + 3 section 컨테이너) | ✅ | 진입점 unblock |
| AppHeader.tsx ⚙️ 진입점 + modal 열림 상태 hook | ✅ | dogfooding 자본 보호 검증 prereq |
| forms/ 공통 컴포넌트 6종 신규 | ✅ | 중복 제거 의무 (사용자 명시) |
| SetupWizard 3 step 리팩토링 (KisRealStep·KisMockStep·SystemConfigStep) | ✅ | forms 재사용 의무 — 중복 제거 LOCK |
| RealTradingConsentToggle을 SettingsModal에 mount | ✅ | T-O 자본 보호 검증 unblock |
| KIS 키 변경 REST endpoint 호출 (api/account.ts 확장) | △ partial | 1차는 **frontend 단독** (api·domain backend-core PR 후속) — frontend는 mock-only로 stub mutation 사용 또는 backend endpoint 부재 시 disabled 처리 |
| RealTradingConsentToggle 내부 `ConfirmDangerToggle` 추출 리팩토링 | ❌ | ADR 0069 §7 §B 후속 PR (영역 분리) |
| KIS MOCK 키 DELETE | ❌ | ADR 0069 §7 §A 후속 PR |
| OpenAI/Naver/KillSwitch/account_prdt_cd section | ❌ | ADR 0069 §7 §E·§F·§G·§H 후속 (T-AI 등 진입 시) |
| `/settings` route URL 보존 | ❌ | ADR 0069 §7 §J 후속 |
| Flyway 변경 | ❌ | 1차 PR Flyway 0건 (ADR 0046 V25 baseline reuse) |
| backend-core 신규 REST endpoint (KIS 키 변경 PUT 등) | ❌ | backend-core sub-agent 후속 별도 PR — 이번 사이클은 frontend 단독 |

**LOCK 요약**: 이번 1차 PR은 **frontend 단독 sub-agent** + **add-only 100% 신규 컴포넌트 + 기존 SetupWizard 3 step 리팩토링 + AppHeader 1 줄 추가**. backend·domain·Flyway 0건. modify-shared 최소화.

## §2. 신규 파일 매트릭스 (add-only)

| 파일 | 책임 | LOC 추정 |
|---|---|---|
| `frontend/src/components/settings/SettingsModal.tsx` | overlay modal frame + Esc/X/overlay click 닫기 + 3 section 컨테이너 + form dirty 시 닫기 confirm | ~120 |
| `frontend/src/components/settings/SettingsSection.tsx` | section header + 본문 wrapper + status badge(저장 됨·변경 중·실패) | ~50 |
| `frontend/src/components/settings/sections/RealTradingConsentSection.tsx` | 기존 `RealTradingConsentToggle.tsx` wrapper — `SettingsSection`로 감싸고 mount 위치 변경 | ~30 |
| `frontend/src/components/settings/sections/KisCredentialSection.tsx` | REAL 변경 + MOCK 추가/변경 form (KisCredentialForm 사용) + last_validated_at 표시 | ~80 |
| `frontend/src/components/settings/sections/SystemConfigSection.tsx` | DART + Slack URL 입력 form (SystemConfigForm 사용) | ~40 |
| `frontend/src/components/settings/forms/KisCredentialForm.tsx` | KIS 계좌번호 + appKey + secretKey 입력 + 8자리 검증 + autocomplete=off + unmount 시 zero-fill — `accountType: 'REAL' \| 'MOCK'` prop 분기 | ~140 |
| `frontend/src/components/settings/forms/SecretKeyInput.tsx` | type=password input + autocomplete=off + ref + unmount 시 zero-fill 헬퍼 hook 내장 | ~50 |
| `frontend/src/components/settings/forms/SystemConfigForm.tsx` | DART API key + Slack webhook URL 입력 + https 검증 | ~80 |
| `frontend/src/components/icons/SettingsIcon.tsx` | ⚙️ SVG icon (lucide-react 또는 inline path — 의존 추가 0 정합) | ~10 |

**총 신규 LOC ~600줄** (frontend 단독, 작은 사이즈).

## §3. 변경 파일 (modify)

| 파일 | 변경 영역 | 변경 사유 |
|---|---|---|
| `frontend/src/components/layout/AppHeader.tsx` | NavLink 9개 뒤에 ⚙️ 버튼 1개 추가 + `useState<boolean>` settingsOpen hook + `<SettingsModal isOpen={settingsOpen} onClose={() => setSettingsOpen(false)} />` mount | ADR 0069 §6 진입점 |
| `frontend/src/pages/setup/steps/KisRealStep.tsx` | KIS 계좌번호·appKey·secretKey 입력 폼 → `KisCredentialForm accountType="REAL"` 사용으로 단순화. Step layout(헤더+안내 문구+검증 mutation 결합)은 그대로 유지 | 중복 제거 (forms 재사용) |
| `frontend/src/pages/setup/steps/KisMockStep.tsx` | 동상 — `KisCredentialForm accountType="MOCK"` 사용 | 동상 |
| `frontend/src/pages/setup/steps/SystemConfigStep.tsx` | DART + Slack 입력 폼 → `SystemConfigForm` 사용으로 단순화 | 동상 |
| `frontend/src/components/settings/RealTradingConsentToggle.tsx` | 변경 0건 (1차 PR scope) — `RealTradingConsentSection`이 이 컴포넌트를 단순 import + render. 내부 `ConfirmDangerToggle` 추출 리팩토링은 ADR 0069 §7 §B 후속 PR | 1차 unblock 최소 변경 |

**총 modify LOC ~150줄** (대부분 코드 삭제 + forms 호출로 치환).

## §4. 사용 STORE/API 매트릭스

### 4-1. 기존 자원 reuse (1차 PR scope — neutral)

| 자원 | 위치 | reuse 영역 |
|---|---|---|
| `useOnboardingStore` (Zustand) | `frontend/src/stores/onboarding.ts` | SetupWizard 영역 유지 (SettingsModal은 사용 안 함 — section 단위 mutation 으로 충분) |
| `useRealTradingConsent` + `useUpdateRealTradingConsent` (TanStack Query) | `frontend/src/api/hooks/useUpdateRealTradingConsent.ts` | RealTradingConsentSection — 그대로 reuse |
| `useValidateKisReal` + `useAddMockAccount` + `useUpdateSystemConfig` (mutations) | `frontend/src/api/onboarding.ts` | SetupWizard 영역 유지 + SettingsModal에서 일부 reuse (DART/Slack section은 `useUpdateSystemConfig` 직접 호출) |
| `accountApi.updateRealTradingConsent` | `frontend/src/api/account.ts` | RealTradingConsentSection (변경 0) |
| `apiClient` (axios wrapper) | `frontend/src/lib/axios.ts` | 신규 mutation hook 작성 시 사용 |

### 4-2. 신규 자원 (1차 PR scope — add)

| 자원 | 위치 | 책임 |
|---|---|---|
| `useUpdateKisCredential` (mutation) | `frontend/src/api/hooks/useUpdateKisCredential.ts` 신규 | PUT `/api/onboarding/kis/{accountType}` (REST endpoint는 ADR 0046 기존 POST `/api/onboarding/kis/validate` + `/api/onboarding/kis/mock` reuse 시도 → 변경/추가 모두 POST upsert 의미 — backend-core가 후속 PR에서 PUT 분리 결정 시 갱신). **1차 PR에서는 frontend stub 또는 disabled 표시** (§5-3 운영 분기 LOCK 참조) |
| `useSystemConfigStatus` (query, optional) | `frontend/src/api/hooks/useSystemConfigStatus.ts` 신규 (선택) | GET `/api/onboarding/status` 응답의 `hasDartKey`·`hasSlackWebhook` 표시용 — 기존 `useOnboardingStatus` reuse 가능 → **신규 hook 0** 가능 |

**원칙**: 신규 hook 최소화 — 기존 `useOnboardingStatus` + `useUpdateSystemConfig` reuse가 1차 PR scope 최적.

### 4-3. backend-core 후속 영역 (이번 PR scope 외)

| 영역 | 책임 | 진입 시점 |
|---|---|---|
| `PUT /api/onboarding/kis/{accountType}` 분리 (validate vs update 시맨틱 명확화) | backend-core | 1차 PR 머지 후 후속 |
| `DELETE /api/onboarding/kis/MOCK` | backend-core | ADR 0069 §7 §A 후속 |
| `MaskedKisCredentialResponseDto` (마스킹 형식 응답 — §5 정합) | backend-core | 1차 PR 머지 후 후속 |
| KisAuthAdapter Redis 캐시 invalidate (active session 처리) | backend-core | ADR 0069 §6 active session 처리 - 1차 PR scope 외 (backlog §D) |

→ **사용자에게 보고**: 1차 PR scope에서 KIS 키 변경 UI는 layout + 입력 form까지만 구현, 실제 변경 submit은 frontend stub 또는 "후속 backend 작업 진행 중" disabled. **RealTradingConsentToggle은 기존 endpoint 운영 중**이므로 1차 PR scope에서 fully functional.

## §5. 핵심 동작 LOCK

### 5-1. ⚙️ 진입점 + modal 열림/닫힘

```tsx
// AppHeader.tsx 추가 영역
const [settingsOpen, setSettingsOpen] = useState(false)

return (
  <>
    <header>
      {/* 기존 NavLink 9개 */}
      <button onClick={() => setSettingsOpen(true)} aria-label="설정">
        <SettingsIcon />
      </button>
    </header>
    {settingsOpen && (
      <SettingsModal onClose={() => setSettingsOpen(false)} />
    )}
  </>
)
```

**원칙**:
- modal은 conditional render (`{settingsOpen && <SettingsModal />}`) — unmount 시 form state 자연 reset → 메모리 잔여 방지
- SetupWizard 라우트(`/setup`)에서는 `App.tsx` 라우팅 분기로 AppHeader 자체 비표시 → ⚙️ 자동 비노출

### 5-2. modal layout (ADR 0069 §6 LOCK 정합)

```tsx
// SettingsModal.tsx 골격
<div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 p-4"
     onClick={handleOverlayClick}>
  <div className="w-full max-w-2xl max-h-[85vh] overflow-y-auto rounded-2xl border border-gray-800 bg-gray-900 shadow-xl"
       onClick={(e) => e.stopPropagation()}>
    <header className="flex items-center justify-between border-b border-gray-800 px-6 py-4">
      <h2 className="text-lg font-semibold text-white">설정</h2>
      <button onClick={onClose} aria-label="닫기">
        <XIcon className="h-5 w-5" />
      </button>
    </header>
    <div className="divide-y divide-gray-800">
      <RealTradingConsentSection />
      <KisCredentialSection />
      <SystemConfigSection />
    </div>
  </div>
</div>
```

**키 이벤트 처리**:
- Esc 키 → `onClose()` (`useEffect` keydown listener 등록·해제)
- overlay click → form dirty 검사 후 confirm "변경 사항이 저장되지 않습니다. 닫을까요?" — section 내 form dirty 상태를 modal에 propagation할 방법은 1차 PR에서는 단순 cancel confirm 없이 닫기만 (form dirty 추적은 backlog §J 분리 가능)

**1차 PR 단순화 LOCK**: form dirty 추적은 추후 — 1차는 단순 닫기만 (사용자가 미저장 변경 알아채는 UX risk → 후속 PR로 개선)

### 5-3. KIS 키 변경 1차 PR 운영 분기 LOCK

backend-core가 PUT 분리 endpoint를 발행하기 전까지는 다음 분기:

**Option A (1차 PR 권장)**: KisCredentialSection은 layout + 입력 form 표시 + **submit 버튼 disabled** + "후속 backend 작업 진행 중" 안내 문구 표시. 화면은 노출되어 사용자가 입을 검증 가능, 실제 mutation은 후속 PR.

**Option B (대안 — 1차에 동시 backend PR 디스패치)**: backend-core sub-agent를 같은 사이클에 디스패치 → PUT endpoint 발행 → frontend가 사용. modify-shared risk가 있지만 사이클당 단일 결과물 LOCK 위반.

→ **선택 LOCK: Option A** (사용자 메모리 `feedback_spec_plan_scope.md` 정합 — 작은 PR 분리 우선). RealTradingConsentToggle + DART/Slack 변경(기존 endpoint reuse)은 1차 PR에서 fully functional. KIS 키 변경 submit은 1차 PR 종료 후 backend-core sub-agent 디스패치 → 2차 PR.

### 5-4. unmount 시 zero-fill 의무 (ADR 0046 §2-5 정합)

`SecretKeyInput` 공통 컴포넌트는 다음 패턴 강제:

```tsx
// SecretKeyInput.tsx 골격
export function SecretKeyInput({ value, onChange, ...rest }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    return () => {
      onChange('')  // state 초기화
      if (inputRef.current) inputRef.current.value = ''  // DOM 초기화
    }
  }, [onChange])

  return (
    <input
      ref={inputRef}
      type="password"
      autoComplete="off"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      {...rest}
    />
  )
}
```

`KisCredentialForm`도 unmount 시 모든 secret state zero-fill. SettingsModal 닫힘 시 컴포넌트 unmount → 자동 zero-fill.

### 5-5. 마스킹 표시 (ADR 0069 §6 LOCK)

KisCredentialSection이 현재 등록된 KIS 키를 표시할 때:

- 계좌번호: `masked_account` (DB GENERATED — 백엔드에서 응답으로 제공)
- App Key: `PS01***wxyz` (앞 4자 + `***` + 뒤 4자 — backend가 응답할 때 마스킹 처리 후 frontend는 그대로 표시)
- Secret Key: `********` (영구 비교 불가 — backend가 `hasSecretKey: true` boolean만 응답)

**1차 PR scope 단순화**: backend가 마스킹 형식을 발행하기 전까지는 frontend가 `hasUserAccount` boolean만 확인 → "KIS 실전 키 등록됨" 메시지 + last_validated_at(있으면) 표시.

## §6. 운영 룰 LOCK 정합 (헌법·메모리·ADR)

| 룰 | 본 spec 정합 항목 |
|---|---|
| 헌법 §1 task.mjs 4단계 | architect 본 PR은 start → check 4건 → done 절차 정합 |
| 헌법 §3 KIS-first | 본 ADR/spec은 UI 책임 분리 영역 → KIS API raw fetch 의무 0건 (해당 사항 없음) |
| 헌법 §4 CHANGELOG 슬롯 | architect 단발 spec PR → §7 slot row 1건만 발행 (sub-agent 추가 행 0건 — 후속 frontend PR에서 본인 row prepend) |
| 헌법 §5 PR 직전 rebase | 발행 직전 origin/main rebase 의무 |
| 헌법 §6 JPA default fun | backend 변경 0건 → 해당 사항 없음 |
| 메모리 `feedback_spec_plan_scope.md` | spec/plan 발행 범위 — 신규 트랙·다중 ADR 동반 시만. 본 사이클은 ADR 1건 + 단일 frontend 트랙 보강 → spec 발행 적합 |
| 메모리 `feedback_kis_key_realonly_for_data.md` | KIS 키 = 실전 전용 (정보 수집 영역). 본 spec은 SettingsModal에 REAL·MOCK 모두 노출하지만, 모의/실전 분기는 ADR 0068 주문 액션 영역만 — 정합 |
| 메모리 `project_kis_key_management.md` | 개발 중 SettingsModal 입력 + DB AES 암호화 (현재 정책). 본 ADR/spec은 이 정책의 1급 구현 path 정의 |
| ADR 0046 §2-5 보안 정책 | unmount 시 zero-fill + autocomplete=off + 평문 로그 금지 — forms/ 공통 컴포넌트 의무 강제 |
| ADR 0050 CHANGELOG 슬롯 | spec PR이 후속 frontend sub-agent 디스패치 만들지 않음 (1 sub-agent 단발) → slot row 1건만 발행 (architect 본인) |

## §7. CHANGELOG 슬롯 (ADR 0050 정합)

본 사이클은 architect 단발 spec PR + 후속 frontend sub-agent 1건 (직렬). 즉 PR 1+1 = 2개 사이클. ADR 0050 §1 "anchor + slot row prepend" 룰 적용:

```markdown
| 2026-05-13 | **arch(settings): PR-387 SettingsModal vs SetupWizard 책임 분리 + 미래 확장 구조 (ADR 0069)** — ADR 0069 신규 발행 + ADR README §10-2 row 추가 + master §10 row 추가 + master §3-1 onboarding row 갱신 + tracks-roadmap T-ONBOARD outline 보강. dogfooding 결함 응답 (RealTradingConsentToggle UI 접근 가능 path 확보 → T-O 자본 보호 검증 unblock). 1차 PR scope LOCK (RealTradingConsentSection + KisCredentialSection + SystemConfigSection 3 section + forms/ 6 공통 컴포넌트 추출 + SetupWizard 3 step 리팩토링). 후속 frontend sub-agent 단발 디스패치 의제 (1차 PR ~600 LOC add + ~150 LOC modify + backend 0건). (architect) | arch/spec | dogfooding 자본 보호 검증 unblock + 미래 신규 API 키 추가 5 step 표준 절차 |
<!-- pr-387-slot-architect -->
| 2026-05-13 | **frontend: PR-N SettingsModal 1차 구현 (ADR 0069 §3 1차 scope)** — TBD (작성: frontend) | frontend | TBD |
<!-- pr-387-slot-frontend -->
<!-- pr-387-slot-end -->
```

**구현 의무**:
- architect 본 PR diff에 위 anchor·slot row prepend (CHANGELOG 최상단 표 직후)
- 후속 frontend sub-agent는 본인 row의 `TBD`만 실제 사유로 in-place edit (다른 row·anchor 절대 수정 금지)
- 정확한 PR-N 번호는 frontend sub-agent 디스패치 시 orchestrator가 채번

## §8. 검증 의무 (frontend sub-agent용)

후속 frontend sub-agent가 본 spec 정합 검증 시:

| 검증 항목 | 방법 |
|---|---|
| AppHeader ⚙️ 버튼 클릭 → SettingsModal overlay 표시 | 수동 dogfooding |
| Esc 키 → modal 닫힘 | 수동 |
| modal 내 RealTradingConsentToggle 이중 confirm → DB `real_trading_consent_at` 갱신 | DB SELECT + STOMP 확인 |
| KisCredentialSection submit 버튼 disabled + 안내 문구 표시 (1차 PR Option A) | 수동 |
| SystemConfigSection DART/Slack 입력 → 기존 endpoint UPDATE 성공 | DB SELECT 확인 |
| SetupWizard 3 step이 forms/ 공통 컴포넌트 사용 + 동작 회귀 0 | SetupWizard 재진행 시 input·검증·zero-fill 모두 정상 |
| pnpm typecheck + pnpm build GREEN | pre-push hook 자동 |
| ktlintCheck (frontend 변경 — 해당 없음, Kotlin 0건) | N/A |

**dogfooding evidence 인용 의무** (메모리 `feedback_verification_strict.md` 정합):
- 1차 PR 종료 시 RealTradingConsentToggle ON/OFF 토글 capture (screen + DB row + STOMP toast)
- DART/Slack 변경 시 system_config row 갱신 확인 + Slack test message 수신 capture
- SetupWizard 재진행 시 기존 흐름 회귀 0 확인 (수동 step 1~5)

## §9. 후속 PR 매트릭스 (1차 PR 머지 후)

| 후속 PR | 영역 | scope | 진입 시점 |
|---|---|---|---|
| PR-N+1 | backend-core | PUT `/api/onboarding/kis/{accountType}` 분리 + `MaskedKisCredentialResponseDto` + KisAuthAdapter Redis 캐시 invalidate (active session 처리, ADR 0069 §6) | 1차 PR 머지 후 즉시 |
| PR-N+2 | frontend | KisCredentialSection submit 활성화 + `useUpdateKisCredential` 연결 | PR-N+1 머지 후 |
| PR-N+3 | frontend | RealTradingConsentToggle 내부 `ConfirmDangerToggle` 추출 리팩토링 (ADR 0069 §7 §B) | 별도 사이클 |
| PR-N+4 | backend-core + frontend | KIS MOCK 키 DELETE (ADR 0069 §7 §A) | 사용자 명시 요청 시 |
| PR-N+M | frontend | OpenAI/Anthropic 키 section (ADR 0069 §7 §E) — T-AI 진입 시 표 §4 표준 절차 정합 | T-AI 사이클 |

## §10. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-13 | spec 신규 발행 — ADR 0069 1차 PR frontend 구현 가이드 LOCK. 신규 9 파일 + 변경 5 파일 + LOC ~600 add + ~150 modify + backend 0건 + Flyway 0건. KIS 키 변경 submit은 후속 PR (Option A LOCK). CHANGELOG 슬롯 architect 1 + frontend 1. dogfooding evidence 인용 의무 명시. (architect) |
