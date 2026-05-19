---
id: T-20260519-1034-adr-0084-p1-2-stomp-event-persistence
agent: backend-core
model: opus
branch: agent/backend-core/adr-0084-p1-2-stomp-event-persistence
status: DONE
startedAt: 2026-05-19T10:34:48.274+09:00
completedAt: 2026-05-19T10:44:47.319+09:00
pr: #523
parentPhase: ?
---

# 목표
ADR 0084 P1-2 STOMP 영속 + backfill REST

## 결과물 체크리스트
- [x] Flyway V41 stomp_event
- [x] domain StompEvent 모델
- [x] api StompEventJpaEntity·Repository·Adapter
- [x] api StompEventBackfillController GET /api/stomp/events?since=
- [x] STOMP publish 시 영속 INSERT 통합
- [x] 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-19T10:34:48.281+09:00 — 작업 시작

## 비고
