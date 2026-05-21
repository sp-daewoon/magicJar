---
id: T-20260521-1323-ws-first-connect-emit
agent: market-data
model: opus
branch: agent/market-data/ws-first-connect-emit
status: DONE
startedAt: 2026-05-21T13:23:30.649+09:00
completedAt: 2026-05-21T13:26:11.037+09:00
pr: #645
parentPhase: ?
---

# 목표
KIS WS startup 첫 연결 WS_CONNECTED emit (PR #644 한계 — 거짓 끊김 영구 잔존 fix)

## 결과물 체크리스트
- [ ] KisWebSocketBridge emit point #5 첫 connect 분기 정정 (WS_CONNECTED action 신규)
- [ ] KisWebSocketBridgeReconnectTest 신규 case 3건 (startup·재연결·정상 1쌍)
- [x] CHANGELOG hotfix 슬롯
- [x] 검증 :consumer:check GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-21T13:23:30.655+09:00 — 작업 시작

## 비고
