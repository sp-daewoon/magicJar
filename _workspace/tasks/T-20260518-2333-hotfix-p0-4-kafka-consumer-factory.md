---
id: T-20260518-2333-hotfix-p0-4-kafka-consumer-factory
agent: backend-core
model: opus
branch: agent/backend-core/hotfix-p0-4-kafka-consumer-factory
status: RUNNING
startedAt: 2026-05-18T23:33:05.669+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
긴급 hotfix — PR #501 P0-4 KafkaErrorHandlerConfig ConsumerFactory DI 결함

## 결과물 체크리스트
- [x] root cause 분석 | fix 옵션 선택 (Spring Boot KafkaAutoConfiguration ObjectProvider pattern 모방) | KafkaErrorHandlerConfig 정정 | 단위 테스트 갱신 | gradle check GREEN | dogfooding consumer 부팅 evidence

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-18T23:33:05.677+09:00 — 작업 시작

## 비고
