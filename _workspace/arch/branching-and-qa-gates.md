# 브랜치 운영 & QA 게이트 실전 가이드

ADR 0009·0010·0011·**0012**의 실무 요약. 에이전트·사용자 모두 이 문서를 참조해서 매 PR을 수행한다.

## 한눈에 보는 흐름 (2단 머지, ADR 0012)

```
[오케스트레이터가 작업 할당]
        │
        ▼
[에이전트] git switch -c agent/{name}/{task}     # main에서 분기
        │   (작업·커밋 반복)
        ▼
[에이전트] git push -u origin ...               # Husky pre-push 로컬 1차 검증
        │
        ▼
[에이전트] gh pr create --base qa               # 1단 PR — base는 qa
        │
        ▼
[QA 에이전트] 체크아웃 → 실행 → 스크린샷 → 보고서
        │
        ├── APPROVED
        │       │
        │       ▼
        │   (사용자 승인) gh pr merge --squash    # ★ 승인 지점 1: qa로 진입
        │       │
        │       ▼
        │   브랜치 qa (누적)
        │
        └── CHANGES_REQUESTED ──▶ 원 에이전트에 SendMessage(수정 사항)


...일정 주기 or Phase 완료 시점...

[오케스트레이터] gh pr create --base main --head qa   # 2단 PR — qa → main
        │     PR 본문에 지난 기간 머지된 변경 목록 + 대표 스크린샷 INDEX
        ▼
[QA 에이전트] 통합 스모크 1회 실행 → 추가 보고서
        │
        ▼
(사용자 승인) gh pr merge --squash                   # ★ 승인 지점 2: main 진입
        │
        ▼
main (배포 가능 상태)
```

**승인 포인트는 정확히 두 개:**
1. `agent/* → qa` 머지 (이 변경을 QA 영역에 넣음)
2. `qa → main` 머지 (쌓인 변경을 배포 상태로 확정)

그 외의 커밋·push·PR 생성·PR 리뷰·QA 작업은 전부 자율 실행 (`.claude/settings.json`의 `allow`).

## 브랜치 네이밍 (강제)

| 패턴 | 기본 base | 용도 |
|------|----------|------|
| `agent/backend-core/{task}` | `qa` | 기능·UI·도메인 변경 |
| `agent/market-data/{task}` | `qa` | 외부 데이터 연동 |
| `agent/strategy-engine/{task}` | `qa` | 전략·백테스트·매매일지 |
| `agent/ai-engineer/{task}` | `qa` | LLM·프롬프트 |
| `agent/frontend/{task}` | `qa` | UI·차트·매매일지 페이지 |
| `agent/qa/{task}` | `qa` | QA 도구·스모크 자동화 개선 |
| `chore/{slug}` | `main` | 하네스·설정·CI·의존성 |
| `docs/{slug}` | `main` | 순수 문서 |
| `hotfix/{slug}` | `main` (+ 사후 qa 백포트) | 운영 긴급 수정 |

**영속 브랜치**
- `main` — 배포 가능 상태 (엄격)
- `qa` — 통합 검수 대상 누적 (영속, main에서 분기)

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
git switch main && git pull --ff-only        # main 최신화
git switch -c agent/{name}/{task}            # main에서 분기 (qa가 아님)
```

> `qa`에 직접 커밋 금지. 모든 작업은 main에서 분기한 `agent/*`에서 수행하고, PR로만 qa에 들어간다.

**작업 완료 (1단 PR, base=qa):**
```bash
git add -A
git commit -m "feat({scope}): ..."
git push -u origin HEAD
gh pr create --base qa --fill                # ★ base를 qa로 명시
```

**chore/docs/hotfix (base=main 예외):**
```bash
gh pr create --base main --fill              # qa 경유 없이 바로 main
```

**qa → main 주기 PR (오케스트레이터 책임):**
```bash
git switch main && git pull
gh pr create --base main --head qa \
  --title "chore(release): qa → main (Phase B batch)" \
  --body "$(cat _workspace/qa/qa-to-main-summary-{date}.md)"
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
