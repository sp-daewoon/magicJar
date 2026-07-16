---
id: T-20260619-1656-scalein-base-persist-r2
agent: backend-core
model: opus
branch: agent/backend-core/scalein-base-persist-r2
status: DONE
startedAt: 2026-06-19T16:56:10.427+09:00
completedAt: 2026-06-19T17:40:00.000+09:00
pr: 1061
parentPhase: ADR-0128-R2
---

# 목표
분할 base 영속 wiring R2 + 분할계획 API

## 결과물 체크리스트
- [x] 체결listener base박제·잔여갱신·reset
- [x] 엔티티매핑
- [x] resolveBuyQuantity base주입
- [x] 분할계획API
- [x] 테스트

## 의존성
- R1 PR #1060 (ADR 0128) — domain 필드·순수함수·V90·projector·resolveBuyQuantity 산식 선행 머지.

## 마지막 활동
2026-06-19T17:40 — PR #1061 발행. :consumer:test :api:test ktlintCheck + bootJar smoke GREEN.

## 비고
- base 박제는 sizing 시점(SignalGeneratedKafkaListener) — 1차 buyable은 그 순간에만 정확(체결 Kafka 비동기 분리).
- 배포 X — orchestrator 사용자 승인 후. R3=frontend.
