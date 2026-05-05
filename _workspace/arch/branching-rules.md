# 브랜치 운영 실전 가이드 (v2 · QA 게이트 배제 후속)

ADR 0033·0034·0035·0036 정합. 단일 worktree·1단 머지·model 차등·orchestrator-meta(git author + task 시스템). **QA 게이트는 본 새 repo에서 배제** (사용자 명시 — 실서비스 진입 시 별도 ADR로 회귀 검토).

## 베이스라인 태그·release 브랜치 (ADR 0031)

| 베이스라인 | 태그 | 보존 브랜치 | 시점 |
|-----------|------|------------|------|
| 마이그레이션 | TBD | TBD | 새 repo 진입 직후 |

- `release/v*` 브랜치는 **체크아웃 전용**. push·머지·삭제 모두 금지 (pre-push hook + 정책)
- hotfix는 받지 않는다. 살 붙이기·버그 수정은 main 트렁크에서, 다음 베이스라인 컷 시 `vX.Y.0` 새 태그 + `release/vX.Y` 신설
- `v*.*.*` 태그 push 시 `release.yml`이 ghcr.io 이미지 + GitHub Release draft 자동 생성

## 한눈에 보는 흐름

```
[오케스트레이터(architect) 작업 할당]
        │
        ▼
git switch main && git pull --ff-only
git switch -c agent/<agent>/<task-slug>          # 브랜치 생성
        │
        ▼
node scripts/task.mjs start --agent <a> --goal "..."  # task 파일 (ADR 0036)
        │
        ▼
[작업 — 메인 worktree에서 직접 또는 sub-agent 호출]
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
[셀프 리뷰 + pre-commit/pre-push 자동 검사로 충분]
        │
        ▼
[사용자 승인] gh pr merge N --rebase --delete-branch
        │
        ▼
git switch main && git pull
node scripts/task.mjs archive T-...
```

**사용자 승인 지점은 정확히 1개**: `gh pr merge`.

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

## 모델 차등 (ADR 0035)

| 에이전트 | 기본 모델 |
|---------|----------|
| architect, ai-engineer, strategy-engine | **opus** |
| backend-core, market-data, frontend | **sonnet** |

**에스컬레이션**: 결과 부족 시 1회 opus 재시도. 또 부족하면 작업 분할 또는 사용자 결정.

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

