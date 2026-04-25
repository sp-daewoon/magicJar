# ADR 0018: Task 파일 시스템

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (사용자 직접 진단)

## 맥락

대시보드(`_workspace/dashboard.html`)가 보여주는 데이터:
- worktree 폴더의 last commit 시각·subject (사후적 흔적)
- 에이전트 정의 (정적 역할 설명)
- 열린 PR 제목 (PR 만들어진 후에야 보임)

빠진 것 = **의도(intent)·결과물(deliverables)·진척(progress)**.
작업이 시작되어 PR 생성되기 전 구간이 깜깜. 사용자가 정작 알고 싶은 "지금 어떤 결과물을 위해 일하는가"가 안 보임.

사용자 표현: "내가 페이지 보면 어떤 결과물을 위해 일을 하는구나를 알 수 있도록."

## 결정

**작업 단위마다 `_workspace/tasks/<id>.md` 파일을 만들고, 그 파일이 작업의 살아있는 기록이 되게 한다.**

### 파일 위치

```
_workspace/tasks/
├── README.md                          # 사용 가이드
├── T-YYYYMMDD-HHMM-<slug>.md           # 활성 task (status ≠ DONE)
└── done/                               # PR 머지 후 archive
    └── T-...md
```

### 파일 양식

```markdown
---
id: T-20260425-1605-scaffold-multimodule
agent: backend-core
model: sonnet
branch: agent/backend-core/scaffold-multimodule
status: RUNNING        # PLANNED | RUNNING | BLOCKED | DONE
startedAt: 2026-04-25T16:05:00.000Z
completedAt: null
pr: null
parentPhase: B
---

# 목표
<한 문단으로 이 작업이 만들려는 결과>

## 결과물 체크리스트
- [x] 완료 항목
- [ ] 남은 항목

## 의존성
- (선행 task·외부 자원 등)

## 마지막 활동
<ISO> — <한 줄 메모>

## 비고
```

### CLI 헬퍼 (`scripts/task.mjs`)

| 명령 | 효과 |
|------|------|
| `task.mjs start --agent A --goal "..." --deliverables "...; ..."` | task 파일 생성, 모델은 에이전트 frontmatter에서 자동 가져옴 |
| `task.mjs check "<항목>"` | 체크리스트 토글 |
| `task.mjs touch "<메모>"` | 마지막 활동 갱신 |
| `task.mjs block --reason "..."` | BLOCKED 상태 |
| `task.mjs done --pr N` | DONE + PR 연결 |
| `task.mjs archive T-...` | done/ 으로 이동 |
| `task.mjs list` | 활성 task 목록 |

### 에이전트 의무

각 에이전트 정의(`.claude/agents/<name>.md`)에 **"작업 시작 시 task 파일 생성"** 의무 추가. 오케스트레이터가 sub-agent 호출 시 동일하게 지시.

### 강제 메커니즘 (가볍게)

너무 강제하면 형식주의 → 부드러운 강제:

1. **에이전트 정의·오케스트레이터 스킬에 명시** (task 파일 먼저 만들고 시작)
2. **대시보드 경고** — task 파일 없는 활성 worktree/브랜치 발견 시 노란 배너
3. **PR 본문 템플릿** — "관련 task: T-..." 한 줄 (없으면 본인이 한 줄 작성)
4. **git hook으로 강제 안 함** — pre-commit에서 막으면 짜증 비용 큼

### 대시보드 통합

`scripts/render-status.mjs`가 `_workspace/tasks/*.md`를 파싱하여 활성 Task 카드를 dashboard.html에 노출:

- 진척률 (체크리스트 N/M)
- 마지막 활동 시각
- 모델·branch·agent
- BLOCKED 상태는 빨간 배너로 강조
- 최근 완료된 task (`done/` 디렉토리, 24시간 내) 별도 섹션

### task ID 규칙

`T-YYYYMMDD-HHMM-<slug>` (KST 기준):
- `T-20260425-2213-harness-v2-six-pack`
- slug는 brach의 마지막 segment에서 자동 생성 (kebab-case, ≤40자)

## 결과

- 긍정: 사용자 가시성 폭증, 에이전트 자기 자신의 진행 추적, 회고 시 작업 단위별 컨텍스트 보존
- 부정: 작업 시작 시 한 단계 추가 (CLI 한 줄). 익숙해지면 무시할 수 있음
- 후속:
  - `scripts/task.mjs` 구현 ✓ (이 PR에서)
  - `scripts/render-status.mjs` task 파싱 추가
  - 모든 에이전트 정의에 task 파일 의무 명시
  - 본 ADR 채택의 첫 task = `T-20260425-2213-harness-v2-six-pack` (이 PR 자체, dogfooding)
