# 브랜치 운영 & QA 게이트 실전 가이드 (v2)

ADR 0009·0010·0011·**0013·0014·0015·0016·0017·0018**의 실무 요약. v1(2단 머지·worktree 7개)에서 **v2(단일 worktree·1단 머지·on-demand QA·model 차등·task 시스템)**로 전환됨.

## 한눈에 보는 흐름

```
[오케스트레이터(메인 세션) 작업 할당]
        │
        ▼
git switch main && git pull --ff-only
git switch -c agent/<agent>/<task-slug>          # 브랜치 생성
        │
        ▼
node scripts/task.mjs start --agent <a> --goal "..."  # task 파일 (ADR 0018)
        │
        ▼
[작업 — 메인 worktree에서 직접 또는 sub-agent 호출]
   ├─ task.mjs check "<항목>"   결과물 한 개 완료마다
   ├─ task.mjs touch "<메모>"   진척마다
   └─ git commit (author = magicjar-<agent>)    ADR 0017
        │
        ▼
git push -u origin agent/<a>/<task>     # pre-push 자동 검증
        │
        ▼
gh pr create --base main --fill         # base = main 직접 (ADR 0015)
node scripts/task.mjs done --pr N
        │
        ▼
[QA 트리거 7개 중 하나 해당? — ADR 0016]
   ├─ 해당 → qa 에이전트 호출 (별도 보고서 작성)
   └─ 미해당 → 메인 세션 셀프 리뷰 + pre-commit/pre-push 자동 검사로 충분
        │
        ▼
[사용자 승인] gh pr merge N --squash --delete-branch       (ask)
        │
        ▼
git switch main && git pull
node scripts/task.mjs archive T-...
```

**사용자 승인 지점은 정확히 1개**: `gh pr merge`. 그 외 모든 단계는 메인 세션 자율.

## 브랜치 네이밍

| 패턴 | 기본 base | 용도 |
|------|----------|------|
| `agent/architect/<task>` | `main` | 시스템 설계·ADR 변경 |
| `agent/backend-core/<task>` | `main` | Spring Boot 뼈대·도메인·인프라 |
| `agent/market-data/<task>` | `main` | KIS API·데이터 수집 |
| `agent/strategy-engine/<task>` | `main` | 지표·DSL·백테스트·매매일지 |
| `agent/ai-engineer/<task>` | `main` | Spring AI·프롬프트 |
| `agent/frontend/<task>` | `main` | UI·차트 |
| `agent/qa/<task>` | `main` | QA 도구·스모크 자동화 |
| `chore/<slug>` | `main` | 하네스·설정·CI·의존성 |
| `docs/<slug>` | `main` | 순수 문서 |
| `hotfix/<slug>` | `main` | 운영 긴급 수정 |

영속 브랜치 = `main` 단 하나. (qa 영속 브랜치 폐기 — ADR 0015)

## 모델 차등 (ADR 0014)

| 에이전트 | 기본 모델 |
|---------|----------|
| architect, ai-engineer, strategy-engine | **opus** |
| backend-core, market-data, frontend, qa | **sonnet** |

**에스컬레이션**: 결과 부족 시 1회 opus 재시도. 또 부족하면 작업 분할 또는 사용자 결정.

## 커밋 메시지 (Conventional Commits)

```
feat(strategy): add RSI oversold signal
fix(market-data): KIS token cache TTL off-by-one
chore(husky): tighten pre-push check
docs(adr): add 0018 task file system
refactor(core): split OrderResult into sealed class hierarchy
test(ai): add sentiment parsing edge cases
```

## 커밋 author 분리 (ADR 0017)

```bash
git -c user.name="magicjar-backend-core" \
    -c user.email="bot+backend-core@magicjar.local" \
    commit -m "feat(core): ..."
```

또는 작업 시작 시 `git config user.name/email`로 한 번 설정 후 작업.

`git log --pretty=format:"%h %an %s"` 출력에서 누가 했는지 즉시 식별.

## QA 트리거 7개 (ADR 0016)

이 중 하나라도 해당 시 qa 에이전트 호출:

1. **돈 흐름** — `OrderSender`, 실계좌, 안전 게이트
2. **공용 도메인 모델 깨는 변경** — `core/domain/*`
3. **새 사용자 흐름 UI**
4. **보안 경계** — 인증·키·시크릿
5. **이전 버그 회귀 영역**
6. **Phase 통합·실배포 직전**
7. **사용자 또는 메인 세션의 명시 판단**

이외엔 PR 본문에 `QA: not required (사유)` 한 줄 명시.

## "프로덕트한 코드" 기준 (ADR 0016)

게이트 대신 코드 자체 기준:
- 단위 테스트 커버리지 70%+ 지향
- 공개 API·도메인 모델 KDoc 1줄 이상
- 에러 처리 sealed class·Result 타입
- 외부 호출 Resilience4j Circuit Breaker
- 시크릿 환경변수만, 로그 노출 금지
- 로그 구조화(JSON) + 마스킹
- 각 모듈 짧은 `README.md`

## PR 템플릿 (요약)

```markdown
## 요약
<1~3줄>

## 담당
- Agent: <a>
- Model: <opus | sonnet>
- Task: T-YYYYMMDD-HHMM-...
- Skill(s): magicjar-...
- 관련 ADR: ...

## QA (ADR 0016)
QA: not required (사유) | required (#N)

## 변경 유형
- [x] feat / fix / refactor / chore / docs / test

## 테스트 방법
- ./gradlew :<module>:test
- 수동 시나리오: ...
```

## 머지 조건

- 빌드·테스트 통과 (pre-push가 자동)
- ktlint 통과 (pre-commit 자동)
- task 파일 status=DONE + PR 번호 연결
- QA required PR이라면 보고서 APPROVED
- 사용자 `gh pr merge` 승인

## 에이전트 치트시트

```bash
# 1. 새 작업 시작
git switch main && git pull --ff-only
git switch -c agent/<a>/<task>
node scripts/task.mjs start --agent <a> --branch agent/<a>/<task> \
   --goal "..." --deliverables "...; ...; ..."

# 2. 작업 진행
# ... 코드 작성 ...
node scripts/task.mjs check "<완료 항목>"
node scripts/task.mjs touch "<메모>"

# 3. 커밋 (author 분리)
git -c user.name="magicjar-<a>" -c user.email="bot+<a>@magicjar.local" \
    commit -m "feat(<scope>): ..."

# 4. push & PR
git push -u origin HEAD
gh pr create --base main --fill
node scripts/task.mjs done --pr N

# 5. 머지 후 정리
git switch main && git pull
node scripts/task.mjs archive T-...
```

## 자주 하는 실수

- **main 직접 push** — pre-push가 차단
- **task 파일 없이 작업 시작** — 대시보드에 "고아 브랜치" 경고, push 시 hook 권고
- **qa 트리거 무시** — 돈 흐름·도메인 모델 변경에 `QA: not required` 적으면 안 됨
- **commit author 통일된 채로** — `git -c user.name=...`로 작업 주체 분리
- **task 파일 archive 잊음** — 머지 후 즉시 archive (`done/` 디렉토리로)
- **worktree 만들기** — 단일 메인 worktree가 기본. 만들 때 명시 ADR 또는 사용자 승인 필요.
