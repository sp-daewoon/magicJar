# ADR 0050 — CHANGELOG 슬롯 채번룰 (architect 사전 발행 + sub-agent in-place edit)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: 사용자(최고 관리자) · architect 정합 발행
- 관계: ADR 0034(머지 흐름 단순화 — rebase) · ADR 0036(Orchestrator Meta Rules — task 파일 + 3단 hierarchy) · ADR 0033(병렬 worktree 허용)

## 1. 컨텍스트

병렬 sub-agent 디스패치(ADR 0033·0036) 시 다수 PR이 동시에 `CHANGELOG.md` 끝부분에 add-only insert를 시도 → **두 번째 머지자가 git rebase 충돌 해결**. 사용자(최고 관리자) 정정:

> "sub-agent CHANGELOG 안 건드림이 아니라 서브에이전트가 작성할 공간을 아키텍처가 미리 작성해놓는거지. 채번룰처럼. 그러면 별도 에이전트끼리 컨플릭은 안나잖아."
>
> "물론 아키텍처가 무조건 개입하는게 아니기때문에 공간이 없더라도 에이전트가 기입하고 컨플릭나면 나는대로 처리하긴해야해."
>
> "아키랑 맺은 규약 공간에 넣고 없다면 지금하던대로 하는거지."

핵심 — **architect가 spec/plan PR을 발행하는 사이클**은 그 spec PR의 CHANGELOG에 sub-agent별 슬롯 row를 미리 점유해 둔다(채번 비유). sub-agent는 자기 row만 in-place edit → git이 다른 line 변경으로 인식 → 충돌 0. spec/plan PR 부재 사이클은 평소대로 add-only + 충돌 자체 해결 fallback.

## 2. 결정

### 2-1. 슬롯 발행 트리거 — architect가 spec/plan PR을 발행하는 경우만

| 사이클 유형 | 슬롯 발행 | 정합 SoT |
|---|---|---|
| **architect spec/plan PR** (신규 트랙·새 모듈·다중 ADR — 사용자 메모리 `feedback_spec_plan_scope.md` 정합) | **의무** — sub-agent별 슬롯 row 사전 발행 | 본 ADR §2-2 |
| hotfix·결함 정정·정책 정합·작은 묶음 변경 | spec/plan 자체 발행 0 → 슬롯 0 → 평소대로 | sub-agent add-only + rebase fallback (§2-4) |
| sub-agent 단독 PR (architect 미개입) | 슬롯 부재 | 평소대로 add-only + rebase fallback (§2-4) |

### 2-2. 슬롯 row 형식 (architect spec/plan PR이 발행)

architect가 spec/plan PR 본문 작성 시점에 `CHANGELOG.md` 표 끝에 다음 row를 sub-agent 수만큼 prepend:

```markdown
| <YYYY-MM-DD> | **<PREFIX>: PR-Na <track-id> <영역> backend** — TBD (작성: backend-core) | <영역> | <사유 자리표시자> |
| <YYYY-MM-DD> | **<PREFIX>: PR-Nb <track-id> <영역> frontend** — TBD (작성: frontend) | frontend | <사유 자리표시자> |
```

- **TBD** 토큰 — sub-agent가 자기 row를 식별하는 마커. 산출 끝나면 in-place edit으로 실제 내용 채움.
- **작성: <agent>** — row 소유자. 다른 agent가 이 row를 편집하면 안 됨(영역 침범).
- **prefix·track-id·영역** — architect가 spec PR 작성 시점에 결정 (PR-Na·PR-Nb 등 세부 PR 번호 포함).
- 슬롯 row는 **table row 단위 1줄씩 점유** — git diff가 다른 row 변경으로 인식 → 병렬 sub-agent PR 머지 시 충돌 0.

### 2-3. sub-agent 행동 (슬롯 row 존재 시)

1. agent/<agent>/<task> 브랜치 생성 → main rebase (architect spec PR 머지 후 main 위에서 작업)
2. `CHANGELOG.md` 열어 `(작성: <본인>)` 마커 row 검색
3. **그 row 1줄만 in-place edit** — `TBD` 자리표시자를 실제 한 줄 사유로 교체
4. 다른 row 절대 미수정 (영역 침범 금지)
5. commit + push + PR + merge → row 자기 자리에 갱신된 채 main에 반영

**slot 일탈 시**: sub-agent가 architect 예측과 다른 산출물(예: 추가 변경 1건 더)을 만들면 별도 정정 commit으로 row 추가 또는 갱신 — sub-agent 본인 책임. architect는 사후 `gh pr review`로 일탈 확인.

### 2-4. fallback — 슬롯 부재 시 (사용자 정정 핵심)

> "아키랑 맺은 규약 공간에 넣고 없다면 지금하던대로 하는거지."

architect가 spec/plan을 발행하지 않은 사이클(hotfix·결함 정정·작은 묶음 등):

1. sub-agent는 `CHANGELOG.md` 표 끝에 add-only insert (row 추가)
2. 두 번째 머지자가 push 시 git rebase 충돌 발생 → 본인이 자체 해결
3. 충돌 해결 = 상대 row + 본인 row 모두 보존 (둘 다 의미 있는 변경)

