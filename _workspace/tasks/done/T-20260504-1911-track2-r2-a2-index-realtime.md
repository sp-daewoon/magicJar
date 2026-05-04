---
id: T-20260504-1911-track2-r2-a2-index-realtime
agent: market-data
model: sonnet
branch: agent/market-data/track2-r2-a2-index-realtime
status: RUNNING
startedAt: 2026-05-04T10:11:33.346Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
R2-A2 지수 실시간 — KOSPI·KOSDAQ KIS WS H0UPCNT0 + Kafka + STOMP + frontend Dashboard 실시간

## 결과물 체크리스트
- [x] IndexTickEvent 도메인
- [x] KisIndexWsAdapter
- [x] IndexSubscriber 부팅 자동 구독
- [x] IndexPublisher
- [x] market.index.tick.v1 토픽
- [x] IndexStompFanoutListener
- [x] useMarketIndexStream 훅
- [x] Dashboard 실시간 통합
- [x] 단위 테스트
- [x] 컨테이너 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T10:11:33.352Z — 작업 시작

## 비고
