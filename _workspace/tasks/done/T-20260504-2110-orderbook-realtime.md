---
id: T-20260504-2110-orderbook-realtime
agent: market-data
model: sonnet
branch: agent/market-data/orderbook-realtime
status: RUNNING
startedAt: 2026-05-04T12:10:12.860Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
호가(H0UNASP0) 실시간 + frontend Orderbook 컴포넌트 — 종목 진입 시 자동 구독

## 결과물 체크리스트
- [x] OrderbookEvent 도메인
- [x] KisOrderbookWsAdapter
- [x] OrderbookPublisher
- [x] market.orderbook.v1 토픽
- [x] SubscriptionManager 호가 trId 통합
- [x] OrderbookStompFanoutListener
- [x] useOrderbookStream 훅
- [x] Orderbook 10-level 컴포넌트
- [x] SymbolDetail 통합
- [x] 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T12:10:12.867Z — 작업 시작

## 비고
