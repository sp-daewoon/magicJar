# PR-7 T-W1 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-7-w1-design.md` v1 + `_workspace/specs/pr-7-dispatch-spec.md` v1
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — **architect "직렬 권장" 답습 금지**. 사용자 의도+실제 충돌 행렬 재검토 후 적극 병렬화. add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 3단 hierarchy: 오케스트라(지휘) → architect(설계) → 실무 sub-agents (병렬 실행)
  - `feedback_autonomy.md` — Claude 자율 실행

---

## 1. 결론 (한 줄)

> **PR-7a (backend-core) + PR-7b (frontend) 완전 병렬 가능 — backend는 api+consumer+domain 신규/변경, frontend는 frontend/만 변경. 모든 충돌 항목이 add-only이거나 양쪽이 인터페이스 동결(REST 시그니처 LOCK)을 바라보는 구조라 modify-shared 위험은 CHANGELOG.md 1건뿐 (PR-6와 동일 패턴, trivial resolve).**

---

## 2. 인터페이스 동결 시점

본 spec 3종(`pr-7-w1-design.md` + `pr-7-dispatch-spec.md` + 본 문서)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-7-w1-design.md` §3 (DB V3 LOCK)
- `pr-7-w1-design.md` §4 (REST API 시그니처 LOCK — `/api/watchlists/*` + 선택 `/api/positions/bootstrap`)
- `pr-7-w1-design.md` §5 (부팅 잔고 워밍 인터페이스 LOCK — KisBalancePort + PositionBootstrapRunner + KisBalanceAdapter + KisAuthAdapter + RedisPositionCacheAdapter)
- `pr-7-w1-design.md` §6 (Watchlist UI 계약 LOCK — 라우트 + 컴포넌트 책임 + ticker validation + confirm 정책 + 정렬·검색 범위)
- `pr-7-w1-design.md` §7 (종목 검색 정책 — 옵션 C raw code 저장)
- `pr-7-dispatch-spec.md` §4 (예시 페이로드)

**인터페이스 동결 즉시** backend·frontend 양쪽이 spec만 보고 동시에 자기 worktree에서 작업 시작 가능.

---

## 3. 파일 단위 충돌 행렬

### 3-1. PR-7a (backend-core) 변경 파일 vs PR-7b (frontend) 변경 파일

| 파일/디렉토리 | PR-7a | PR-7b | 충돌 유형 | 결정 |
|---|---|---|---|---|
| `api/src/main/resources/db/migration/V3__watchlist_position_refinements.sql` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/controller/WatchlistController.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/controller/dto/WatchlistDto.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/service/WatchlistService.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/WatchlistJpaEntity.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/WatchlistJpaRepository.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/WatchlistRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/InstrumentJpaEntity.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/InstrumentJpaRepository.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/persistence/InstrumentRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/resources/application.yml` | **변경 0 또는 Kafka producer 1줄 추가** | 무관 | (변경 0 또는 backend 단독) | **격리** |
| `api/src/test/kotlin/.../WatchlistControllerTest.kt` | **신규** | 무관 | add-only | **격리** |
| `api/src/main/kotlin/.../api/controller/PositionController.kt` (선택) | **신규** | 무관 | add-only | **격리** |
| `domain/src/main/kotlin/.../domain/position/port/KisBalancePort.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/src/main/kotlin/.../domain/position/KisBalance.kt` (또는 KisBalancePort.kt에 nested) | **신규** | 무관 | add-only | **격리** |
| `domain/src/main/kotlin/.../domain/position/KisHolding.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/src/main/kotlin/.../domain/position/port/PositionCachePort.kt` | **변경 0** (PR-5a에 이미 존재 가정) 또는 **신규 add-only** | 무관 | (변경 0 또는 add-only) | **격리** |
| `consumer/src/main/kotlin/.../consumer/bootstrap/PositionBootstrapRunner.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/kis/KisBalanceAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/kis/KisAuthAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/cache/RedisPositionCacheAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/persistence/PositionRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/persistence/UserKisCredentialJpaEntity.kt` (and Repository, Adapter) | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/persistence/KillSwitchRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/infrastructure/persistence/AuditTrailRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/kotlin/.../consumer/config/WebClientConfig.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/main/resources/application.yml` | **변경** (Redis · DB datasource 보강 — PR-5a에 stub만 있을 가능성) | 무관 | modify (backend 단독) | **격리** |
| `consumer/src/test/kotlin/.../PositionBootstrapRunnerTest.kt` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Watchlist/WatchlistPage.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/Watchlist/WatchlistEmpty.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/Watchlist/WatchlistRow.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/api/watchlist.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/api/hooks/useWatchlist.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/components/layout/AppHeader.tsx` (또는 `AppLayout.tsx`) | 무관 | **신규** | add-only | **격리** |
| `frontend/src/App.tsx` | 무관 | **변경** (`/watchlist` 라우트 추가 + AppHeader 적용) | modify (frontend 단독) | **격리** |
| `frontend/src/__tests__/watchlist-flow.test.tsx` | 무관 | **신규** | add-only | **격리** |
| `batch/**` | **변경 0** | 무관 | (변경 0) | **격리** |
| `docker/docker-compose.yml` | **변경 0 권장** | **변경 0 권장** | (변경 0) | **격리** |
| `docker/docker-compose.dev.yml` | **변경 0 권장** | **변경 0 권장** | (변경 0) | **격리** |
| `docker/docker-compose.infra.yml` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `scripts/build-and-deploy.sh` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.github/workflows/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.env.example` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `CHANGELOG.md` | **변경** (PR-7a 1줄 추가) | **변경** (PR-7b 1줄 추가) | **modify-shared** | **rebase 시 수동 정렬** (PR-6과 동일 패턴) |
| `docs/architecture/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `_workspace/arch/adr/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `_workspace/specs/pr-7-*.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (본 spec — architect 단독) |
| `_workspace/tasks/T-*-pr-7-w1-backend.md` | **신규** (자기 task) | 무관 | add-only | **격리** |
| `_workspace/tasks/T-*-pr-7-w1-frontend.md` | 무관 | **신규** (자기 task) | add-only | **격리** |
| `README.md` (루트) | **변경 0** | **변경 0** | (변경 0) | **격리** |

### 3-2. modify-shared 항목 분석

`CHANGELOG.md` 1건만 modify-shared 후보. 분석:

```markdown
| 2026-05-07 | feat(api,consumer,domain): PR-7a T-W1 backend ... | backend | T-W1 |
| 2026-05-07 | feat(frontend): PR-7b T-W1 frontend ... | frontend | T-W1 |
```

- 두 행은 추가 위치가 같은 영역(상단)이라 git rebase 시 conflict 가능
- 그러나 양쪽 모두 **add-only insert** 패턴 — `git rebase` 시 conflict marker 발생 시 양쪽 행 모두 보존하는 trivial resolve
- PR-6와 같은 패턴이 검증됨 — git auto-merge가 실제로 양쪽 새 줄 모두 keep하는 경우 다수
- **두 번째 PR rebase 책임자**: 두 번째로 머지되는 PR 작업자 (PR-7a가 먼저 머지 → PR-7b 작업자가 main rebase 후 force push)

**결론**: CHANGELOG.md 1건은 modify-shared지만 **충돌 해결 비용이 사실상 0** — 양쪽 행 모두 keep. 병렬 진행에 영향 없음.

### 3-3. 격리 검증 — backend·frontend가 같은 디렉토리에 손대는 경우 0건

- `api/**` 디렉토리는 backend 단독
- `consumer/**` 디렉토리는 backend 단독 (PR-7부터 본격 도입)
- `domain/**` 디렉토리는 backend 단독 (KisBalancePort 신규 add-only)
- `frontend/**` 디렉토리는 frontend 단독
- `docker/**`·`scripts/**`·`.github/**` 양쪽 변경 0
- `batch/**` 양쪽 변경 0

**결론**: PR-6과 동일 — 같은 디렉토리 동시 작성 0건. 진짜 병렬 가능.

---

## 4. pre-push hook 종속성 분석

PR-5 시리즈에서 직렬을 강제한 사유 중 하나가 "frontend가 backend 모듈 골격 없는 main에서 push 시 `./gradlew check` 실패". 본 PR-7에는 해당 문제 없음:

| 이유 | 분석 |
|---|---|
| PR-5a·PR-5b·PR-6 머지 완료 | main에 4 JVM 모듈 골격 + V1·V2 마이그레이션 + frontend Vite 설정 + Onboarding 진입점 모두 존재 — `./gradlew check` 통과 가능 |
| PR-7b가 frontend만 변경 | `./gradlew check` 영향 0 (Kotlin 변경 0) — pre-push 통과 |
| PR-7a가 backend만 변경 | frontend 디렉토리 변경 0 — frontend 측 검증 무관 |
| domain 모듈 변경 (KisBalancePort 신규) | add-only — 다른 모듈에서 import 안 한 상태로도 `:domain:check` GREEN |
| consumer 모듈 본격 도입 | PR-5a에 ConsumerApplication stub 존재 + PositionRepository·KillSwitchRepository port 존재. 본 PR-7에서 비로소 KIS 어댑터·Bootstrap·Adapter 진입 — `:consumer:check` 통과 가능 |

**결론**: pre-push hook은 양쪽 PR이 main을 base로 진행해도 모두 GREEN 가능. 직렬 종속성 없음.

---

## 5. 인터페이스 동결로 인한 병렬 가능 메커니즘

backend·frontend가 동시에 작업하더라도 다음 4가지가 양쪽이 같은 spec을 바라보고 진행되도록 보장:

### 5-1. REST 시그니처 LOCK (spec §4-1)

```
GET /api/watchlists?sort=priority|created → List<WatchlistEntryDto>
POST /api/watchlists (WatchlistAddRequest) → WatchlistEntryDto (201)
DELETE /api/watchlists/{code} → 204
POST /api/positions/bootstrap (선택) → PositionBootstrapResult
```

**Frontend 입장**: `useAddWatchlist()` 등 TanStack Query mutation을 `WatchlistAddRequest`/`WatchlistEntryDto` TypeScript 타입으로 작성 — backend 부재 시에도 msw mock으로 검증 가능.

**Backend 입장**: 위 시그니처를 100% 그대로 구현 — frontend 부재 시에도 `curl`로 검증 가능.

### 5-2. ErrorResponse 공통 포맷 (dispatch §4-2)

`{ code, message }` 통일. PR-6과 같은 코드명 체계 (`ONBOARDING_REQUIRED`·`KIS_AUTH_FAILED` 등) + 본 PR-7 신규 (`INVALID_TICKER_FORMAT`·`WATCHLIST_DUPLICATE`·`WATCHLIST_NOT_FOUND`).

### 5-3. UI 계약 LOCK (spec §6)

각 컴포넌트 책임·ticker validation·confirm 정책·정렬 룰이 spec에 lock — frontend가 backend 응답 mock하면서 진행 가능.

### 5-4. 예시 페이로드 (dispatch §4-3)

JSON 페이로드 예시를 양쪽이 공유 — TypeScript 타입 inference + Kotlin DTO 작성 시 같은 데이터 형태.

---

## 6. 병렬 가능한 시나리오별 검증

### 6-1. 시나리오 A — backend·frontend 동시 시작 + 동시 머지

```
T0: spec 머지 (architect)
T0+5분: backend 작업 시작 (worktree A)
T0+5분: frontend 작업 시작 (worktree B)
T+3시간: backend PR-7a 푸시 → CI green → 사용자 머지
T+3.5시간: frontend PR-7b 푸시 → CI green → 사용자 머지
```

**충돌 가능성**:
- CHANGELOG.md 1건 — frontend가 backend 머지 후 main rebase 시 1줄 추가 → 양쪽 행 keep (trivial resolve OR git auto-merge)
- 그 외 0건

**검증**:
- backend 머지 후 main에 V3 마이그레이션 + Watchlist REST + Bootstrap Runner 존재
- frontend rebase → main의 backend 변경에 영향 0 (frontend는 frontend/만 변경)
- frontend 머지 후 통합 검증 (DoD #1~#20) 가능

### 6-2. 시나리오 B — frontend 먼저 머지 (backend 미완)

```
T0: spec 머지
T+1.5시간: frontend PR-7b 푸시 → CI green (smoke test는 mock 기반) → 머지
T+3시간: backend PR-7a 푸시 → 머지
```

**검증**:
- frontend는 WatchlistPage UI + axios + TanStack Query만 — 빌드 GREEN
- 단 frontend만 머지된 시점에 `localhost:3000/watchlist` 진입 시 `useWatchlist()`가 backend 부재로 502 — UX 부조화 가능
- **architect 권장 = backend 먼저 머지 + frontend 즉시 후속** (시나리오 C) — 사용자 검증 단순

### 6-3. 시나리오 C — backend 먼저 머지 + frontend 즉시 후속 (권장)

```
T0: spec 머지
T0+5분: 양쪽 동시 시작
T+3시간: backend PR-7a 푸시 → 머지
T+3.5시간: frontend PR-7b rebase → 머지
```

**검증**: 시나리오 A와 동일하나 머지 순서 명시. 양쪽이 같은 시간 동안 작업하니 효율 동일.

### 6-4. 시나리오 D — 직렬 (권장하지 않음)

```
T0: spec 머지
T0+5분: backend 시작
T+3시간: backend 머지
T+3시간 5분: frontend 시작
T+5시간: frontend 머지
```

**왜 권장하지 않는가**:
- 사용자 메모리 `feedback_orchestrator_active_parallel.md` — "직렬 권장 답습 금지"
- 인터페이스 동결로 양쪽이 즉시 시작 가능
- 직렬은 총 작업 시간 1.5배 (~5시간 vs 병렬 ~3.5시간)

---

## 7. 사용자 메모리 정합 검증 (필수)

| 사용자 메모리 | 본 결정 정합 |
|---|---|
| `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지 + 사용자 의도+실제 충돌 행렬 재검토 후 적극 병렬화 | ✅ — 본 행렬에서 modify-shared 1건(CHANGELOG)만 식별 + trivial resolve 사유 명시 + 병렬 권장 결론 |
| `feedback_3tier_hierarchy.md` — 오케스트라 → architect(설계) → 실무 sub-agents(병렬 실행) | ✅ — architect는 본 spec 3종만 발행, 코드 0건. backend·frontend가 병렬로 코드 작성 |
| `feedback_autonomy.md` — Claude 자율 실행, 사용자 확인은 아키텍처·외부자원·블로킹상충 3가지만 | ✅ — 본 spec에 사용자 결정 필요 사항 0건 (ADR 0042·0028·0040 모두 사용자 명시 정책 정합 + 종목 검색 정책 옵션 C는 architect 자율 결정으로 spec §7에 사유 명시) |
| `feedback_v3_skepticism_no_regression.md` — 자동매매 적합성 의심 lens | 자동매매 적합성: ✅ 본 PR-7은 magicJar 1순위 진입점(Watchlist) + 부팅 잔고 워밍(자동매매 안전망) — 핵심 자동매매 기능. v1/v2 잔재 0건. fitness gap 0 |
| `feedback_kis_first_policy.md` — KIS MCP·open-trading-api 베이스, tr_id·endpoint 자체 추론 금지 | ✅ — 본 PR-7의 KIS endpoint(`/uapi/domestic-stock/v1/trading/inquire-balance` TR `TTTC8434R`/`VTTC8434R`)는 ADR 0042 §2-2에 출처 명시(`examples_llm/.../inquire_balance.py`) |
| `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 | ✅ — backend는 실 KIS 호출, frontend는 backend 응답 그대로 표시. 잘못된 ticker는 T-W2 시세 수집 시점에 "데이터 없음" 표시 (옵션 C 정합) |
| `feedback_secret_handling.md` — 키 평문 응답 reflect 금지 | ✅ — 본 PR-7의 KIS REST 응답에 키 평문 reflect 0건 (PR-6 정합) + KisAuthAdapter 메모리 zero-fill 의무 |
| `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES, 본격 운영 시 secret manager 재논의 | ✅ — 본 PR-7은 PR-6 등록된 AES BYTEA 키를 복호화해 KIS 호출. SettingsModal에서 키 갱신·재등록은 후속 트랙 |
| `feedback_qa_on_demand.md` — MVP 단계 QA 게이트 미적용, 코드 품질은 린트·테스트·셀프리뷰 | ✅ — 본 PR-7 양쪽은 smoke 1~2건만 + ktlintCheck + 셀프 리뷰. R3 일괄은 T-S1 또는 별도 묶음 |

---

## 8. 결론 — 권장 디스패치 모드

### 8-1. 1차 권장 — **완전 병렬 (시나리오 A 또는 C)**

오케스트라가 spec 머지 후 즉시 양쪽 동시 디스패치:

```bash
# Terminal 1 (backend worktree)
git worktree add .claude/worktrees/agent-pr7-backend agent/backend-core/pr-7-w1-backend
cd .claude/worktrees/agent-pr7-backend
node scripts/task.mjs start --agent backend-core --branch agent/backend-core/pr-7-w1-backend ...

# Terminal 2 (frontend worktree, 동시)
git worktree add .claude/worktrees/agent-pr7-frontend agent/frontend/pr-7-w1-frontend
cd .claude/worktrees/agent-pr7-frontend
node scripts/task.mjs start --agent frontend --branch agent/frontend/pr-7-w1-frontend ...
```

### 8-2. 2차 권장 — **시나리오 C** (backend 먼저 머지 + frontend rebase 후 즉시 머지)

머지 순서만 명시하고 작업 시간 동일.

### 8-3. 직렬은 권장하지 않음

직렬 권장 시 사유 입증 의무:
- modify-shared가 진짜로 어디인지 → CHANGELOG 1건뿐 (trivial)
- 인터페이스 동결 미달 → spec 3종이 동결 보장
- pre-push hook 종속 → PR-5·PR-6 머지 완료로 양쪽 main에서 GREEN

**위 3건 모두 미충족 — 직렬 권장 사유 부재.**

---

## 9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

### 9-1. PR-7a 머지 시점

- [ ] PR-7b가 main rebase 가능한 상태 (충돌 없음 또는 CHANGELOG 1줄만)
- [ ] PR-7a CI green
- [ ] spec §11-1 backend 검증 게이트 15건 모두 ✅
- [ ] PR-7b 작업자에게 "main rebase + force push" 안내 (작업자 worktree 격리됨)

### 9-2. PR-7b 머지 시점

- [ ] PR-7b CI green
- [ ] spec §11-2 frontend 검증 게이트 9건 모두 ✅
- [ ] 양쪽 머지 후 통합 게이트 (spec §11-3) 6건 모두 ✅ — 사용자 본인 키로 수동 검증

### 9-3. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG.md 정합 확인 (양쪽 add-only 행 보존)

---

## 10. 발견된 함정·위험 (사용자 알아야 할 것)

PR-6 진행 중 확인된 함정 + 본 PR-7에서 새로 식별된 위험. 솔직 보고:

### 10-1. CHANGELOG.md 양쪽 add-only insert는 GitHub UI에서 conflict로 인식 (PR-6 발견)

PR-6 머지 시 확인됨 — 양쪽 작업자가 CHANGELOG 상단에 1줄씩 추가하면 GitHub은 conflict marker로 표시. 로컬에서 `git rebase main` 후 양쪽 행 keep + force push 의무.

**처리 책임자**: 두 번째로 머지되는 PR 작업자. PR-7도 같은 패턴.

### 10-2. consumer 모듈 본격 도입 — DB datasource·Redis config 부재 가능성

PR-5a에서 consumer 모듈은 ApplicationStub만 — application.yml에 DB·Redis config가 미흡할 수 있음. 본 PR-7a에서 PositionRepositoryAdapter·RedisPositionCacheAdapter 도입 시 `consumer/src/main/resources/application.yml`에 datasource·redis 추가 필요.

**처리**: backend-core가 PR-7a 작업 초기에 application.yml 점검 + 누락 시 추가. spec §3-2 (V3 마이그레이션) 외 별도 commit 권장 ("chore(consumer): application.yml datasource·redis config 보강").

### 10-3. KIS REST 호출 mock 검증 어려움

본 PR-7a의 KisBalanceAdapter는 실 KIS 호출. CI 환경에선 KIS endpoint 접근 불가 → 실제 KIS 호출 검증은 사용자 본인 환경에서만 가능.

**처리**: backend-core는 WireMock 또는 단순 unit test로 KisBalanceAdapter 단위 검증 (mock JSON 응답으로 매핑 로직 검증). 통합 검증 (DoD #12·#13·#14)은 사용자 자체 검증 의무.

### 10-4. UQ(user_id, code) 위반 시 409 응답 — JPA exception 매핑 의무

`watchlistRepository.add(entry)` 시 `DataIntegrityViolationException` 발생 → controller에서 409로 매핑 의무. 미매핑 시 500 응답 (UX 저하).

**처리**: WatchlistService에서 try-catch 또는 `@RestControllerAdvice` 글로벌 핸들러로 매핑. backend-core 자율 결정.

### 10-5. instrument FK CASCADE 정책 정합

V1 baseline: `watchlist`·`position`은 `instrument(code)` FK 참조. ON DELETE 정책 미명시 (default = NO ACTION). T-IM1 진입 시 stub instrument를 정식 데이터로 UPDATE — DELETE 안 함 → CASCADE 정책 무관.

**확인**: 본 PR-7은 instrument 행을 DELETE 안 함 (UPDATE만). T-IM1 진입 시 CASCADE 정책 재검토.

### 10-6. consumer 모듈 부팅 시간 — Bootstrap이 KIS 호출 N명 직렬

활성 user N명 × 1~2초 = N~2N초 부팅 latency. N=10명 시 10~20초 부팅 시간 소요 → docker compose healthcheck timeout 검토 필요.

**처리**: 본 PR-7은 1차 single-tenant — N=1명 (≤ 2초). post-MVP에서 N≥10 시 ADR 0042 §3-3 모니터링 임계 (≥ 20초 시 alert) 도입.

### 10-7. PositionCachePort domain port 부재 가능성

spec §5-5는 `domain/.../position/port/PositionCachePort.kt`가 PR-5a에 존재 가정. 실제 도메인 디렉토리 점검 시 `port/PositionCachePort.kt` 파일이 존재 (확인됨).

**처리**: 만약 부재 시 PR-7a에서 add-only 신규 (architect 자율 — domain 신규 add는 안전).

### 10-8. selectivity — Redis 분산락 30s timeout

KisAuthAdapter 분산락 timeout 30s — 동시 부팅 시 락 보유자가 KIS REST 응답 5s + cache write 1s = 6s 정도면 충분. 30s는 여유. 단 락 보유자 사망 시 대기자가 30s 대기 후 cache 재조회 → 부팅 latency 증가. 본 PR-7은 1차 single-tenant — 동시 부팅 거의 없음 → 영향 미미.

**처리**: backend-core가 KisAuthAdapter spec §5-3 그대로 구현. ADR 0038 정합 — 분산락 timeout·재시도 정책은 ADR 0038에 정의.

### 10-9. Watchlist 중복 등록 시점 race condition

동시 2개 요청 (같은 user_id·code) 시 둘 다 EXISTS 체크 통과 후 INSERT → UQ violation 1건 발생 → 한 쪽만 409. 정합 OK.

**처리**: WatchlistService는 EXISTS 체크 안 하고 바로 INSERT + DataIntegrityViolationException catch → 409. 또는 EXISTS 체크 후 INSERT (race condition 무시) — UQ가 어차피 보호. backend-core 자율.

### 10-10. ADR 0040 endpoint 명명 불일치 — `/api/watchlist` vs `/api/watchlists`

ADR 0040 §2-4·§3 outline은 `/api/watchlist/{code}` 단수형. 본 PR-7은 `/api/watchlists` 복수형 + `/{code}` (RESTful 표준).

**처리**: ADR 0040 본문 update는 본 PR 비범위. post-merge 시 별도 docs PR로 ADR 0040 §2-4 endpoint 명명 정정 (architect 후속 처리). 본 PR-7 spec이 권위.

---

## 11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-7 충돌 행렬 v1 발행. 결론: **PR-7a + PR-7b 완전 병렬 권장**. modify-shared 1건(CHANGELOG.md)만 trivial resolve. add-only 충돌은 진짜 병렬 가능 — 사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합. 발견된 함정 10건 명시 (architect, opus 4.7 1M). |
