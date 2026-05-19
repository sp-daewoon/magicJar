---
id: T-20260519-2105-adr-0089-userid-p2-2-flags-p1-3-stomp-ho
agent: frontend
model: opus
branch: agent/frontend/adr-0089-userid-p2-2-flags-p1-3-stomp-hook
status: DONE
startedAt: 2026-05-19T21:05:44.633+09:00
completedAt: 2026-05-19T21:31:03.861+09:00
pr: #575
parentPhase: ?
---

# 목표
ADR 0089 userId + P2-2 runtime-flags display + P1-3 STOMP hook

## 결과물 체크리스트
- [x] useOnboardingStore.registeredUserId type string → number
- [x] 5+ 파일 type 정정
- [x] RuntimeFlags hook + AppHeader indicator
- [x] useStompReconnect hook + onConnect lastSeenAt + backfill
- [x] typecheck/build GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-19T21:05:44.674+09:00 — 작업 시작

## 비고
