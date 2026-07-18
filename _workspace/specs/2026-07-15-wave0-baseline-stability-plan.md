# Wave 0 Baseline Stabilization Plan

> **Task**: `T-20260715-2242-wave0-baseline-stability`
> **Branch**: `chore/wave0-baseline-stability`
> **Approval**: 2026-07-15 사용자 Wave 0 승인

## Goal

매매·리스크·주문·KIS·DB·배포 동작을 변경하지 않고 frontend 테스트 환경, 권위 문서, task 위생, architecture fitness 검사를 안정화한다.

## Immutable boundary

다음 영역은 변경 금지다.

- 전략 DSL, 진입·청산 조건, RiskPlan, 포지션 사이징
- KillSwitch, 주문·체결, KIS REST/WS, Kafka 자본 payload
- Flyway/DB schema, REAL/MOCK 설정, feature flag 기본값, 배포
- 기존 미추적 `.omc/`, `__prompts/`, `docs/architecture/.omc/`, 생성 HTML, deploy draft

금지 영역 변경이 필요해지면 즉시 중단하고 사용자에게 재승인을 요청한다.

## Evidence baseline

- JVM: `./gradlew check` — 3,036 tests, 0 failures, 1 skipped
- Frontend build: 통과
- Node 26.5.0 기본 test: `localStorage` undefined로 96 failures
- `NODE_OPTIONS=--localstorage-file=<tmp>`: 진단상 795/795지만 worker 공유 저장소라 정식 해결책에서 제외
- fresh worktree 좁은 3-file run: 27/27 pass
- worker-local storage + maxWorkers=4 적용 후 Node 26 host에서 797/797 연속 3회 통과
- Node 24.18.0 Alpine 격리 환경에서 797/797 통과(기존 795 + 환경 계약 2)

## Root-cause hypothesis

Node 26의 process-global web storage와 JSDOM `window.localStorage`가 경쟁한다. 동일 `--localstorage-file`을 Vitest worker들이 공유하면 다른 테스트의 `clear()`가 STOMP 테스트의 비동기 anchor write/assert 사이에 끼어든다. 테스트 환경은 Node process storage가 아니라 worker별 JSDOM storage를 권위로 강제해야 한다.

## Task 1 — Deterministic Web Storage (TDD)

### RED

- Create `frontend/src/__tests__/testEnvironment.test.ts`.
- Assert `globalThis.localStorage === window.localStorage`.
- Assert a round trip through global storage is visible through window storage.
- Run with plain Node 26 and confirm expected failure before setup fix. Node 26은 비-LTS/IPC 불안정 때문에 지원 표준으로 채택하지 않는다.

### GREEN

- Modify `frontend/src/__tests__/setup.ts` to install worker-local Storage and bind `globalThis`, `window`, `document.defaultView` to the same local/session instances.
- Do not modify runtime hooks or product behavior.
- Run the environment test, three former flaky files, then full suite three times without `NODE_OPTIONS`.

### Toolchain contract

- Pin frontend verification to tested Node `24.18.0` in `frontend/.node-version`; pre-push rejects any different Node version. Existing Docker Node 20 remains an explicitly documented deployment-scope exception.
- Keep `pnpm@9.15.9` via existing `packageManager`.
- Document Corepack command; do not add a second package manager.

## Task 2 — Frontend warning baseline

- Do not broaden scope into all existing MSW/React warnings.
- Record existing warnings as follow-up only.
- `corepack pnpm build` must pass.
- Test count must remain at least 795; no skip/delete/assertion weakening.

## Task 3 — Authoritative documentation sync

현행 문서만 수정하고 역사 snapshot·ADR 본문은 제외한다. 대상:

- `README.md`
- `docs/architecture/master-architecture.md`
- `docs/architecture/policies/audit-remediation-tracker.md`
- `_workspace/INDEX.md` only if an entrypoint correction is required
- `docs/operations/initial-setup.md`, `docs/operations/runbook/R5-redeploy.md`
- `docs/architecture/policies/task-cycle.md`, `_workspace/arch/{agent-charter,branching-rules}.md`

Corrections:

- v3.17.0/current HEAD facts
- 9 JVM modules, not 4
- current ADR index through 0140
- remove DRAFT/SoT contradiction
- update 2026-07-03 remediation states from current code evidence
- link code registries instead of duplicating volatile counts where practical

No policy semantics or trading behavior changes.

## Task 4 — Non-destructive task hygiene

- Wave 0에서는 기존 task를 자동 archive/delete/move하지 않는다.
- `status: DONE`도 PR/merge evidence와 사용자 명시 승인 전에는 이동하지 않는다.
- RUNNING/BLOCKED의 경과시간 기반 완료 추정과 `--force` archive를 금지한다.
- Add a read-only doctor/check that reports active counts, malformed metadata, and DONE files left in active root.
- Current Wave 0 task remains active until PR/report stage.

## Task 5 — Architecture fitness checks

Add a read-only Node check and tests for current invariants:

- `settings.gradle.kts` declares the expected 9 modules
- `domain/src/main` has no Spring/JPA imports
- `api`, `batch`, `consumer` do not directly depend on each other
- shared library는 domain만 의존하고 module graph cycle이 없음
- Spring Boot plugin/main은 api·batch·consumer에만 허용
- Kotlin runtime Kafka wire literal은 `KafkaTopics.kt` registry만 소유하고, YAML/properties 값은 registry 등록값과 일치
- README module statement matches settings authority
- known structural debt such as duplicate position entities is reported separately, not silently normalized or fixed in Wave 0

The check must not scan secrets or mutate files.

## Task 6 — Independent review and final verification

Run:

```bash
./gradlew check
cd frontend
# Node 24.18.0 격리 환경
corepack pnpm test
corepack pnpm typecheck
corepack pnpm build
```

Additional gates:

- full frontend suite 3 consecutive passes
- no test count reduction
- forbidden-path diff guard
- secret scan on added lines
- independent reviewer receives the diff only
- maximum two review-fix cycles

## Stop conditions

Stop and request user approval if:

- a remaining failure requires product STOMP/trading behavior change
- DB migration or deployment change becomes necessary
- forbidden capital-flow files appear in the diff
- JVM test count drops
- same failure persists after two targeted fix attempts
- existing untracked files conflict

## Delivery

- No direct main edits
- No automatic merge
- Present diff stat, changed files, test evidence, reviewer verdict, remaining risks
