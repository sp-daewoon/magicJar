---
id: T-20260612-1540-remove-detail-orderbook
agent: market-data
model: opus
branch: refactor/remove-detail-orderbook
status: RUNNING
startedAt: 2026-06-12T15:40:36.151+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
종목 상세(DETAIL) 호가 구독 경로 제거 — 호가(H0STASP0)는 전략 ACTIVE 경로(STRATEGY owner) 전용화

## 결과물 체크리스트
- [ ] OrderbookSubscriptionRouter DETAIL 경로 제거 / Kafka listener orderbook DETAIL 호출 제거 / REST 호가 alias deprecated / WsSlotBudgetLedger 예산 효과 명시 / CLAUDE.md·policies 방향성 가드 문구 갱신 / 테스트 회귀 0

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T15:40:36.161+09:00 — 작업 시작

## 비고
