# Task 파일 시스템

각 작업의 의도(goal)·결과물 체크리스트·진척·블로커를 한 파일에 살아있게 기록한다.
대시보드(`_workspace/dashboard.html`)가 이 파일들을 파싱해 사용자에게 노출한다.

## 흐름

```
agent/<agent>/<task> 브랜치 생성 직후
   │
   └─ node scripts/task.mjs start --agent <a> --goal "..." --deliverables "...; ...; ..."
        │
        └─ _workspace/tasks/T-YYYYMMDD-HHMM-<slug>.md 생성

작업 진행 중
   ├─ task.mjs check "<체크리스트 항목 일부>"   → 토글 [x]
   ├─ task.mjs touch "방금 한 일 한 줄"
   └─ task.mjs block --reason "..." (필요 시)

PR 생성 시
   └─ task.mjs done --pr <N>

PR 머지 후
   └─ task.mjs archive <id>     → done/ 으로 이동
```

## 파일 형식

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
Gradle 멀티모듈 뼈대 + core 도메인/포트 + app 부팅

## 결과물 체크리스트
- [x] settings.gradle.kts (8 modules)
- [x] core 도메인 record 13종
- [ ] app/Application.kt
- [ ] core 단위 테스트

## 의존성
- ADR 0001~0008 합의 완료

## 마지막 활동
2026-04-25T16:32 — core 도메인 모델 13종 확정

## 비고
```

## 디렉토리 구조

```
_workspace/tasks/
├── README.md                         # 이 파일
├── T-20260425-1605-...md              # 활성 task
├── T-20260425-1620-...md              # 활성 task
└── done/                              # 머지 후 archive
    ├── T-20260424-...md
    └── ...
```

## 명령 한 번에 보기

| 명령 | 효과 |
|------|------|
| `node scripts/task.mjs start --agent A --goal "..."` | 새 task 생성 |
| `node scripts/task.mjs check "<항목>"` | 체크리스트 토글 |
| `node scripts/task.mjs touch "<메모>"` | 마지막 활동 갱신 |
| `node scripts/task.mjs block --reason "..."` | BLOCKED 상태 |
| `node scripts/task.mjs done --pr N` | DONE 상태 + PR 연결 |
| `node scripts/task.mjs archive T-...` | done/ 으로 이동 |
| `node scripts/task.mjs list` | 활성 task 목록 |

## 지키는 룰

- 새 작업 시작 시 task 파일 **없이 진행 금지**. 대시보드의 "고아 worktree" 경고 대상.
- DONE 후 머지 안 됐어도 `archive`는 PR 머지 후에만.
- BLOCKED 상태는 사용자/오케스트레이터의 개입 신호. 방치 금지.
- task 파일은 사후 수정 OK (스킬·일정 변경 시) — 다만 frontmatter의 `id`·`startedAt`은 불변.
