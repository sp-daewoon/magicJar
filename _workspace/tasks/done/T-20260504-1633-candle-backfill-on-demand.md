---
id: T-20260504-1633-candle-backfill-on-demand
agent: market-data
model: sonnet
branch: agent/market-data/candle-backfill-on-demand
status: RUNNING
startedAt: 2026-05-04T07:33:09.851Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
D — 종목 진입 시 자동 일봉 backfill + 매일 16:30 KST scheduled (default5 + holdings)

## 결과물 체크리스트
- [x] MarketCandleBackfillCommandEvent
- [x] api Publisher
- [x] batch Listener with dedup
- [x] DailyCandleBackfillScheduler @Scheduled 16:30 KST
- [x] 단위 테스트
- [x] 컨테이너 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T07:33:09.857Z — 작업 시작

## 비고
