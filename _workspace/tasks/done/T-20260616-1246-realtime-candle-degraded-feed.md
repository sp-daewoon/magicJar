---
id: T-20260616-1246-realtime-candle-degraded-feed
agent: backend-core
model: opus
branch: agent/backend-core/realtime-candle-degraded-feed
status: DONE
startedAt: 2026-06-16T12:46:51.260+09:00
completedAt: 2026-06-16T13:11:50.117+09:00
pr: #1009
parentPhase: ?
---

# 목표
ACTIVE 전략 캔들 실시간 복구: LiveCandleUpdater 공통화, WS no-tick 보강 poller, KIS 호출 버킷 분리, fallback BUY 설정 옵션화

## 결과물 체크리스트
- [x] consumer degraded candle feed poller
- [x] shared live candle updater
- [x] optional fallback-buy setting UI/API/consumer gate
- [x] tests
- [x] deploy

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-16T12:46:51.276+09:00 — 작업 시작
2026-06-16T13:17:55+09:00 — PR #1009 merge + api/batch/frontend/consumer 배포 완료. consumer drain guard는 실제 열린 AUTO_EXIT SELL ACK #400 확인 후 SKIP_DRAIN_CHECK=1로 우회, 부팅 후 체결통보 구독 성공 및 093370 1m current source=KIS-POLLING 갱신 확인.

## 비고
