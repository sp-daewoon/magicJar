---
id: T-20260612-1508-strategy-orderbook-subscription
agent: market-data
model: opus
branch: hotfix/strategy-orderbook-subscription
status: DONE
startedAt: 2026-06-12T15:08:35.364+09:00
completedAt: 2026-06-12T15:18:10.630+09:00
pr: #935
parentPhase: ?
---

# 목표
전략 ACTIVE 시 호가(H0STASP0) 구독 자동 시작 + 호가 REST fallback REAL 자격증명 resolve 결함 수선 (F11)

## 결과물 체크리스트
- [x] OrderbookSubscriptionRouter STRATEGY owner 차원 추가
- [x] ActiveStrategyDemandRegistrar 호가 enter/exit 배선
- [x] KisRestAskingPriceAdapter REAL credential resolve (c95b5391 패턴)
- [x] WsSlotBudgetLedger H0STASP0 회계
- [x] 테스트 GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T15:08:35.381+09:00 — 작업 시작

## 비고
