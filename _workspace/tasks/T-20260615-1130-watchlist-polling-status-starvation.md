---
id: T-20260615-1130-watchlist-polling-status-starvation
agent: market-data
model: opus
branch: agent/market-data/watchlist-polling-status-starvation
status: RUNNING
startedAt: 2026-06-15T11:30:53.828+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
watchlist polling이 status publisher를 굶기는 빨간배너 결함 fix (결함④)

## 결과물 체크리스트
- [x] WatchlistPollingWorker price endpoint 전환
- [x] RealtimeStatusSnapshotPublisher 전용 스케줄러 분리
- [x] 단위테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T11:30:53.839+09:00 — 작업 시작

## 비고
