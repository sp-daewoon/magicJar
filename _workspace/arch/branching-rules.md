# 브랜치 운영 실전 가이드 (v3 · 3단 hierarchy + 병렬 worktree 허용)

ADR 0033·0034·0036·0080·0094 정합. 오케스트라 판단 하 병렬 worktree·1단 머지·3단 hierarchy(최고 관리자 → 오케스트라 → architect → 실무 sub-agents). 운영 단계의 qa는 Visual·E2E·DR drill 품질 헌터 역할을 담당한다.

## 베이스라인 태그·release 브랜치 (ADR 0031)

| 베이스라인 | 태그 | 보존 브랜치 | 시점 |
|-----------|------|------------|------|
| 마이그레이션 | TBD | TBD | 새 repo 진입 직후 |

- `release/v*` 브랜치는 **immutable 베이스라인**. 신규 ref 생성만 허용하고 기존 ref update/delete는 금지한다(pre-push hook 정합).
- hotfix는 받지 않는다. 살 붙이기·버그 수정은 main 트렁크에서, 다음 베이스라인 컷 시 `vX.Y.0` 새 태그 + `release/vX.Y` 신설
- GitHub Actions workflow는 사용하지 않는다. release는 `scripts/release.sh`와 로컬 검증 절차가 권위다.

## 한눈에 보는 흐름

```
[최고 관리자 — 비전·우선순위 결정]
        │
        ▼
[오케스트라 — 작업 분해 + 충돌 행렬 점검 (ADR 0036 §2-3)]
        │
        ▼
[architect — 설계·ADR 발행 (필요 시)] / [sub-agent 직접 dispatch (병렬 가능)]
        │
        ▼
git switch main && git pull --ff-only
git switch -c agent/<agent>/<task-slug>          # 브랜치 생성 (메인 worktree 또는 병렬 worktree)
        │
        ▼
node scripts/task.mjs start --agent <a> --goal "..."  # task 파일 (ADR 0036)
        │
        ▼
[작업 — sub-agent 자기 worktree·자기 author]
   ├─ task.mjs check "<항목>"     결과물 한 개 완료마다
   ├─ task.mjs touch "<메모>"     진척마다
   └─ git commit (author = bot+<agent>@magicjar.local)    ADR 0036
        │
        ▼
git push -u origin agent/<a>/<task>     # pre-push 자동 검증
        │
        ▼
gh pr create --base main --fill         # base = main 직접 (ADR 0034)
node scripts/task.mjs done --pr N
        │
        ▼
[구현자 셀프 리뷰 + 독립 qa/reviewer + pre-commit/pre-push 검사]
        │
        ▼
[사용자 승인] gh pr merge N --rebase --delete-branch
        │
        ▼
[오케스트라 — 머지 후 정리 의무 (ADR 0033 §결과)]
   ├─ git worktree unlock + remove (dirty면 중단·보고, 기본 force 금지)
   ├─ git branch -d agent/<a>/<task>
   ├─ git worktree prune + 빈 디렉토리 청소
   └─ node scripts/task.mjs archive T-...
        │
        ▼
git switch main && git pull
```

**사용자 승인 지점은 정확히 1개**: `gh pr merge`.

오케스트라가 충돌 행렬에서 독립 작업으로 판단한 경우 다수 sub-agent를 동시 dispatch 가능 (ADR 0033 §결정 — 병렬 디스패치 권장 케이스).

## 브랜치 네이밍

| 패턴 | 기본 base | 용도 |
|------|----------|------|
| `agent/architect/<task>` | `main` | 시스템 설계·ADR 변경 |
| `agent/backend-core/<task>` | `main` | Spring Boot 뼈대·도메인·인프라 |
| `agent/market-data/<task>` | `main` | KIS API·데이터 수집 |
| `agent/strategy-engine/<task>` | `main` | 지표·DSL·백테스트·매매일지 |
| `agent/ai-engineer/<task>` | `main` | Spring AI·프롬프트 |
| `agent/frontend/<task>` | `main` | UI·차트 |
| `chore/<slug>` | `main` | 하네스·설정·CI·의존성 |
| `docs/<slug>` | `main` | 순수 문서 |
| `hotfix/<slug>` | `main` | 운영 긴급 수정 |

영속 브랜치 = `main` 단 하나.

## 모델 정책 (ADR 0080 · ADR 0094 amend)

| 에이전트 | 기본 모델 |
|---------|----------|
| architect, backend-core, market-data, strategy-engine, ai-engineer, frontend, qa | **opus** |

`model: opus` 별칭은 최신 opus를 추종한다. 사용자가 모델을 내리기 전까지 전 역할 동일 정책을 유지한다.

## Git author per agent (ADR 0036)

`.husky/pre-commit`이 화이트리스트 강제. 화이트리스트:

- `sp-daewoon@gmail.com` (사용자 직접)
- `seonjupark94@gmail.com` (사용자 보조)
- `bot+architect@magicjar.local`
- `bot+backend-core@magicjar.local`
- `bot+market-data@magicjar.local`
- `bot+strategy@magicjar.local`
- `bot+ai@magicjar.local`
- `bot+frontend@magicjar.local`
- `bot+qa@magicjar.local`
- `bot+orchestrator@magicjar.local`

위반 시 commit reject. 머지 정책은 `--rebase` (squash가 author를 web user로 덮어쓰는 문제 회피).

