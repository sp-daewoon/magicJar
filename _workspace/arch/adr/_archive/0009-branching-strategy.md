# ADR 0009: 브랜치 전략과 머지 규칙

> **STATUS: SUPERSEDED by ADR 0015 + 0016 (2026-05-03 v2 베이스라인)** — Feature → QA → Main 2단 머지 + QA 게이트 강제 → 0015 단일 머지 흐름(`agent/* → main`) + 0016 QA on-demand로 단순화. 본 ADR은 참조용 보존.

- 상태: Superseded by 0015 · 0016
- 작성일: 2026-04-25
- 결정자: architect (협의: backend-core, qa, 사용자 지시)

## 맥락

magicJar는 단일 개발자 + **복수 에이전트가 병렬 작업**하는 형태다. 에이전트가 만드는 변경은 반드시 **QA 통과(+ 스크린샷 게이트)** 이후에만 통합되어야 한다. 너무 무거운 Git Flow는 오버헤드, 너무 단순한 trunk-based는 품질 게이트 공간이 부족.

## 결정

**단순 Feature-branch 모델 + 보호된 `main` + 에이전트 단위 네이밍.**

### 브랜치 종류

| 종류 | 네이밍 | 용도 | 수명 |
|------|-------|------|------|
| **main** | `main` | 항상 "QA 통과 + 스크린샷 인증" 상태 | 영구 |
| 에이전트 작업 | `agent/{agent-name}/{task-slug}` | 에이전트가 실제 코드·문서 작업 | 단기 (PR 머지 후 삭제) |
| 수동/임시 | `chore/{slug}`, `docs/{slug}` | 사람이 직접 하는 소소한 수정 | 단기 |
| 핫픽스 | `hotfix/{slug}` | 운영 긴급 수정 (MVP 단계엔 거의 없음) | 단기 |

예:
- `agent/backend-core/scaffold-multimodule`
- `agent/market-data/kis-client-token`
- `agent/strategy-engine/indicator-library`
- `agent/ai-engineer/sentiment-prompt-v1`
- `agent/frontend/journal-page`
- `agent/qa/phase-b-smoke`

### 머지 흐름 (단일 경로)

```
agent/* ─(PR)─▶ main
                ▲
            QA 게이트 통과 + 스크린샷 아티팩트
```

- 모든 PR의 목적지는 **`main`** (develop 브랜치 도입하지 않음 — MVP 단계 오버엔지니어링)
- PR은 squash merge를 기본으로 하여 `main` 히스토리를 선형 유지
- 커밋 메시지 컨벤션: Conventional Commits(`feat|fix|chore|docs|refactor|test: ...`). commitlint 도입은 후속 ADR.

### 머지 조건 (모두 충족)

1. CI(향후 GitHub Actions): `./gradlew check` 성공
2. `ktlint` 통과
3. **QA 에이전트 승인** — QA 보고서 파일(`_workspace/qa/report-{timestamp}.md`) 존재 + `status: APPROVED`
4. **스크린샷 아티팩트** — 해당 PR이 UI 변경 포함 시 `_workspace/qa/screenshots/{pr-id}/` 아래 관련 스크린샷 필수 (상세는 ADR 0011)
5. PR Description에 다음 섹션 필수:
   - 변경 요약 (무엇을·왜)
   - 담당 에이전트 + 사용 스킬
   - 테스트 방법
   - QA 체크리스트(해당 경계면 + 스크린샷)

### main 보호 규칙 (원격)

- 직접 푸시 금지, PR 경유만
- 머지 전 최소 1 review (자기 자신 승인 허용 — 단독 개발자)
- force-push 금지
- `CLAUDE.md` 또는 `.claude/**` 변경은 "harness" 레이블 의무

### 에이전트 작업 순서 (표준 플로우)

1. 오케스트레이터가 작업 할당 → 에이전트는 `git checkout main && git pull && git checkout -b agent/{name}/{task}`
2. 에이전트 작업 → 주기적 커밋 (conventional commit 메시지)
3. 작업 완료 → `git push -u origin agent/{name}/{task}` → `gh pr create`
4. QA 에이전트가 해당 PR을 대상으로 점검 → 스크린샷 저장 → 보고서 기록
5. QA APPROVED → 머지 → 원격 브랜치 삭제

## 결과

- 긍정: 단순·명료, PR이 "QA 통과의 증거"를 항상 동반, 에이전트 작업이 브랜치명으로 식별 가능
- 부정: 단일 개발자가 자기 자신의 PR을 리뷰·머지하므로 "4 eyes" 원칙이 형식적 — 대신 QA 에이전트가 객관성 유지
- 후속:
  - GitHub Actions CI 파이프라인 (ADR 0012 예정)
  - commitlint / Release-Please 도입 여부 (후속)
  - ADR 0010(husky), ADR 0011(스크린샷 게이트) 참조
