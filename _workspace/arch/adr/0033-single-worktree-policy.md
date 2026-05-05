# ADR 0014: 단일 Worktree + 순차 작업 정책

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (사용자 직접 진단 반영)
- 관계: ADR 0009 보완

## 맥락

Phase B 동안 7개 에이전트가 각자 `.claude/worktrees/agent-*` 별도 worktree에서 동시 작업하는 모델로 운영했다. 결과:

- **토큰 폭증**: 각 worktree마다 에이전트가 풀 컨텍스트(시스템 프롬프트·도구·기억) 적재 → 7배 비용
- **격리 가치 미실현**: 모듈이 모두 `core`에 의존하는 모놀리식이라 진짜 병렬은 환상. 머지 단계에서 같은 `build.gradle.kts`·`application.yml`을 수정해 결국 충돌
- **상태 동기화 어려움**: `_harness-state.json`이 여러 곳에서 동시 갱신
- **머지 시간 증가**: PR이 한꺼번에 몰려 사용자 승인 부담 누적

사용자 표현: "토큰의 막대한 소모만 생기고 있어. 자체적 에이전트가 자기 브랜치에서 처리하고 문제 있으면 너(최고책임자)가 분석·체크·돌려보내는 모델을 원했던 건데."

## 결정

**기본은 단일 메인 worktree + 브랜치 전환으로 순차 작업.**

```
[메인 worktree 1개]
  ├─ git switch -c agent/<a>/<task>
  ├─ 작업·커밋·push·PR
  ├─ 머지 후 git switch main && git pull
  └─ 다음 작업으로 git switch -c …
```

### 작업 순서 (Phase B+ 표준)

1. `architect` (설계·ADR 변경 시)
2. `backend-core` (도메인·인프라 우선)
3. `market-data` (외부 어댑터)
4. `strategy-engine` (도메인 로직)
5. `ai-engineer` (LLM 통합)
6. `frontend` (UI — 항상 마지막. 위 모든 결과물의 시각화)
7. `qa` (on-demand, ADR 0016 참조)

> frontend가 마지막인 이유: 백엔드 API·도메인 스키마·AI 출력 포맷이 정해진 다음에 UI를 짜야 재작업 적음. 일찍 시작하려면 stub 데이터 단계로 분리 후 마지막에 실제 연결.

### Worktree 사용을 허용하는 예외 (좁게)

- 본 디렉토리가 dirty인데 다른 작업이 급할 때 **임시** (작업 후 `git worktree remove`)
- 진짜 독립적인 큰 작업(예: 별도 마이크로서비스 추가, 대규모 리팩터)에서 본 디렉토리와 동시 진행이 명백히 이득일 때
- 위 외에는 worktree 만들지 않는다.

### 폐기

- `.claude/worktrees/agent-*` 디렉토리 7개는 Phase B 종료 후 정리
- 향후 worktree 생성은 명시적 ADR 또는 사용자 승인이 있을 때만

## 결과

- 긍정: 토큰 비용 큰 폭 절감, 머지 충돌이 작업 중간에 노출되어 빨리 해결, 본 디렉토리 한 곳만 보면 됨
- 부정: 동시 병렬 작업의 가능성을 포기 (1인 개발자 + MVP 단계엔 손실 미미)
- 후속:
  - 오케스트레이터 스킬에 "기본 단일 worktree, 예외만 worktree 생성" 명시
  - 매 작업 시작 시 task 파일 생성 (ADR 0018) — 가시성 보강
