---
id: T-20260918-1322-a3-upsert-writers-precedence
agent: backend-core
model: opus
branch: agent/backend-core/a3-upsert-writers-precedence
status: RUNNING
startedAt: 2026-09-18T13:22:08.237+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
P0-A Phase A-3: upsertAll writer 4개를 feature flag(magicjar.candle.precedence-guard.enabled) 뒤에서 upsertAllWithPrecedence 경로로 전환 (zero-regression, flag default false)

## 결과물 체크리스트
- [x] application.yml flag
- [x] MarketDailyNightlySnapshotService OFFICIAL_DAILY_CLOSE
- [x] MarketDailyDateRepairService REST_CONFIRMED
- [x] DailyFamilyRollupRunner REST_CONFIRMED
- [x] AggregateBackfillRunner REST_CONFIRMED
- [x] 단위테스트 precedence 경로 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-18T13:22:08.244+09:00 — 작업 시작

## 비고
