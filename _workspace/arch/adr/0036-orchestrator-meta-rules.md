# ADR 0020 — Orchestrator Meta Rules (git-author + task-file-system + architect-as-orchestrator)

- 상태: Accepted
- 날짜: TBD

## 1. 컨텍스트

magicJar 하네스는 6명 전문 에이전트가 architect의 조율로 작업한다. 운영 메타 규약(에이전트 author·작업 단위 파일·architect 일체화)을 단일 ADR로 묶어 발행한다.

## 2. 결정

### 2-1. Git Author per Agent

각 에이전트는 자기 commit author로 push한다. Husky `pre-commit`이 화이트리스트로 강제.

| Author | 용도 |
|--------|------|
| 사용자 직접 author | 사용자 직접 commit |
| `bot+architect@magicjar.local` | architect 에이전트 |
| `bot+backend-core@magicjar.local` | backend-core |
| `bot+market-data@magicjar.local` | market-data |
| `bot+strategy@magicjar.local` | strategy-engine |
| `bot+ai@magicjar.local` | ai-engineer |
| `bot+frontend@magicjar.local` | frontend |

### 2-2. Task 파일 시스템

작업 단위는 `_workspace/tasks/T-<YYYYMMDD>-<HHmm>-<slug>.md`. CLI는 `scripts/task.mjs`. 종료 시 `_workspace/tasks/done/`로 이동.

### 2-3. Orchestrator = architect

`architect` 에이전트가 magicJar 메인 오케스트레이터다. 모든 비트리비얼 작업은 magicjar-orchestrator 스킬 → architect 에이전트 dispatch.

## 3. 결과

- pre-commit author whitelist로 ADR 위반 0
- 작업 추적성 확보 (PR 본문 task.id 명시)
- 메인 세션은 dispatch 결정만, 실 작업은 architect/sub-agent

## 4. 참고

- `scripts/task.mjs` — Task CLI
- `.husky/pre-commit` — author whitelist 강제
