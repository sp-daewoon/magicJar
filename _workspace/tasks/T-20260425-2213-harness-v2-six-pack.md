---
id: T-20260425-2213-harness-v2-six-pack
agent: architect
model: opus
branch: chore/harness-v2-six-pack
status: RUNNING
startedAt: 2026-04-25T13:13:43.101Z
completedAt: null
pr: null
parentPhase: harness-v2
---

# 목표
하네스 v2 — 6 패키지 일괄 적용 (단일 worktree·모델 차등·머지 단순화·QA on-demand·Git author 분리·Task 시스템)

## 결과물 체크리스트
- [x] scripts/task.mjs CLI 헬퍼
- [x] ADR 0013 단일 worktree 정책
- [ ] ADR 0014 모델 차등 + 에스컬레이션
- [ ] ADR 0015 머지 흐름 단순화
- [ ] ADR 0016 QA on-demand
- [ ] ADR 0017 Git author 분리
- [ ] ADR 0018 Task 파일 시스템
- [ ] 7개 에이전트 frontmatter model 차등
- [ ] 오케스트레이터 스킬 v2 개정
- [ ] boundary-qa 스킬 on-demand 반영
- [ ] .husky/pre-push qa 차단 폐기
- [ ] render-status.mjs task 파일 파싱
- [ ] settings.json 정리
- [ ] CLAUDE.md 변경 이력
- [ ] README 워크플로우 갱신
- [ ] 대시보드에 task 카드 노출

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-04-25T13:27:16.376Z — render-status.mjs v2 + dashboard 21KB 확인. 다음: 커밋·push·PR

## 비고
