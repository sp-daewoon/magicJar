---
id: T-20260919-2154-execution-sync-db-active-mode
agent: backend-core
model: opus
branch: agent/backend-core/execution-sync-db-active-mode
status: RUNNING
startedAt: 2026-09-19T21:54:33.946+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
GAP-2 교정 — 체결 sync/backfill이 env magicjarMode 대신 DB 활성모드(ActiveTradingModePort) 기준으로 accountType 결정. min(env상한,DB활성) 공용 해소기 도입. MOCK 체결이 포지션에 반영되도록. 자본안전 실주문0, env=MOCK이면 무조건 MOCK 유지.

## 결과물 체크리스트
- [x] ActiveAccountTypeResolver 공용 해소기
- [x] 체결 sync 경로(backfill/SignalGenerated/OrderRouter/WsListener/StuckAck) 적용
- [x] 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-19T21:54:33.955+09:00 — 작업 시작

## 비고
