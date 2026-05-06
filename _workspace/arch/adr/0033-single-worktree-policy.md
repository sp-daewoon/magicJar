# ADR 0033: Worktree 정책 — 병렬 디스패치 허용 + 머지 후 정리 의무

- 상태: Accepted
- 작성일: 2026-04-25 (초안: 단일 worktree)
- 갱신일: 2026-05-07 (병렬 허용 + 정리 의무)
- 결정자: architect (사용자 — 최고 관리자 직접 진단 반영)
- 관계: ADR 0009 보완, ADR 0036 3단 hierarchy 정합

## 맥락

### v1 (PoC) — 토큰 폭증

PoC v1 시점 7개 에이전트가 각자 `.claude/worktrees/agent-*` 별도 worktree에서 동시 작업하는 모델로 운영했다. 결과:

- **토큰 폭증**: 각 worktree마다 에이전트가 풀 컨텍스트(시스템 프롬프트·도구·기억) 적재 → 7배 비용
- **격리 가치 미실현**: 모듈이 모두 `core`에 의존하는 모놀리식이라 진짜 병렬은 환상. 머지 단계에서 같은 `build.gradle.kts`·`application.yml`을 수정해 결국 충돌
- **상태 동기화 어려움**: `_harness-state.json`이 여러 곳에서 동시 갱신
- **머지 시간 증가**: PR이 한꺼번에 몰려 사용자 승인 부담 누적

사용자 표현: "토큰의 막대한 소모만 생기고 있어. 자체적 에이전트가 자기 브랜치에서 처리하고 문제 있으면 너(최고책임자)가 분석·체크·돌려보내는 모델을 원했던 건데."

### v2 — 단일 worktree 강제

v1의 후과로 PoC v2 시점 단일 메인 worktree + 순차만 허용하는 강제 정책으로 전환. 토큰은 절감됐으나 **진짜 독립 작업까지 직렬화**되어 1인 개발자 + 7 에이전트 협업의 처리량 한계 노출.

### v3 — 오케스트레이터 판단 하의 병렬 허용

v3 베이스라인(2026-05-06) + ADR 0036 3단 hierarchy(최고 관리자 → 오케스트라 → architect → 실무 sub-agents) 도입과 함께 worktree 정책 재검토. 오케스트라가 디스패치 시점에 충돌 행렬을 점검하고 **독립 작업은 병렬, 충돌 작업은 직렬**로 분배하면 v1의 토큰 폭증과 v2의 처리량 한계를 동시에 회피할 수 있다.

## 결정

**기본은 메인 worktree + 브랜치 전환. 오케스트라 판단 하에 병렬 worktree 디스패치를 허용한다. 충돌 가능 작업은 명시적 직렬.**

```
[메인 worktree]
  ├─ 오케스트라가 작업 단위(task) 분해
  ├─ 충돌 행렬 점검 (같은 모듈/같은 파일/같은 ADR 주제 = 직렬)
  ├─ 독립 작업은 병렬 worktree 디스패치 가능
  └─ 머지 후 즉시 worktree 정리 (의무)

[병렬 worktree들 (옵션)]
  ├─ git worktree add ./.claude/worktrees/<agent>-<task> agent/<agent>/<task>
  ├─ sub-agent 작업·commit·push·PR
  └─ 머지 직후 정리 (의무 — 아래 §결과 참조)
```

### 작업 우선순위 (기본 순서, 병렬 가능 시 동시)

1. `architect` (설계·ADR 변경 시)
2. `backend-core` (도메인·인프라 우선)
3. `market-data` (외부 어댑터)
4. `strategy-engine` (도메인 로직)
5. `ai-engineer` (LLM 통합)
6. `frontend` (UI — 항상 마지막 권장)
7. `qa` (on-demand, ADR 0036 참조)

> frontend가 마지막인 이유: 백엔드 API·도메인 스키마·AI 출력 포맷이 정해진 다음에 UI를 짜야 재작업 적음. 일찍 시작하려면 stub 데이터 단계로 분리 후 마지막에 실제 연결.

### 병렬 디스패치 권장 케이스

- 서로 다른 모듈/도메인 영역 (예: `market-data` 어댑터 vs `frontend` UI 컴포넌트)
- 서로 다른 트랙 ID (예: T-IND vs T-LWC)
- 문서 영역 분리 (예: ADR 신규 작성 vs ADR 헤더 정합)
- 충돌 가능성 0인 명확한 독립 작업

### 직렬 권장 케이스 (병렬 금지)

- 같은 파일·디렉토리 동시 수정 (예: 동일 ADR 두 군데 수정, 동일 `application.yml` 변경)
- 같은 모듈 빌드 의존성 변경 (예: `build.gradle.kts` 동시 수정)
- ADR 간 강결합 변경 (예: 0033·0036 동시 갱신처럼 한 PR로 묶이는 것이 자연스러운 변경)
- SoT 문서(`master-architecture.md`·`tracks-roadmap.md`) 동시 수정
- 같은 `_harness-state.json` 갱신

### 폐기

- v1의 `.claude/worktrees/agent-*` 7개 영구 worktree → **v3 베이스라인 시점에 이미 정리 완료**
- 향후 worktree는 task 단위 일회성. 영구 worktree 금지.

## 결과

### 긍정

- 처리량 회복 — 독립 작업 병렬화로 1인 개발자+7 에이전트 협업의 hourly 처리량 증가
- 토큰 절감 유지 — 직렬화가 필요한 영역은 여전히 단일 worktree 강제
- 머지 충돌 조기 노출 — 오케스트라의 충돌 행렬 점검 단계가 1차 게이트

### 부정

- 오케스트라 부담 증가 — 매 디스패치마다 충돌 점검·worktree 라이프사이클 관리
- 디버깅 복잡도 — 동시 진행 PR 다수일 때 main pull 타이밍 주의

### 머지 후 정리 의무 (반드시 즉시 수행)

PR 머지 직후 다음 절차를 단일 commit-less 시퀀스로 실행:

```bash
# 1. worktree unlock + remove
git worktree unlock <worktree-path> 2>/dev/null || true
git worktree remove --force <worktree-path>

# 2. 로컬 브랜치 삭제
git branch -d agent/<agent>/<task>     # 머지된 경우 -d 안전
# 머지 detect 실패 시 -D 강제 (rebase merge로 git이 인지 못할 때)

# 3. orphan worktree 메타 prune
git worktree prune

# 4. 빈 디렉토리 청소
rmdir .claude/worktrees/<agent>-<task> 2>/dev/null || true
```

**locked 방치 금지** — 사용자 메모리 `feedback_worktree_cleanup.md`도 같은 의무 명시. 위반 시 다음 작업 시작 전 자동 점검·정리.

### 후속

- 오케스트레이터 스킬에 "디스패치 전 충돌 행렬 점검 + 머지 후 정리 의무" 명시
- 매 작업 시작 시 task 파일 생성 (ADR 0036) — 가시성 보강
- 병렬 worktree 사용 통계 수집 (선택) — 처리량 vs 토큰 비용 측정 후 비율 조정

## 참고

- ADR 0036 — Orchestrator Meta Rules (3단 hierarchy + author + task)
- 사용자 메모리 `feedback_worktree_cleanup.md` — 머지 직후 정리 의무
- 사용자 메모리 `feedback_autonomy.md` — git workflow 결정은 자율 영역
