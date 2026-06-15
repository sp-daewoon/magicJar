---
id: T-20260615-1740-watchlist-polling-and-strategy-drift
agent: backend-core
model: opus
branch: agent/backend-core/watchlist-polling-and-strategy-drift
status: RUNNING
startedAt: 2026-06-15T17:40:37.031+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
운영 회귀 수정 2건: ① watchlist 시세 5초 REST polling 제거(ADR 0124 amend) ② strategy_position 체결직후 drift 근본 fix

## 결과물 체크리스트
- [ ] WatchlistPollingWorker tick 비활성화
- [ ] ADR 0124 amend
- [ ] ConsumerExecutionStrategyPositionAdapter reconcileDrift 보수 가드 확장
- [ ] rebuildTotal 진단
- [ ] :consumer:test+ktlint GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T17:40:37.040+09:00 — 작업 시작

## 비고
