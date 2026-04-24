# 브랜치 운영 & QA 게이트 실전 가이드

ADR 0009·0010·0011의 실무 요약. 에이전트·사용자 모두 이 문서를 참조해서 매 PR을 수행한다.

## 한눈에 보는 흐름

```
[오케스트레이터가 작업 할당]
        │
        ▼
[에이전트] git switch -c agent/{name}/{task}
        │   (작업·커밋 반복)
        ▼
[에이전트] git push -u origin ...    ──▶ Husky pre-push (로컬 1차 검증)
        │
        ▼
[에이전트] gh pr create   (PR 템플릿 채움)
        │
        ▼
[QA 에이전트] 체크아웃 → 실행 → 스크린샷 → 보고서
        │
        ▼
[QA]  _workspace/qa/report-{ts}.md  (status: APPROVED?)
        │
        ├── APPROVED         ──▶ squash merge → 브랜치 삭제
        └── CHANGES_REQUESTED ──▶ 원 에이전트에 SendMessage(수정 사항)
```

## 브랜치 네이밍 (강제)

| 패턴 | 예시 |
|------|------|
| `agent/backend-core/{task}` | `agent/backend-core/scaffold-multimodule` |
| `agent/market-data/{task}` | `agent/market-data/kis-token-refresh` |
| `agent/strategy-engine/{task}` | `agent/strategy-engine/rsi-backtest` |
| `agent/ai-engineer/{task}` | `agent/ai-engineer/sentiment-prompt-v1` |
| `agent/frontend/{task}` | `agent/frontend/journal-list-page` |
| `agent/qa/{task}` | `agent/qa/phase-b-smoke` |
| `chore/{slug}` | `chore/update-deps-2026-05` |
| `docs/{slug}` | `docs/readme-quickstart` |
| `hotfix/{slug}` | `hotfix/order-guard-bypass` |

슬러그는 kebab-case, 4~8단어 이내.

## 커밋 메시지 (Conventional Commits)

```
feat(strategy): add RSI oversold signal
fix(market-data): KIS token cache TTL off-by-one
chore(husky): tighten pre-push check
docs(adr): add 0011 qa screenshot gate
refactor(core): split OrderResult into sealed class hierarchy
test(ai): add sentiment parsing edge cases
```

스코프는 모듈 이름(`core`, `market-data`, `strategy`, `ai`, `order-exec`, `web`, `app`, `husky`, `adr`, `qa`, `deps` 등).

## PR 템플릿 (PR Description에 필수)

```markdown
## 요약
<1~3줄 — 무엇을 바꿨고 왜>

## 담당
- Agent: strategy-engine
- Skill(s): magicjar-backtest-engine, magicjar-trading-journal
- 관련 ADR: 0006, 0007

## 테스트 방법
- `./gradlew :strategy:test`
- 수동: `./gradlew :app:bootRun --args='--spring.profiles.active=mock'` → /journal

## QA 체크리스트 (QA 에이전트가 채움)
- [ ] 경계면 매트릭스 재검증
- [ ] 스모크 시나리오 통과
- [ ] 스크린샷 저장 완료 (`_workspace/qa/screenshots/<slug>/`)
- [ ] 보고서 생성 (`_workspace/qa/report-<ts>.md`, status: APPROVED)

## Screenshot Summary
(UI 변경 있을 때만. 주요 화면 1장 임베드 + 나머지 경로 링크)
```

GitHub에 `.github/pull_request_template.md` 를 만들어 이 양식을 자동 주입 (Phase B 초기 backend-core가 생성).

## QA 스크린샷 규칙 (ADR 0011 요약)

- 경로: `_workspace/qa/screenshots/{pr-id or branch-slug}/`
- 파일명: `NN-{screen}-{state}.png` (`01-home-happy.png`, `02-journal-list-empty.png`)
- 상태 커버리지(최소): happy·empty, 선택: error·loading
- 해상도 기본 1440×900, 모바일 필요 시 `-mobile` 접미어
- 각 스크린샷 디렉토리에 `INDEX.md` — 표 형식(파일명, 화면, 상태, 캡처 시각, 메모)

## 머지 조건 체크 (병합 차단 기준)

PR을 main으로 머지하기 전 모두 충족:

- [ ] 빌드·테스트 통과 (CI 도입 전에는 로컬 `./gradlew check`)
- [ ] ktlint 통과
- [ ] QA 에이전트 APPROVED 보고서 존재
- [ ] UI/사용자 흐름 변경 시 스크린샷 아티팩트 존재
- [ ] PR Description이 템플릿 완전히 채워짐
- [ ] main과 최신 상태 (rebase 또는 merge)

## 에이전트 전용 치트시트

**작업 시작:**
```bash
git switch main && git pull --ff-only
git switch -c agent/{name}/{task}
```

**작업 완료:**
```bash
git add -A
git commit -m "feat({scope}): ..."
git push -u origin HEAD
gh pr create --fill
```

**QA 요청:**
```
SendMessage(to: qa, "PR #NN 브랜치 agent/.../...에 대해 QA 바랍니다. 화면 변경: 매매일지 목록")
```

## QA 에이전트 전용 치트시트

```bash
gh pr checkout {pr-id}
./gradlew :app:bootRun --args='--spring.profiles.active=mock' &   # 또는 수동
# 스크린샷 캡처 → _workspace/qa/screenshots/{slug}/
# 경계면 점검 → _workspace/qa/boundary-matrix.md 갱신
# 보고서 → _workspace/qa/report-{ts}.md (status: APPROVED)
gh pr review {pr-id} --approve --body "QA 통과: _workspace/qa/report-{ts}.md"
```

## 자주 하는 실수

- **main 직접 push** — Husky pre-push가 1차 차단, 원격 branch protection이 2차 차단.
- **커밋 후 스크린샷 깜빡** — PR에 스크린샷 경로 누락하면 QA가 CHANGES_REQUESTED.
- **QA Report status 누락** — 보고서 있어도 `status: APPROVED` 키가 없으면 자동화 체크 실패.
- **브랜치 네이밍 오류** — `agent/frontend/foo`가 아니라 `feature/foo`로 만들면 오케스트레이터 추적 실패.
