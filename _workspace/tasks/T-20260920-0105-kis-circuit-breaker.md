---
id: T-20260920-0105-kis-circuit-breaker
agent: backend-core
model: opus
branch: agent/backend-core/kis-circuit-breaker
status: RUNNING
startedAt: 2026-09-20T01:05:28.620+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KIS Rate Limit Circuit Breaker 실차단 (Phase 3) — TPS 초과 지속 시 회로 OPEN해 INGESTION/BACKFILL만 차단(계정차단 방지). USER_PATH(주문·잔고)는 OPEN이어도 절대 통과(정책 C). Redis 단일 상태 3JVM 공유. 판정은 domain 순수함수.

## 결과물 체크리스트
- [x] domain KisCircuitBreakerDecision 순수함수+테스트
- [x] Redis circuit state I/O(api/consumer/batch 3어댑터 acquire OPEN 차단+onTpsExceeded/onSuccess 윈도 기록)
- [x] USER_PATH 예외 검증 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-20T01:05:28.629+09:00 — 작업 시작
2026-09-20T15:20 — consumer/batch 어댑터 circuit 이식 완료 (props 3필드 추가 + enforceCircuit/recordCircuitFailure/recordCircuitSuccess + Bean에 Redis connection 배선). ConsumerKisCircuitBreakerAdapterTest 4건 GREEN(OPEN→BACKFILL 차단 / USER_PATH 통과 / CLOSED 통과 / conn 미주입 fail-safe). :domain:test·:consumer:test·:consumer/:batch:compileKotlin 모두 GREEN.

## 비고
