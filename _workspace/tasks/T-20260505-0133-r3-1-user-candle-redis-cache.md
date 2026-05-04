---
id: T-20260505-0133-r3-1-user-candle-redis-cache
agent: architect
model: opus
branch: agent/architect/r3-1-user-candle-redis-cache
status: RUNNING
startedAt: 2026-05-04T16:33:45.845Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
ADR 0050 R3-1 — UserCandleCachePort + Redis ZSet adapter + LRU 10 + UserChartCandleService 골격 + 1m·1d 흐름

## 결과물 체크리스트
- [ ] domain UserCandleCachePort
- [ ] consumer RedisUserCandleCacheAdapter
- [ ] consumer UserCandleCacheListener
- [ ] api UserChartCandleService
- [ ] InstrumentController 라우팅
- [ ] 단위 테스트
- [ ] 컨테이너 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T16:33:45.852Z — 작업 시작

## 비고
