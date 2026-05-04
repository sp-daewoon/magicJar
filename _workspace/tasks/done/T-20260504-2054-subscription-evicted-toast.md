---
id: T-20260504-2054-subscription-evicted-toast
agent: architect
model: opus
branch: agent/architect/subscription-evicted-toast
status: RUNNING
startedAt: 2026-05-04T11:54:47.090Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
SubscriptionEvictedEvent → Kafka → STOMP /topic/notifications → frontend toast UI

## 결과물 체크리스트
- [x] domain notification 이벤트 또는 SubscriptionEvictedEvent Kafka 발행
- [x] consumer ApplicationListener publisher
- [x] api Kafka listener + STOMP fanout
- [x] frontend useNotifications + toast UI
- [x] 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-04T11:54:47.096Z — 작업 시작

## 비고
