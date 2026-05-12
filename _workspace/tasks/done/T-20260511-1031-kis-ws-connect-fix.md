---
id: T-20260511-1031-kis-ws-connect-fix
agent: market-data
model: sonnet
branch: agent/market-data/kis-ws-connect-fix
status: DONE
startedAt: 2026-05-11T10:31:31.406+09:00
completedAt: 2026-05-12T13:37:25.112+09:00
pr: null
parentPhase: ?
---

# 목표
KIS WS connect 누락 결함 hotfix — 장중 실시간 시세 수신 0건 (사용자 dogfooding)

## 결과물 체크리스트
- [x] KisWsPort connect 시그니처 추가
- [x] KisWebSocketBridge override 정합
- [x] WatchlistIngestionWorker 또는 PositionBootstrapRunner에서 connect 호출
- [x] 단위 테스트
- [ ] 통합 부팅 검증
- [x] backlog 갱신

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-11T10:31:31.413+09:00 — 작업 시작

## 비고
