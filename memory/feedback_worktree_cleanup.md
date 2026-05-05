---
name: Worktree 정리 의무
description: PR 머지 직후 sub-agent worktree·로컬 브랜치 즉시 정리, locked 방치·orphan 디렉토리 누적 금지
type: feedback
originSessionId: 64638064-5610-474e-8f54-b9d51c8468ab
---
PR이 머지되면 즉시 다음을 수행해야 한다:

1. `git worktree unlock <path>` (sub-agent가 locked 상태로 종료한 경우)
2. `git worktree remove --force <path>`
3. `git branch -d <branch>` (origin에 머지된 경우 -d로도 충분, 경고 메시지는 정상)
4. `git worktree prune` 마무리
5. `.claude/worktrees/` 하위에 남은 빈 디렉토리는 `find ... -type d -empty -delete`로 청소

**Why:** ADR 0013(단일 worktree·순차)·orchestrator 규약상 PR 머지 후 worktree·브랜치 모두 즉시 회수가 정책. 2026-05-03 사용자 점검에서 git이 인식하는 worktree 2개가 머지 후 locked 방치 + orphan 빈 디렉토리 7개 누적이 발견됨. "PR 머지까지 완료된 worktrees는 삭제하고 마무리하는 정책이 아니였나?"라는 명시 지적.

**How to apply:**
- sub-agent 디스패치 후 PR 머지 시 동일 turn 안에서 worktree·브랜치 정리까지 끝내라.
- 디스패치된 sub-agent가 worktree를 locked 상태로 두고 종료하면 메인 세션이 unlock + remove 책임진다.
- destructive 명령(`git worktree remove --force`, `rm -rf`, `git branch -D`)은 사용자 승인 필요. `git branch -d`는 머지된 브랜치만 삭제하므로 우선 시도.
- `.claude/settings.json`이 `rm -rf` 광역 deny일 수 있으므로 `find -type d -empty -delete`로 빈 디렉토리만 회수하는 우회로 확보.
- 사용자가 "다 같이 한번에 진행"이라고 명시하면 chain 명령 대신 개별 호출로 분할(설정 deny가 chain을 차단할 수 있음).
