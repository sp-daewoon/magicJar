# ADR 0012: Feature → QA → Main 2단 머지 흐름

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (사용자 지시 반영 · qa 협의)
- 관계: ADR 0009(브랜치 전략)를 **보완**(supersede 아님 — 네이밍·커밋·훅 규약은 그대로)

## 맥락

사용자 요청: "머지는 자유롭게 해도 되지만, **QA 진행 단계로 넘길 때**와 **QA 완료 후 main으로 올릴 때** 두 지점에서만 사용자 승인을 받고 싶다."

ADR 0009의 단일 경로(`agent/*` → `main`)는 QA 결과가 바로 프로덕션에 반영되어 편하지만, "최종 관문 두 개"의 구조적 분리가 약하다. 자동매매처럼 **사람이 눈 한 번 마주쳐야 하는 시점이 명확해야 하는** 도메인에선 2단 승인이 더 안전하다.

## 결정

**영속 브랜치 `qa`를 도입해 `agent/* → qa → main` 2단 머지로 전환.**

### 브랜치 역할

| 브랜치 | 정책 | 상태 의미 |
|--------|------|----------|
| `main` | 가장 엄격 — 진짜 "언제든 배포 가능" 상태 | QA 완료 + 사용자 최종 승인된 변경만 |
| `qa` | 영속 통합 브랜치. 신규 — Phase A 이후 main에서 분기 | QA 에이전트 검수 대상 누적 |
| `agent/<agent>/<task>` | 단기 작업 브랜치 | WIP |
| `chore/*`·`docs/*` | 단기 작업 브랜치 | WIP |
| `hotfix/*` | 단기, 긴급 | WIP |

### 머지 경로

**기본 (코드·UI 변경):**
```
agent/<agent>/<task>
        │
        ├── PR (base: qa)  ◀── QA 에이전트가 검수 (스크린샷·경계면·보고서)
        │   └── gh pr merge  → 사용자 승인 필요 (settings.json "ask")
        ▼
      qa   (누적)
        │
        ├── PR (base: main) ◀── 주기 통합: Phase 단위 또는 배포 시점
        │   └── gh pr merge → 사용자 승인 필요 (settings.json "ask")
        ▼
      main
```

**면제 경로 (qa 경유 없이 main 직접 가능):**
- `chore/*`: 하네스·설정·CI·의존성·문서 등 비기능적 변경
- `docs/*`: 순수 문서
- `hotfix/*`: 운영 긴급 수정 (사후 반드시 qa로 백포트)

이 경우에도 `gh pr merge`는 여전히 `ask` — 사용자 승인은 필수.

### 승인 지점이 2개인 이유

1. **agent → qa 머지 시점**: "이 작업을 QA 영역에 올리겠습니다"의 사용자 확인. 즉, 스크린샷·경계면 검수가 끝났고 QA APPROVED 보고서가 있는 상태를 사람이 눈으로 재확인.
2. **qa → main 머지 시점**: "지금까지 qa에 쌓인 것들을 실제 배포 가능 상태로 확정"의 확인. 누적된 변경을 하나로 묶어 Phase/릴리즈 단위로 main에 내보냄.

그 외의 모든 머지·커밋·push는 오케스트레이터(Claude)가 자율 실행. `.claude/settings.json`의 `ask` 목록에 `gh pr merge:*`를 박아서 이 두 지점을 자동 가드레일로 만든다.

### QA 에이전트 작업 반영

- QA는 **PR base가 `qa`인 것**을 주된 검수 대상으로 한다. base가 `main`인 `chore/docs/hotfix` PR은 경량 체크(빌드·린트) 수준.
- `qa` 브랜치에 여러 agent PR이 누적되면 QA는 **통합 스모크**를 한 번 더 실행해 PR 단위 QA에서 놓친 상호작용 버그를 잡는다.
- `qa → main` PR에는 **"그 동안의 스크린샷 요약 INDEX"**를 본문에 첨부 (지난 기간 머지된 각 PR의 대표 스크린샷 1장씩).

### `qa` 브랜치 관리 규칙

- 최초 생성: `git switch main && git pull && git branch qa && git push -u origin qa`
- main에 hotfix가 들어가면 **즉시 main → qa로 fast-forward 동기화** (`git switch qa && git merge --ff-only main && git push`)
- `qa`에서 직접 코드 편집·커밋 금지. 오로지 PR 머지로만 변경이 들어감.
- `qa` 리베이스·force-push 금지 (settings.json에서 금지).

### Conventional Commits 스코프 확장

브랜치 대상에 따라 스코프 힌트 추가 가능: `feat(strategy)!: ...` 같은 기존 규약 유지. 머지 커밋에 PR 번호(`(#N)`) 자동 포함 (squash 머지).

## 결과

- 긍정:
  - 사용자 부담은 정확히 "두 번의 승인"으로 집중 → 자율 실행 장점 유지 + 중요한 순간에만 눈 마주침
  - `main`이 항상 엄격히 "배포 가능 상태". `qa`에서 부가 통합 버그 조기 포착
  - 자동매매 도메인의 "이중 안전장치" 정신과 일치
- 부정:
  - 단계 하나 늘어 소소한 관리 비용 (특히 `qa` 브랜치 drift 주의)
  - `hotfix/*`는 순서 역행 위험 → "back-port to qa" 규칙 문서화로 방어
- 후속:
  - `branching-and-qa-gates.md` 전체 개편
  - `magicjar-orchestrator`·`magicjar-boundary-qa`·`agents/qa.md` 업데이트
  - `.husky/pre-push`에 qa 브랜치 push 허용 정책 반영
  - `.github/pull_request_template.md`에 base 안내 추가
  - 실제 `qa` 브랜치는 다음 세션에서 생성(현재 다른 세션이 실행 중이므로 HEAD 변경 지연)
