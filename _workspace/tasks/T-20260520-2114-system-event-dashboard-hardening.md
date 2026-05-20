---
id: T-20260520-2114-system-event-dashboard-hardening
agent: orchestrator
model: opus
branch: agent/orchestrator/system-event-dashboard-hardening
status: DONE
startedAt: 2026-05-20T21:14:30.930+09:00
completedAt: 2026-05-20T21:22:33.650+09:00
pr: null
parentPhase: ?
---

# 목표
시스템 이벤트 운영 대시보드의 STOMP 백필 계약 불일치와 Kafka publish 영향도 완화

## 결과물 체크리스트
- [x] frontend STOMP backfill DTO 정합
- [x] system-event channel 백필 혼선 차단
- [x] SystemEvent Kafka publish 비동기화 또는 호출 영향 완화
- [x] 관련 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-20T21:14:30.939+09:00 — 작업 시작

## 비고
