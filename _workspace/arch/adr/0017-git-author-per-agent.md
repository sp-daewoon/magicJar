# ADR 0017: 에이전트별 Git Author 분리

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (사용자 요청)

## 맥락

지금까지 모든 commit이 `sp-daewoon <sp-daewoon@gmail.com>` + `Co-Authored-By: Claude` 트레일러로 단일화되어 있다. 결과:

- `git log --pretty=format:"%h %an %s"`로 누가 어느 부분을 했는지 즉시 식별 불가
- 매매일지·UI·전략 등 도메인이 다른 변경이 모두 같은 author로 보임
- 회고·인덱싱·디버깅 시 컨텍스트 잃음

사용자 표현: "git naming(user) 커밋을 각각 분리해서 가졌으면 좋겠어. 머지할 때 누가 했던 부분인거구나 하고 바로 알아볼 수 있게끔."

## 결정

**에이전트별 고유 Git author 부여.** 사용자 본인은 본인 명의 그대로.

### Author 매트릭스

| 작업 주체 | name | email |
|----------|------|-------|
| 사용자 본인 | `sp-daewoon` | `sp-daewoon@gmail.com` |
| architect 에이전트 | `magicjar-architect` | `bot+architect@magicjar.local` |
| backend-core 에이전트 | `magicjar-backend-core` | `bot+backend-core@magicjar.local` |
| market-data 에이전트 | `magicjar-market-data` | `bot+market-data@magicjar.local` |
| strategy-engine 에이전트 | `magicjar-strategy` | `bot+strategy@magicjar.local` |
| ai-engineer 에이전트 | `magicjar-ai` | `bot+ai@magicjar.local` |
| frontend 에이전트 | `magicjar-frontend` | `bot+frontend@magicjar.local` |
| qa 에이전트 | `magicjar-qa` | `bot+qa@magicjar.local` |
| 메인 세션 (오케스트레이터) | `magicjar-orchestrator` | `bot+orchestrator@magicjar.local` |

이메일 도메인 `magicjar.local`은 가짜 (실 수신 불가). GitHub의 commit author와 GitHub 사용자 매핑은 안 됨 — author 표시만 분리하면 충분.

### 적용 방법

각 에이전트가 commit 시 `--author` 옵션 또는 임시 git config로 author 지정:

```bash
git -c user.name="magicjar-backend-core" \
    -c user.email="bot+backend-core@magicjar.local" \
    commit -m "feat(core): ..."
```

또는 작업 시작 시 한 번:
```bash
git config user.name "magicjar-backend-core"
git config user.email "bot+backend-core@magicjar.local"
```

후자가 편하지만 다른 작업 전환 시 user 갱신 잊을 위험. **전자(commit별 명시) 권장**.

### Co-Authored-By 트레일러

기존처럼 `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` 트레일러는 유지. author는 에이전트, co-author는 모델.

### 메인 세션의 직접 commit

사용자가 개입한 (대화로 직접 지시) commit도 메인 세션이 commit한 경우 `magicjar-orchestrator` author. 사용자 본인이 git을 직접 친 경우만 `sp-daewoon`.

### 시각화

```
$ git log --pretty=format:"%h %an %s"
fa4628a magicjar-strategy  feat(strategy): Phase B-2 지표·DSL·백테스트
fb4cb02 magicjar-market-data  feat(market-data): KIS/DART/뉴스 어댑터
d451bee magicjar-ai  feat(ai): Spring AI 통합 5개 서비스
1efbcdc magicjar-orchestrator  chore(harness): settings.json + 2-stage merge flow
```

머지 후 누가 무엇을 했는지 즉시 식별 가능.

## 결과

- 긍정: 명확한 책임 추적, 회고·디버깅 시 컨텍스트 보존, GitHub UI에서도 commit author로 분류
- 부정: GitHub 사용자 계정과 매핑 안 됨 (의도적 — bot 표시 명확화)
- 후속:
  - 오케스트레이터 스킬에 commit 시 author 명시 규약 추가
  - 에이전트 정의에 author 정보 명시
  - 첫 적용은 본 PR (chore/harness-v2-six-pack) — orchestrator author로 commit
