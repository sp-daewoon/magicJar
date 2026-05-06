# ADR 0036 — Orchestrator Meta Rules (3단 hierarchy + git-author + task-file-system)

- 상태: Accepted
- 작성일: 2026-04-25 (초안: orchestrator=architect 1인 2역)
- 갱신일: 2026-05-07 (3단 hierarchy 정식화 + sub-agent task 표준 + qa·orchestrator author 추가)
- 결정자: architect (사용자 — 최고 관리자 결정 반영)
- 관계: ADR 0033 (worktree 정책) 정합

## 1. 컨텍스트

magicJar 하네스는 7명 전문 에이전트(architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa)가 협업한다. v1·v2 시기 운영 메타 규약은 다음을 묶어 발행했었다:

- 에이전트별 git author whitelist (pre-commit 강제)
- 작업 단위 파일 시스템 (`_workspace/tasks/*.md`)
- "Orchestrator = architect" 1인 2역 원칙

v3 (2026-05-06 베이스라인) 시점에 다음 변화로 메타 규약 재발행이 필요해졌다:

- **오케스트라 분리** — `magicjar-orchestrator` 스킬이 architect와 별개의 디스패치 레이어로 자리잡음
- **qa 7번째 에이전트 합류** — author whitelist 누락 상태
- **병렬 디스패치 도입** (ADR 0033 v3) — 다수 sub-agent 동시 작업의 task ID 충돌 안전성 필요
- **사용자(최고 관리자) 의사결정 비중** — 자율 실행과 사용자 결정 게이트의 명확한 분리 필요

## 2. 결정

### 2-1. Git Author per Agent

각 에이전트는 자기 commit author로 push한다. Husky `pre-commit`이 화이트리스트로 강제. v3에서 qa·orchestrator 추가.

| Author | 용도 |
|--------|------|
| `sp-daewoon@gmail.com` | 사용자 직접 commit (메인) |
| `seonjupark94@gmail.com` | 사용자 직접 commit (보조) |
| `bot+architect@magicjar.local` | architect 에이전트 |
| `bot+backend-core@magicjar.local` | backend-core 에이전트 |
| `bot+market-data@magicjar.local` | market-data 에이전트 |
| `bot+strategy@magicjar.local` | strategy-engine 에이전트 |
| `bot+ai@magicjar.local` | ai-engineer 에이전트 |
| `bot+frontend@magicjar.local` | frontend 에이전트 |
| `bot+qa@magicjar.local` | qa 에이전트 (on-demand 진입 시) |
| `bot+orchestrator@magicjar.local` | 오케스트라 메타 작업 (디스패치·라우팅·정리) |

위반 시 commit reject. 머지 정책은 `--rebase` (squash가 author를 web user로 덮어쓰는 문제 회피).

### 2-2. Task 파일 시스템 — Sub-agent별 독립 발급 표준

작업 단위는 `_workspace/tasks/T-<YYYYMMDD>-<HHmm>-<slug>.md`. CLI는 `scripts/task.mjs`. 종료 시 `_workspace/tasks/done/`로 이동.

**v3 표준 — 병렬 디스패치 안전성:**

- **각 sub-agent (architect 포함 7명)는 자기 task를 독립 발급**한다. 오케스트라가 분해한 작업 단위마다 1 task 파일.
- **task ID 충돌 방지** — `T-<YYYYMMDD>-<HHmm>-<slug>` 형식에서 `<HHmm>` 동일 분에 다수 sub-agent가 start할 수 있다. slug에 agent 이름을 포함하거나 작업 명칭을 구체화해 충돌 회피.
  - 권장: `T-20260507-0030-architect-pr-0-meta` / `T-20260507-0030-frontend-chart-fix`
  - 비권장: `T-20260507-0030-task` / `T-20260507-0030-fix`
- **병렬 디스패치 시** 각 sub-agent가 자기 worktree·자기 브랜치에서 독립적으로 task start. 오케스트라는 충돌 행렬 점검만 수행 (ADR 0033 §결정 참조).
- **자기 git author로 task 파일 commit** — task.md 자체도 commit 대상. 해당 sub-agent의 `bot+<agent>@magicjar.local`으로 author 설정 후 commit.
- **머지 후** `_workspace/tasks/done/`로 이동. `node scripts/task.mjs archive <task-id>` 또는 후속 정리 PR.

CLI 명령 매핑:

