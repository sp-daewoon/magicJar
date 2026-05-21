---
id: T-20260521-1123-ws-reconnected-emit
agent: market-data
model: opus
branch: agent/market-data/ws-reconnected-emit
status: RUNNING
startedAt: 2026-05-21T11:23:16.403+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KisWebSocketBridge WS_RECONNECTED emit 누락 fix (사용자 dogfooding 거짓 끊김 표시)

## 결과물 체크리스트
- [x] doFinally 중복 stale cid 감지 + RECONNECT_LOST emit
- [x] reconnect cid null fallback fresh UUID
- [x] 회귀 보호 테스트 3건

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-21T11:23:16.415+09:00 — 작업 시작

## 비고
