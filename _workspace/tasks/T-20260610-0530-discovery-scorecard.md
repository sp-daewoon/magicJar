---
id: T-20260610-0530-discovery-scorecard
agent: strategy
model: opus
branch: agent/strategy-engine/discovery-scorecard
status: IN_PROGRESS
startedAt: 2026-06-10T05:30:00.000+09:00
completedAt:
pr:
parentPhase: ?
---

# 목표
발굴 스코어카드 — 매일 발굴 기록 + 다음날 자동 채점

## 결과물 체크리스트
- [ ] 스코어카드 테이블+Flyway (V77 discovery_scorecard)
- [ ] 일일 snapshot 스케줄러 (08:30 KST)
- [ ] 채점 잡 (23:30 KST — R2 야간 일봉 후)
- [ ] 조회 REST (GET /api/screener/scorecard)
- [ ] 테스트 (snapshot 멱등·채점 계산·미채점 skip)

## 의존성
- DaytradeDiscoverService (발굴 재사용, startRealtimeWatch=false)
- CandleRepository 일봉 (채점 데이터 — R2 MarketDailyNightlySnapshotScheduler 공급)
- market_calendar (영업일 가드)

## 마지막 활동
2026-06-10T05:30:00.000+09:00 — 작업 시작 (task.mjs 분류기 일시 불가로 수동 생성)

## 비고
forward-test 증거가 매일 공짜로 누적되는 발굴 품질 hit-rate 레이어. 자본/주문/watchlist write 0.
