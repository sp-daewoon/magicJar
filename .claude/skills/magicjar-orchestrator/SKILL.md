---
name: magicjar-orchestrator
description: magicJar(마법의항아리) 한국 주식 자동매매 프로젝트의 모든 구현·설계·확장·유지보수 작업을 단일 메인 worktree에서 자율 조율하는 오케스트레이터. Spring Boot + Spring AI + KIS OpenAPI 기반 백테스트/모의/실전 시스템 구축, 모듈 추가, 전략 추가, AI 기능 추가, 화면 추가, 부분 재실행, 이전 결과 기반 개선 요청에 반드시 이 스킬을 사용. "magicJar 시작", "자동매매 기능 추가", "전체 빌드", "백테스트 돌려줘", "부분 재실행", "이전 결과 기반 개선"도 포함.
---

# magicjar-orchestrator — magicJar 자율 운영 v2

magicJar 프로젝트의 모든 비트리비얼 작업은 이 오케스트레이터를 통한다. **단일 메인 worktree + 순차 작업 + 모델 차등 + 머지 단순화 + QA on-demand + Task 파일 시스템** 기반. 에이전트 7명은 worktree 격리가 아닌 "역할 모자"로 사용 — 메인 세션이 그 모자만 바꿔 쓰며 작업한다.

## 에이전트 지도 (model 차등 — ADR 0014)

| 에이전트 | 모델 | 전용 스킬 | 주 담당 |
|---------|------|---------|--------|
| architect | **opus** | magicjar-adr | 시스템 설계·ADR·모듈 경계 |
| backend-core | sonnet | magicjar-spring-boot-scaffold, magicjar-harness-dashboard | Spring Boot 뼈대·도메인 모델·공용 인프라·하네스 대시보드 백엔드 |
| market-data | sonnet | magicjar-kis-openapi | KIS API·시세·뉴스·공시 수집 |
| strategy-engine | **opus** | magicjar-backtest-engine, magicjar-trading-journal | 지표·DSL·백테스트·주문 로직·매매일지 자동 기록 |
| ai-engineer | **opus** | magicjar-spring-ai | Spring AI·프롬프트·감성·조언·매매 AI 복기 |
| frontend | sonnet | magicjar-dashboard, magicjar-trading-journal, magicjar-harness-dashboard | UI·차트·매매일지 페이지·하네스 운영 페이지 |
| qa | sonnet | magicjar-boundary-qa | **On-Demand** 경계면 교차 검증 (ADR 0016 트리거 시에만) |

**에스컬레이션**: 기본 모델로 시도 후 결과 부족 시 1회 opus로 재시도. 더 부족하면 작업 분할 또는 사용자 의사결정 요청. (ADR 0014)

## 운영 모델 (v2 핵심)

### 1. 단일 메인 worktree (ADR 0013)
- `.claude/worktrees/agent-*` 디렉토리는 **만들지 않는다**.
- 모든 작업은 본 디렉토리(`/Users/ak-song/magic_ch1`)에서 브랜치 전환으로 순차.
- 예외: 본 디렉토리가 dirty인데 급한 경우 임시 worktree 1회 + 끝나면 즉시 `git worktree remove`.

### 2. 작업 시작 시 task 파일 (ADR 0018) — 반드시
```bash
git switch main && git pull --ff-only
git switch -c agent/<agent>/<task-slug>
node scripts/task.mjs start --agent <a> --goal "..." --deliverables "..., ..., ..."
```
task 파일이 없으면 시작 안 한 것으로 간주. 대시보드가 "고아 brach" 경고.

### 3. 작업 중 task 파일 갱신
- 결과물 한 개 완료 → `node scripts/task.mjs check "<항목>"`
- 의미 있는 진전 → `node scripts/task.mjs touch "<메모>"`
- 막힘 → `node scripts/task.mjs block --reason "..."`
- 사용자 개입이 필요한 BLOCKED는 즉시 사용자에게 보고

### 4. 커밋 시 author 분리 (ADR 0017)
```bash
git -c user.name="magicjar-<agent>" -c user.email="bot+<agent>@magicjar.local" \
    commit -m "feat(<scope>): ..."
```
또는 작업 시작 시 `git config user.name/email` 설정 (전환 시 갱신 잊지 말기).

### 5. PR 생성 (ADR 0015 — `agent/* → main` 직행)
```bash
gh pr create --base main --fill --body "$(...)"
node scripts/task.mjs done --pr <N>
```
- 기본 base는 **main** (qa 영속 브랜치 폐기)
- PR 본문에 `QA: not required (사유)` 또는 `QA: required (트리거 #N)` 명시

### 6. QA 호출 (ADR 0016 — On-Demand)
다음 7개 트리거 중 하나라도 해당 시 QA 에이전트 호출:
1. 돈 흐름 변경 (OrderSender·실계좌·안전 게이트)
2. 공용 도메인 모델 깨는 변경
3. 새 사용자 흐름 UI
4. 보안 경계 (인증·키·시크릿)
5. 이전 버그 회귀 영역
6. Phase 통합·실배포 직전
7. 사용자 또는 메인 세션 명시 판단

이 외에는 메인 세션이 셀프 리뷰 + pre-commit/pre-push 자동 검사로 충분.

