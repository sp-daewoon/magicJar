---
id: T-20260629-1712-auction-liquidation-idemkey-fix
agent: backend-core
model: opus
branch: agent/backend-core/auction-liquidation-idemkey-fix
status: DONE
startedAt: 2026-06-29T17:12:12.111+09:00
completedAt: 2026-06-29T17:29:19.305+09:00
pr: #1139
parentPhase: ?
---

# 목표
autopilot 자본경로 결함 3건 수정: D1 동시호가 marketable LIMIT 청산, D2 손절 idemKey 재시도 안전화, D3 SellRejectAutoPauseTracker read-only tx 버그

## 결과물 체크리스트
- [x] D1 동시호가 청산 LIMIT 분기
- [x] D2 idemKey terminal-safe 재시도
- [x] D3 autoDisable 쓰기 tx fix
- [x] consumer:check GREEN
- [x] 단위테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-29T17:12:12.122+09:00 — 작업 시작

## 비고
