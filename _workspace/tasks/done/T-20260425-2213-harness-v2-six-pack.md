---
id: T-20260425-2213-harness-v2-six-pack
agent: architect
model: opus
branch: chore/harness-v2-six-pack
status: DONE
startedAt: 2026-04-25T13:13:43.101Z
completedAt: 2026-04-25T22:31:48+09:00
pr: 9
mainCommit: e3300ab
parentPhase: C
---

# 목표
하네스 v2 — 6 패키지 일괄 적용 (단일 worktree·모델 차등·머지 단순화·QA on-demand·Git author 분리·Task 시스템)

## 결과물 체크리스트
- [x] scripts/task.mjs CLI 헬퍼
- [x] ADR 0013 단일 worktree 정책
- [x] ADR 0014 모델 차등 + 에스컬레이션
- [x] ADR 0015 머지 흐름 단순화
- [x] ADR 0016 QA on-demand
- [x] ADR 0017 Git author 분리
- [x] ADR 0018 Task 파일 시스템
- [x] 7개 에이전트 frontmatter model 차등
- [x] 오케스트레이터 스킬 v2 개정
- [x] boundary-qa 스킬 on-demand 반영
- [x] .husky/pre-push qa 차단 폐기
- [x] render-status.mjs task 파일 파싱
- [x] settings.json 정리
- [x] CLAUDE.md 변경 이력
- [x] README 워크플로우 갱신
- [x] 대시보드에 task 카드 노출

## 의존성
- Phase B 종료(qa→main PR #8 머지) 이후 진행

## 마지막 활동
2026-04-25T22:31:48+09:00 — PR #9 머지 완료(main e3300ab). 후속으로 bootRun hotfix(PR #10, b4553a8)로 Spring Boot 정상 부팅 확인.

## 비고
Phase C(하네스 유지보수) 단일 task로 처리.