### 7. 머지 (사용자 승인 1회)
`gh pr merge <N> --squash --delete-branch` — `.claude/settings.json`의 `ask` 대상.
머지 후:
```bash
git switch main && git pull --ff-only
node scripts/task.mjs archive T-...
```

## 표준 작업 순서 (Phase B+ 기본)

설계가 정해진 상태에서 새 기능 추가 시 권장 순서:

```
1. architect       (필요 시 — 새 ADR·모듈 경계 변경)
2. backend-core    (도메인 모델·공용 인프라 우선)
3. market-data     (외부 어댑터)
4. strategy-engine (도메인 로직·매매일지)
5. ai-engineer     (LLM 통합)
6. frontend        (UI — 항상 마지막)
7. qa              (트리거 시에만)
```

> frontend가 마지막인 이유: 백엔드·AI가 정해진 다음에 UI를 짜야 재작업 적음.

## Phase 0: 컨텍스트 확인 (항상 첫 단계)

오케스트레이터가 트리거되면 **가장 먼저** 다음 확인:

1. `_workspace/_harness-state.json` — 현재 Phase, 활성 작업
2. `_workspace/tasks/*.md` — 진행 중 task가 있는지 (있으면 그 작업 이어가기)
3. 사용자 요청 패턴 분석:
   - "처음부터" → **초기 실행**
   - "{모듈}만 다시", "{에이전트}에게 {작업}" → **부분 재실행**
   - "전체 업데이트", "이전 결과 개선" → **증분 실행**
4. `MEMORY.md`와 `memory/*.md` 확인하여 사용자 선호·정책 재확인 (특히 `feedback_autonomy.md`, `feedback_qa_on_demand.md`)

결정된 모드를 한 줄 요약으로 사용자에게 알린 뒤 작업 진입.

**Phase 전환 시 `_workspace/_harness-state.json` 갱신 의무.**

## 작업 호출 패턴 (sub-agent)

작은 작업은 메인 세션이 직접 처리. 큰 작업·도메인 분리가 필요한 작업만 sub-agent 호출:

```
Agent({
  subagent_type: "architect",      // 커스텀 에이전트 (새 세션에선 자동 등록)
  model: "opus",                    // 명시 — 또는 escalation 시 override
  prompt: ".claude/agents/architect.md를 역할로, magicjar-adr 스킬 적용. 작업 시작 시 task 파일 생성. 목표: <...>",
  run_in_background: false          // 보통 false — 단일 worktree라 동시 실행 회피
})
```

**중요**: `run_in_background: true`는 진짜 독립 작업일 때만. 기본은 직렬 처리.

## 데이터 전달

- **파일 기반**: 모든 산출물 `_workspace/<module>/`에. 다른 에이전트가 읽음.
- **task 파일**: 진행 상태·체크리스트·BLOCKED는 `_workspace/tasks/<id>.md`에.
- **메시지 기반**: 단일 메인 세션 모드에선 SendMessage 거의 불필요. 사용자 보고만.

## 에러 핸들링

| 유형 | 전략 |
|------|------|
| sub-agent 1회 실패 | 동일 입력으로 재시도 1회. 재실패 시 모델 escalate(opus)로 1회. 또 실패면 task 파일 BLOCKED + 사용자 보고. |
| 빌드 실패 | 즉시 중단, 원인 분석 후 자체 수정. 외부 의존(API 한도·네트워크) 시 BLOCKED + 30분 후 재시도. |
| 토큰 한도 도달 | task 파일 BLOCKED + 작업 분할 시도. 사용자 보고. |
| 다른 에이전트 결론 상충 | 사용자 의사결정 요청 (자동매매 도메인이라 자동 합의 위험) |
| pre-push 차단 | hook 메시지 따라 수정 (브랜치명·시크릿 등) |

## Phase 종료 시점

- Phase 내 모든 작업 머지 완료 → `_workspace/_harness-state.json` 갱신
- 회고 문서 `docs/phase-N-summary` 또는 `_workspace/arch/phase-N-retrospective.md` 작성 (선택)
- 사용자에게 다음 Phase 진입 여부 확인

## 사용자에게 보고할 것 (간결)

- **작업 시작**: "X 작업 시작 (task T-..., agent <a>, model <m>)"
- **진척**: 사용자가 묻기 전엔 침묵. 대시보드(`_workspace/dashboard.html`)가 진척 노출.
- **블로커**: 즉시 보고. "BLOCKED — 이유, 사용자 결정 필요."
- **PR 생성**: "PR #N 만들었음. 머지 승인 부탁."
- **머지 후**: "머지 완료. 다음 작업: ..."

## 테스트 시나리오

### 정상 흐름 (단일 작업)
1. 사용자: "frontend에 새 화면 추가해줘"
2. Phase 0: 활성 task 없음 → 새 작업 시작
3. `git switch -c agent/frontend/new-screen` + task 파일 생성
4. frontend 에이전트(sonnet) 호출 → 작업
5. 결과 검토 → 문제 없음
6. 커밋(magicjar-frontend author) + push + PR(base=main)
7. 사용자 머지 승인 → main 반영
8. task archive

### 에러 흐름 (escalation)
1. ai-engineer가 sonnet으로 프롬프트 작성 → 결과 품질 미달 (메인 세션 판단)
2. 같은 작업 opus로 1회 재시도
3. 통과 → 진행
4. (또 부족하면 작업 분할 또는 사용자 결정)
