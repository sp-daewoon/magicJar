---
id: T-20260520-1006-strategy-evaluator-shared-module
agent: strategy
model: opus
branch: agent/strategy/strategy-evaluator-shared-module
status: RUNNING
startedAt: 2026-05-20T10:06:25.177+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
P0 auto-trading review fix: move JVM strategy evaluator out of consumer so api backtest/screener use real evaluator instead of HOLD stub

## 결과물 체크리스트
- [x] strategy-engine shared module with evaluator
- [x] api/consumer depend on shared evaluator
- [x] HOLD stub test-only or fallback-safe
- [x] regression tests
- [x] CHANGELOG updated
- [x] gradle check

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-20T10:06:25.185+09:00 — 작업 시작

## 비고
