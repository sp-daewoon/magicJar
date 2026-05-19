---
id: T-20260519-1034-adr-0084-p1-1-order-event-outbox
agent: backend-core
model: opus
branch: agent/backend-core/adr-0084-p1-1-order-event-outbox
status: DONE
startedAt: 2026-05-19T10:34:29.976+09:00
completedAt: 2026-05-19T10:51:41.276+09:00
pr: #524
parentPhase: ?
---

# 목표
ADR 0084 P1-1 order_event_outbox + OrderOutboxPublisherWorker

## 결과물 체크리스트
- [x] Flyway V40 order_event_outbox
- [x] domain OrderEventOutbox 모델
- [x] api OrderEventOutboxJpaEntity·Repository·Adapter
- [x] consumer OrderOutboxPublisherWorker @Scheduled
- [x] 4 producer (OrderIntent·OrderRequested·OrderRejected·OrderFilled) outbox 통합
- [x] 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-19T10:34:29.983+09:00 — 작업 시작

## 비고
