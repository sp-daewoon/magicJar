# ADR 0034: 머지 흐름 단순화 (`agent/* → main` 직행)

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (사용자 직접 진단 반영)
- 관계: **ADR 0012 부분 철회** (영속 `qa` 브랜치 폐기) · ADR 0009 보완

## 맥락

ADR 0012에서 `agent/* → qa → main` 2단 머지를 도입했다. 의도는 자동매매 도메인의 이중 안전장치였으나, 1인 개발자 + MVP 단계에서 다음 비용이 효익을 압도했다:

- **사용자 승인 2배**: 같은 변경에 머지 승인을 두 번 받음
- **머지 시간 증가**: main까지 도달하기까지 며칠
- **`qa` 브랜치 drift 관리 부담**: hotfix 백포트·동기화 등
- **사이클 단절**: 사용자가 결과를 바로 main에서 보지 못함

사용자 표현: "main까지 머지의 시간이 매우 길어진 부분에서 해소해야 할 것 같아."

## 결정

**기본은 `agent/* → main` 직행 1단 머지.** 영속 `qa` 브랜치 폐기.

### 새 흐름

```
agent/<agent>/<task>
   │
   └─ PR(base=main)
        │
        └─ 사용자 승인 1회 → rebase merge → main
```

**머지 방식**: `--rebase` 사용 (squash 금지). 사유: squash는 PR 작성 author를 web user(사용자)로 덮어써서 ADR 0036 author 화이트리스트(`bot+<agent>@magicjar.local`) 정합이 깨짐 — 에이전트의 실제 작업 history를 보존하려면 rebase가 정답.

`gh pr merge:*` 는 여전히 `.claude/settings.json` 의 `ask` (사용자 승인 1회만 남음).

### Wave 통합 PR (선택적, 큰 변경 묶음)

Wave(W1·W2·W3·W4·W5) 단위 통합이 필요한 경우(예: W2 종료 시점), 임시 통합 브랜치를 그때만 만들어 단일 큰 PR로 묶을 수 있음. 영속 브랜치 아님 — **임시·1회용**.

```
[Wave 종료 시점, 선택]
   여러 agent/* PR이 이미 main에 들어와 있음
   → 별도 회고/요약 PR(`docs/wave-N-summary`)만 main에 직접
```

### `qa` 브랜치 처리

- 원격 `qa` 브랜치가 있으면 ADR 적용 시점에 main과 동기화 후 **삭제**
- `.husky/pre-push`의 qa 차단 로직 제거
- `.claude/settings.json`의 qa 관련 deny/ask 정리

### 예외 경로 유지

- `chore/*` · `docs/*` · `hotfix/*` → base=main 직접 (이전과 동일)
- `hotfix/*`는 추가로 task 파일에 "hotfix-*" 태그 (ADR 0018)

## 결과

- 긍정: 사용자 승인 1회로 단축, main까지 도달 시간 큰 폭 단축, qa 브랜치 drift 관리 부담 제거
- 부정: 자동매매 안전장치가 1단계로 줄어듦 — **MVP 단계에 한해** 수용. 실서비스 진입 시 ADR로 다시 강화 게이트 도입 검토 (ADR 0016 sunset 참조)
- 후속:
  - ADR 0012의 "영속 qa 브랜치" 부분만 철회. 나머지(QA 보고서 양식 등)는 ADR 0011·0016에 흡수
  - `_workspace/arch/branching-and-qa-gates.md` 전면 갱신
  - 원격 `qa` 브랜치 삭제 (메인 머지 후)
