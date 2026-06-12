---
id: T-20260612-1803-detail-orderbook-live-only
agent: market-data
model: opus
branch: agent/market-data/detail-orderbook-live-only
status: RUNNING
startedAt: 2026-06-12T18:03:20.446+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
종목 상세 호가 구독 표시 중에만 방식으로 복원 (enter 구독/exit 즉시 해제)

## 결과물 체크리스트
- [x] OrderbookSubscriptionRouter DETAIL owner enter구독 exit즉시해제
- [x] SymbolDetailCommandKafkaListener orderbook enter exit 재배선
- [x] DETAIL 호가 idle 15분 안전망
- [x] SymbolController orderbook alias orderbookSlot 응답 복원
- [x] CLAUDE.md candle-data-policy 방향성 가드 재정정
- [x] 테스트 enter exit STRATEGY공존 idle안전망 현재가불변

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T18:03:20.453+09:00 — 작업 시작

## 비고
