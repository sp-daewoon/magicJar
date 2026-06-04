---
id: T-20260604-1506-tick-minute-aggregator-race-fix
agent: market-data
model: opus
branch: agent/market-data/tick-minute-aggregator-race-fix
status: RUNNING
startedAt: 2026-06-04T15:06:55.542+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
TickToMinuteAggregator 분 경계 race fix — 같은 ts 1m 이중 발행·분 누락 차단

## 결과물 체크리스트
- [x] deterministic closingTs
- [x] 원자 snapshot
- [x] 중복 Kafka publish 차단
- [x] 회귀 테스트
- [x] CHANGELOG row

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-04T15:06:55.556+09:00 — 작업 시작

## 비고
