---
description: magicJar 마스터 HTML 갱신 — `_workspace/master.html` 재생성. 사용자 수동 trigger.
---

# /master-index

`scripts/master-index.mjs`를 실행하여 `_workspace/master.html`을 재생성합니다.

## 진행 순서

1. `node scripts/master-index.mjs` 실행
2. exit code 확인 (0이면 GREEN, 1이면 self-check 실패 보고)
3. `git status _workspace/master.html`로 변경 사항 확인
4. 변경 있으면 사용자에게 보고 (파일 크기·갱신 시각·diff 줄 수)
5. 변경 commit 여부는 사용자 결정 (자동 commit X)

## 정합 사항

- spec: `_workspace/specs/2026-05-17-master-html-design.md`
- AI 호출 0 LOCK
- 외부 호출 0 (gh CLI 1건은 graceful, 실패 시 fallback)
