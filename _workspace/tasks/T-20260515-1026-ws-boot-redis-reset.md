---
id: T-20260515-1026-ws-boot-redis-reset
agent: market-data
model: sonnet
branch: hotfix/ws-boot-redis-reset
status: RUNNING
startedAt: 2026-05-15T10:26:51.920+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
Consumer 부팅 시 Redis candle:today stale HASH reset ApplicationRunner 구현

## 결과물 체크리스트
- [x] RedisCandleTodayResetRunner ApplicationRunner
- [x] 단위 테스트 2건 (watchlist DEL 검증 + 빈 watchlist graceful)
- [x] CHANGELOG 갱신

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-15T10:26:51.930+09:00 — 작업 시작

## 비고
