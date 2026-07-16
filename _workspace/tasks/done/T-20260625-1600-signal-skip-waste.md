---
id: T-20260625-1600-signal-skip-waste
agent: strategy
model: opus
branch: agent/strategy/signal-skip-waste
status: DONE
startedAt: 2026-06-25T16:00:46.006+09:00
completedAt: 2026-07-16T23:03:18.021+09:00
pr: #1137
parentPhase: ?
---

# 목표
RealtimeSignalGenerator 헛 신호 생성 2종 앞단 skip(DAILY_LOSS_LIMIT BUY suppress + 보유0 DSL SELL skip) — zero-regression

## 결과물 체크리스트
- [x] 개선① DAILY_LOSS_LIMIT BUY skip
- [x] 개선② 보유0 SELL skip
- [x] 단위 테스트
- [x] zero-regression 보존

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-25T16:00:46.014+09:00 — 작업 시작

## 비고