```bash
# 각 sub-agent가 자기 작업 시작 시
node scripts/task.mjs start --agent <agent> --branch agent/<agent>/<slug> \
  --goal "..." --deliverables "결과물1, 결과물2, 결과물3"

# 결과물 한 개 완료
node scripts/task.mjs check "<항목>"

# 진척 메모
node scripts/task.mjs touch "<메모>"

# blocker 발생
node scripts/task.mjs block --reason "..."

# PR 생성 후
node scripts/task.mjs done --pr <N>

# 머지 후 정리 (오케스트라 또는 사용자)
node scripts/task.mjs archive T-<id>
```

### 2-3. 3단 Hierarchy 정식화

v1·v2의 "Orchestrator = architect" 1인 2역 원칙을 **폐기**한다. v3는 다음 3단으로 분화한다.

```
[최고 관리자 (사용자)]
        │  ── 비전·우선순위·외부 자원 결정·블로킹 충돌 해결
        ▼
[오케스트라 (magicjar-orchestrator 스킬)]
        │  ── 작업 분해·디스패치·충돌 행렬·worktree 라이프사이클·머지 후 정리
        ▼
[architect (수석 아키텍트)]
        │  ── 설계·ADR·모듈 경계·기술 선택 (구현 X, 문서만)
        ▼
[실무 sub-agents (6명)]
   backend-core · market-data · strategy-engine
   ai-engineer · frontend · qa
        ── 각자 영역 구현·테스트·운영
```

**각 단의 책임:**

| 단 | 책임 | 결정 권한 |
|----|------|----------|
| 최고 관리자 (사용자) | 비전·우선순위·외부 자원·아키텍처 충돌 최종 결정 | 무한 |
| 오케스트라 | 작업 분해·디스패치·충돌 점검·worktree·머지 후 정리·git workflow 자율 결정 | 자율 (사용자 메모리 `feedback_autonomy.md` 영역) |
| architect | 설계 일관성·ADR 발행·모듈 경계·기술 선택 근거 | 설계 영역 자율, 구현 X |
| sub-agents | 자기 영역 구현·테스트·로컬 운영·자기 task·자기 PR | 자기 영역 자율 |

**충돌 행렬 점검 의무 (오케스트라):**

작업 디스패치 전 다음 항목을 점검하여 병렬 vs 직렬 결정 (ADR 0033 §결정 케이스 참조):

1. 같은 파일·디렉토리 수정 여부
2. 같은 모듈 빌드 의존성 변경 여부
3. ADR 간 강결합 (한 PR로 묶이는 게 자연스러운지)
4. SoT 문서 동시 수정 여부
5. `_harness-state.json` 동시 갱신 여부

위 중 하나라도 해당하면 **직렬**. 모두 해당 없으면 병렬 디스패치 가능.

**사용자 결정 게이트 (사용자 메모리 `feedback_autonomy.md` 정합):**

- 아키텍처 변경 (새 ADR·기존 ADR 폐기)
- 외부 자원 도입·변경 (KIS 키·DART 키·LLM provider 등)
- 블로킹 상충 (sub-agent 간 또는 ADR 간 해결 불가 충돌)

위 3개 외 모든 git workflow·작업 분해·디스패치 결정은 오케스트라 자율 영역.

## 3. 결과

- **pre-commit author whitelist로 ADR 위반 0** — qa·orchestrator 행 추가로 v3 누락 해소
- **작업 추적성 확보** — PR 본문에 task.id 명시, 병렬 디스패치 시 각 sub-agent task 독립 추적
- **3단 분화로 책임 명확** — 최고 관리자(전략)·오케스트라(운영)·architect(설계)·sub-agents(실무)가 자기 영역에서 자율 결정. v1·v2의 architect 1인 2역 부담 해소
- **병렬 디스패치 안전성** — task ID 충돌 방지 표준 + 충돌 행렬 점검 의무로 동시 작업 가능

## 4. 참고

- `scripts/task.mjs` — Task CLI (멀티 에이전트 호환, agent 7명 모두 처리)
- `.husky/pre-commit` — author whitelist 강제 (ADR 0032)
- ADR 0033 — Worktree 정책 (병렬 허용 + 정리 의무) 정합
- `_workspace/arch/branching-rules.md` — 브랜치·머지·author 정책 실전 가이드
- 사용자 메모리 `feedback_autonomy.md` — 자율 실행 영역 정의
- 사용자 메모리 `feedback_worktree_cleanup.md` — 머지 후 정리 의무
