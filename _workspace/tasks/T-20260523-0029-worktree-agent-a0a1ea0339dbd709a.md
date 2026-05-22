---
id: T-20260523-0029-worktree-agent-a0a1ea0339dbd709a
agent: market-data
model: opus
branch: agent/market-data/pr-1-kis-block-subscribeon-hotfix
status: RUNNING
startedAt: 2026-05-23T00:29:39.957+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KIS .block() subscribeOn hotfix

## 결과물 체크리스트
- [x] KisOrderInquireCcldHttpAdapter subscribeOn 정정
- [x] KisOrderInquireDailyCcldHttpAdapter subscribeOn 정정
- [x] KisExecutionBackfillService.runBackfillSafely isInNonBlockingThread 가드
- [ ] BlockHound 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-23T00:29:39.964+09:00 — 작업 시작

## 비고
