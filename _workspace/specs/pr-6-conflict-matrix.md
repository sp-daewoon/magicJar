# PR-6 T-ONBOARD 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-6-onboard-design.md` v1 + `_workspace/specs/pr-6-dispatch-spec.md` v1
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — **architect "직렬 권장" 답습 금지**. 사용자 의도+실제 충돌 행렬 재검토 후 적극 병렬화. add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 3단 hierarchy: 오케스트라(지휘) → architect(설계) → 실무 sub-agents (병렬 실행)
  - `feedback_autonomy.md` — Claude 자율 실행

---

## 1. 결론 (한 줄)

> **PR-6a (backend-core) + PR-6b (frontend) 완전 병렬 가능 — 모든 충돌 항목이 add-only이거나 양쪽이 인터페이스 동결(REST 시그니처 LOCK)을 바라보는 구조라 modify-shared 위험이 0건.**

---

## 2. 인터페이스 동결 시점

본 spec 3종(`pr-6-onboard-design.md` + `pr-6-dispatch-spec.md` + 본 문서)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-6-onboard-design.md` §3 (DB 스키마 LOCK)
- `pr-6-onboard-design.md` §4 (REST API 시그니처 LOCK)
- `pr-6-onboard-design.md` §5 (Wizard step 계약 LOCK)
- `pr-6-onboard-design.md` §6 (AES-256-GCM Crypto 명세)
- `pr-6-dispatch-spec.md` §4 (예시 페이로드)

**인터페이스 동결 즉시** backend·frontend 양쪽이 spec만 보고 동시에 자기 worktree에서 작업 시작 가능.

---

## 3. 파일 단위 충돌 행렬

### 3-1. PR-6a (backend-core) 변경 파일 vs PR-6b (frontend) 변경 파일

| 파일/디렉토리 | PR-6a | PR-6b | 충돌 유형 | 결정 |
|---|---|---|---|---|
| `api/src/main/resources/db/migration/V2__onboarding_refinements.sql` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/secret/AesGcmCipher.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/adapter/kis/KisAuthValidator.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/adapter/kis/ValidationResult.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/config/WebClientConfig.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/config/OnboardingGuardFilter.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/config/MaskingPatternLayout.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/controller/OnboardingController.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/controller/dto/Onboarding*.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/service/OnboardingService.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/UserAccount*.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/UserKisCredential*.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/SystemConfig*.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/resources/application.yml` | **변경** (magicjar.secret.master-key 1줄 추가) | 무관 | modify (backend 단독) | **격리** |
| `api/src/main/resources/logback-spring.xml` | **신규** | 무관 | add-only | **격리** |
| `api/src/test/kotlin/.../OnboardingControllerTest.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/test/kotlin/.../AesGcmCipherTest.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/**` | **변경 0** (PR-5a에 이미 SecretCipher port·UserOnboardedEvent stub 존재 가정) | 무관 | (변경 0) | **격리** |
| `batch/**` | **변경 0** | 무관 | (변경 0) | **격리** |
| `consumer/**` | **변경 0** | 무관 | (변경 0) | **격리** |
| `frontend/src/pages/SetupWizard.tsx` | 무관 | **신규** (또는 placeholder 교체) | add-only | **격리** |
| `frontend/src/pages/SetupWizardPlaceholder.tsx` | 무관 | **삭제** (또는 SetupWizard로 rename) | delete (frontend 단독) | **격리** |
| `frontend/src/pages/setup/steps/KisRealStep.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/setup/steps/KisValidatingStep.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/setup/steps/KisMockStep.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/setup/steps/SystemConfigStep.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/setup/steps/CompleteStep.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/stores/onboarding.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/api/onboarding.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/lib/axios.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/App.tsx` | 무관 | **변경** (라우팅 가드 추가) | modify (frontend 단독) | **격리** |
| `frontend/package.json` | 무관 | **변경 가능** (axios 추가 시) | modify (frontend 단독) | **격리** |
| `frontend/pnpm-lock.yaml` | 무관 | **변경 가능** (axios 추가 시) | modify (frontend 단독) | **격리** |
| `frontend/src/__tests__/onboarding-routing.test.tsx` | 무관 | **신규** | add-only | **격리** |
| `.env.example` | **변경** (MAGICJAR_SECRET_MASTER_KEY 추가 + KIS·DART deprecated) | 무관 | modify (backend 단독) | **격리** |
| `docker/docker-compose.yml` | **변경 0 권장** | **변경 0 권장** | (변경 0) | **격리** |
| `docker/docker-compose.dev.yml` | **변경 0 권장** | **변경 0 권장** | (변경 0) | **격리** |
| `docker/docker-compose.infra.yml` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `scripts/build-and-deploy.sh` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.github/workflows/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `CHANGELOG.md` | **변경** (PR-6a 1줄 추가) | **변경** (PR-6b 1줄 추가) | **modify-shared** | **rebase 시 수동 정렬** (양쪽 add 위치가 같음) |
| `docs/architecture/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `_workspace/arch/adr/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `_workspace/specs/pr-6-*.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (본 spec — architect 단독) |
| `_workspace/tasks/T-*-pr-6-onboard-backend.md` | **신규** (자기 task) | 무관 | add-only | **격리** |
| `_workspace/tasks/T-*-pr-6-onboard-frontend.md` | 무관 | **신규** (자기 task) | add-only | **격리** |
| `README.md` (루트) | **변경 0** | **변경 0** | (변경 0) | **격리** |

### 3-2. modify-shared 항목 분석

`CHANGELOG.md` 1건만 modify-shared. 분석:

```markdown
| 2026-05-07 | feat(api): PR-6a T-ONBOARD backend ... | backend | T-ONBOARD |
| 2026-05-07 | feat(frontend): PR-6b T-ONBOARD frontend ... | frontend | T-ONBOARD |
```

- 두 행은 추가 위치가 같은 영역(상단)이라 git rebase 시 conflict 가능
- 그러나 양쪽 모두 **add-only insert** 패턴 — `git rebase` 시 conflict marker 발생 시 양쪽 행 모두 보존하는 trivial resolve
- 실제로 양쪽 `add-only insert`라서 git이 자동 merge하는 경우도 많음 (3-way merge 알고리즘이 양쪽 새 줄 모두 keep)

**결론**: CHANGELOG.md 1건은 modify-shared지만 **충돌 해결 비용이 사실상 0** — 양쪽 행 모두 keep. 병렬 진행에 영향 없음.

### 3-3. 격리 검증 — backend·frontend가 같은 디렉토리에 손대는 경우 0건

- `api/**` 디렉토리는 backend 단독
- `frontend/**` 디렉토리는 frontend 단독
- `domain/**`·`batch/**`·`consumer/**` 양쪽 변경 0
- `docker/**`·`scripts/**`·`.github/**` 양쪽 변경 0

**결론**: PR-5와 달리 PR-6은 **`docker/docker-compose.yml` 양쪽 손댈 가능성도 0** — 새 컨테이너 추가·서비스 변경이 모두 PR-6 비범위.

---

## 4. pre-push hook 종속성 분석

PR-5 시리즈에서 직렬을 강제한 사유 중 하나가 "frontend가 backend 모듈 골격 없는 main에서 push 시 `./gradlew check` 실패". 본 PR-6에는 해당 문제 없음:

| 이유 | 분석 |
|---|---|
| PR-5a 머지 완료 | main에 4 JVM 모듈 골격 존재 — `./gradlew check` 통과 가능 |
| PR-5b 머지 완료 | main에 frontend 디렉토리 + Vite 설정 존재 |
| PR-6b가 frontend만 변경 | `./gradlew check` 영향 0 (Kotlin 변경 0) — pre-push 통과 |
| PR-6a가 backend만 변경 | frontend 디렉토리 변경 0 — frontend 측 검증 무관 |

**결론**: pre-push hook은 양쪽 PR이 main을 base로 진행해도 모두 GREEN 가능. 직렬 종속성 없음.

---

## 5. 인터페이스 동결로 인한 병렬 가능 메커니즘

backend·frontend가 동시에 작업하더라도 다음 4가지가 양쪽이 같은 spec을 바라보고 진행되도록 보장:

### 5-1. REST 시그니처 LOCK (spec §4-1)

```
GET /api/onboarding/status → OnboardingStatus
POST /api/onboarding/kis/validate (KisValidateRequest) → KisValidateResponse
POST /api/onboarding/kis/mock (KisMockRequest) → KisMockResponse
POST /api/onboarding/system-config (SystemConfigRequest) → SystemConfigResponse
POST /api/onboarding/complete (OnboardingCompleteRequest) → OnboardingCompleteResponse
```

**Frontend 입장**: `useValidateKisReal()` 등 TanStack Query mutation을 `KisValidateRequest`/`KisValidateResponse` TypeScript 타입으로 작성 — backend 부재 시에도 msw mock으로 검증 가능.

**Backend 입장**: 위 시그니처를 100% 그대로 구현 — frontend 부재 시에도 `curl`로 검증 가능.

### 5-2. ErrorResponse 공통 포맷 (dispatch §4-2)

`{ code, message }` 통일. 양쪽이 같은 코드명 사용 (`ONBOARDING_REQUIRED`·`KIS_AUTH_FAILED` 등).

### 5-3. Wizard 7-step 계약 LOCK (spec §5)

각 step의 화면 책임·필수 입력·검증 룰·다음 step 전이가 spec에 lock — frontend가 backend 응답 mock하면서 진행 가능.

### 5-4. 예시 페이로드 (dispatch §4-3)

JSON 페이로드 예시를 양쪽이 공유 — TypeScript 타입 inference + Kotlin DTO 작성 시 같은 데이터 형태.

---

## 6. 병렬 가능한 시나리오별 검증

### 6-1. 시나리오 A — backend·frontend 동시 시작 + 동시 머지

```
T0: spec 머지 (architect)
T0+5분: backend 작업 시작 (worktree A)
T0+5분: frontend 작업 시작 (worktree B)
T+2시간: backend PR-6a 푸시 → CI green → 사용자 머지
T+2.5시간: frontend PR-6b 푸시 → CI green → 사용자 머지
```

**충돌 가능성**:
- CHANGELOG.md 1건 — frontend가 backend 머지 후 main rebase 시 1줄 추가 → 양쪽 행 keep (trivial resolve OR git auto-merge)
- 그 외 0건

**검증**:
- backend 머지 후 main에 V2 마이그레이션 + REST endpoint 존재
- frontend rebase → main의 backend 변경에 영향 0 (frontend는 frontend/만 변경)
- frontend 머지 후 통합 검증 (DoD #1~#12) 가능

### 6-2. 시나리오 B — frontend 먼저 머지 (backend 미완)

```
T0: spec 머지
T+1시간: frontend PR-6b 푸시 → CI green (smoke test는 mock 기반) → 머지
T+2시간: backend PR-6a 푸시 → 머지
```

**검증**:
- frontend는 SetupWizard UI + axios + Zustand만 — 빌드 GREEN
- 단 frontend만 머지된 시점에 `localhost:3000` 진입 시 `useOnboardingStatus()`가 backend 부재로 502 — UX 부조화 가능
- **architect 권장 = backend 먼저 머지 + frontend 즉시 후속** (시나리오 C) — 사용자 검증 단순

### 6-3. 시나리오 C — backend 먼저 머지 + frontend 즉시 후속 (권장)

```
T0: spec 머지
T0+5분: 양쪽 동시 시작
T+2시간: backend PR-6a 푸시 → 머지
T+2.5시간: frontend PR-6b rebase → 머지
```

**검증**: 시나리오 A와 동일하나 머지 순서 명시. 양쪽이 같은 시간 동안 작업하니 효율 동일.

### 6-4. 시나리오 D — 직렬 (권장하지 않음)

```
T0: spec 머지
T0+5분: backend 시작
T+2시간: backend 머지
T+2시간 5분: frontend 시작
T+4시간: frontend 머지
```

**왜 권장하지 않는가**:
- 사용자 메모리 `feedback_orchestrator_active_parallel.md` — "직렬 권장 답습 금지"
- 인터페이스 동결로 양쪽이 즉시 시작 가능
- 직렬은 총 작업 시간 2배 (~4시간 vs 병렬 ~2.5시간)

---

## 7. 사용자 메모리 정합 검증 (필수)

| 사용자 메모리 | 본 결정 정합 |
|---|---|
| `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지 + 사용자 의도+실제 충돌 행렬 재검토 후 적극 병렬화 | ✅ — 본 행렬에서 modify-shared 1건(CHANGELOG)만 식별 + trivial resolve 사유 명시 + 병렬 권장 결론 |
| `feedback_3tier_hierarchy.md` — 오케스트라 → architect(설계) → 실무 sub-agents(병렬 실행) | ✅ — architect는 본 spec 3종만 발행, 코드 0건. backend·frontend가 병렬로 코드 작성 |
| `feedback_autonomy.md` — Claude 자율 실행, 사용자 확인은 아키텍처·외부자원·블로킹상충 3가지만 | ✅ — 본 spec에 사용자 결정 필요 사항 0건 (ADR 0046 이미 사용자 명시 정책 정합) |
| `feedback_v3_skepticism_no_regression.md` — 자동매매 적합성 의심 lens | 자동매매 적합성: ✅ 본 PR-6은 KIS 키 등록 — T-W1·T-W2·T-O 등 자동매매 트랙의 진입점. v1/v2 잔재 0건. fitness gap 0 |
| `feedback_kis_first_policy.md` — KIS MCP·open-trading-api 베이스, tr_id·endpoint 자체 추론 금지 | ✅ — 본 PR의 KIS endpoint(/oauth2/tokenP)는 ADR 0046 §2-3에 출처 명시(`kis_devlp.yaml` prod/vps + `docs/operations/kis-api-reference.md` §1) |
| `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 | ✅ — backend는 실 KIS 호출, frontend는 사용자 본인 키 입력 — mock 0건. 단 frontend smoke test는 msw mock OK (테스트 한정) |
| `feedback_secret_handling.md` — 키 평문 응답 reflect 금지 | ✅ — spec §4-1-2 명시 + ADR 0046 §2-5 |
| `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES, 본격 운영 시 secret manager 재논의 | ✅ — 본 PR-6은 셋업 마법사 = 첫 등록 화면. 등록 후 ⚙️ SettingsModal에서 갱신·재등록은 후속 트랙(post-MVP). secret manager는 ADR 0090+ 후속 |

---

## 8. 결론 — 권장 디스패치 모드

### 8-1. 1차 권장 — **완전 병렬 (시나리오 A 또는 C)**

오케스트라가 spec 머지 후 즉시 양쪽 동시 디스패치:

```bash
# Terminal 1 (backend worktree)
git worktree add .claude/worktrees/agent-pr6-backend agent/backend-core/pr-6-onboard-backend
cd .claude/worktrees/agent-pr6-backend
node scripts/task.mjs start --agent backend-core --branch agent/backend-core/pr-6-onboard-backend ...

# Terminal 2 (frontend worktree, 동시)
git worktree add .claude/worktrees/agent-pr6-frontend agent/frontend/pr-6-onboard-frontend
cd .claude/worktrees/agent-pr6-frontend
node scripts/task.mjs start --agent frontend --branch agent/frontend/pr-6-onboard-frontend ...
```

### 8-2. 2차 권장 — **시나리오 C** (backend 먼저 머지 + frontend rebase 후 즉시 머지)

머지 순서만 명시하고 작업 시간 동일.

### 8-3. 직렬은 권장하지 않음

직렬 권장 시 사유 입증 의무:
- modify-shared가 진짜로 어디인지 → CHANGELOG 1건뿐 (trivial)
- 인터페이스 동결 미달 → spec 3종이 동결 보장
- pre-push hook 종속 → PR-5a·PR-5b 머지 완료로 양쪽 main에서 GREEN

**위 3건 모두 미충족 — 직렬 권장 사유 부재.**

---

## 9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

### 9-1. PR-6a 머지 시점

- [ ] PR-6b가 main rebase 가능한 상태 (충돌 없음 또는 CHANGELOG 1줄만)
- [ ] PR-6a CI green
- [ ] spec §10-1 backend 검증 게이트 11건 모두 ✅
- [ ] PR-6b 작업자에게 "main rebase + force push" 안내 (작업자 worktree 격리됨)

### 9-2. PR-6b 머지 시점

- [ ] PR-6b CI green
- [ ] spec §10-2 frontend 검증 게이트 6건 모두 ✅
- [ ] 양쪽 머지 후 통합 게이트 (spec §10-3) 5건 모두 ✅ — 사용자 본인 키로 수동 검증

### 9-3. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG.md 정합 확인 (양쪽 add-only 행 보존)

---

## 10. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-6 충돌 행렬 v1 발행. 결론: **PR-6a + PR-6b 완전 병렬 권장**. modify-shared 1건(CHANGELOG.md)만 trivial resolve. add-only 충돌은 진짜 병렬 가능 — 사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합 (architect, opus 4.7 1M). |