이는 **현재 PR-6/PR-7 사이클에서 사용된 패턴**과 동일. architect 슬롯 발행은 **add-on 안전망**이지 강제 의존이 아니다.

### 2-5. architect 책임 (slot 발행 의무)

architect가 spec/plan PR을 발행하는 경우:

- 그 spec PR 본문에 sub-agent 디스패치 매트릭스 명시 (어느 sub-agent가 어느 PR 번호로 어떤 영역 작업)
- 그 매트릭스를 그대로 CHANGELOG 슬롯 row로 옮겨 spec PR diff에 포함
- spec PR 머지 후 sub-agent들이 main에서 자기 row를 채움
- spec/plan에 누락된 sub-agent가 있으면 후속 정정 commit 또는 sub-agent가 fallback 패턴(§2-4)으로 자체 추가

### 2-6. 채번룰 비유

전통 채번룰 = "다음에 발급할 ID는 N+1" 사전 예약. 본 ADR도 동일 — architect가 "다음 사이클 sub-agent별 CHANGELOG 자리는 row Na·Nb·Nc"로 사전 발급. sub-agent는 그 자리만 채워 넣음 → 충돌 0.

## 3. 결과

### 3-1. 즉시

- 본 ADR 0050 발행 (`_workspace/arch/adr/0050-changelog-slot-numbering.md`)
- agent SKILL 보강 — `architect.md`에 "spec/plan PR 발행 시 슬롯 row 의무" 1절 추가
- `backend-core.md`·`frontend.md`·`market-data.md`·`strategy-engine.md`·`ai-engineer.md`에 "슬롯 사용 + fallback 룰" 1절 추가
- `magicjar-orchestrator/SKILL.md`에 운영 절차 1절 추가 (architect 디스패치 시 슬롯 발행 의무 명시 + sub-agent 디스패치 프롬프트에 슬롯 위치 알림)

### 3-2. 운영 효과

| 측면 | 이전 | 이후 (architect spec/plan 사이클) | 이후 (fallback 사이클) |
|---|---|---|---|
| 병렬 sub-agent CHANGELOG 충돌 | 매번 발생 → 자체 해결 | 0건 (in-place edit) | 매번 발생 → 자체 해결 (현재 패턴 유지) |
| sub-agent 부담 | rebase 해결 | row 1줄만 채움 | rebase 해결 |
| architect 부담 | 0 | spec PR에 슬롯 매트릭스 prepend | 0 |
| git history 깔끔함 | rebase 흔적 | 자연스러운 row update | rebase 흔적 |

### 3-3. trade-off

- architect가 sub-agent 디스패치 매트릭스를 사전에 정확히 알아야 함 — spec/plan PR 작성 시 약간의 추가 명시 비용.
- 일탈 케이스(sub-agent가 예상외 추가 작업) 발생 시 row 보강 필요 — 책임은 sub-agent에 있고 fallback 패턴 적용.
- spec/plan PR이 머지된 후에야 sub-agent가 작업 시작 가능 — 이미 ADR 0033·0036 정합 (architect → sub-agent 순서).

## 4. 대안 (rejected)

| 대안 | rejected 사유 |
|---|---|
| **CHANGELOG 추가를 sub-agent 0건 강제 (architect만 작성)** | sub-agent 자율성 침해. 사용자 명시 거부 — "규약 공간에 넣고 없다면 지금하던대로". |
| **CHANGELOG 자체 폐기** | 변경 이력 추적이 가치 큼. CLAUDE.md SoT 표 정합. |
| **머지마다 GitHub Actions로 자동 생성** | ADR 0049 정합 — GitHub Actions release 트리거만. |
| **별도 row file 분리 (.changelog.d/N.md)** | 표준화·검색성 저하. 단일 CHANGELOG.md SoT 유지가 운영자 친화적. |

## 5. 검증

- architect spec/plan PR diff에 CHANGELOG 슬롯 row N개 (sub-agent 수만큼) 포함 확인
- sub-agent PR diff에 CHANGELOG 1 row만 변경(in-place edit) — 다른 row 미변경 확인
- 병렬 sub-agent 머지 시 git rebase 충돌 0건 (또는 fallback 사이클일 때만 발생)
- sub-agent 일탈 row 보강 commit 기록 추적 가능

## 6. 참고

- ADR 0033 — 단일/병렬 worktree 정책 (병렬 디스패치 정합)
- ADR 0034 — 머지 흐름 단순화 (`agent/* → main` rebase — 본 ADR 슬롯 패턴은 rebase 정합)
- ADR 0036 — Orchestrator Meta Rules (3단 hierarchy + task 파일 시스템)
- 사용자 메모리 `feedback_spec_plan_scope.md` — spec/plan 발행 범위(신규 트랙·새 모듈·다중 ADR일 때만)
- 사용자 메모리 `feedback_orchestrator_active_parallel.md` — 적극 병렬 디스패치 룰
- `CHANGELOG.md` — 본 ADR 적용 대상 SoT
