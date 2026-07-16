---
id: T-20260618-1258-ws-notick-resub-reset
agent: market-data
model: opus
branch: agent/market-data/ws-notick-resub-reset
status: DONE
startedAt: 2026-06-18T12:58:02.605+09:00
completedAt: 2026-06-18T13:09:10.367+09:00
pr: #1049
parentPhase: ?
---

# 목표
KIS WS 무tick self-heal: bare 재구독 무효(ALREADY IN SUBSCRIBE dedup) → UNSUBSCRIBE→지연→SUBSCRIBE 풀리셋 사이클로 교체 + churn 억제 grace (후성 093370 stuck-stream)

## 결과물 체크리스트
- [x] KisWebSocketBridge self-heal 풀리셋 사이클
- [x] RealtimeSubscriptionRouter unsubscribe grace
- [x] 테스트
- [x] KIS규약 KDoc

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-18T12:58:02.618+09:00 — 작업 시작

## 비고
