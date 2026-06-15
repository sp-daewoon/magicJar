---
id: T-20260615-1959-redis-standard-key-cutover
agent: backend-core
model: opus
branch: agent/backend-core/redis-standard-key-cutover
status: RUNNING
startedAt: 2026-06-15T19:59:41.726+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
Redis 운영 wire key를 권장 Naming Convention으로 전환하고 장마감 old-key cleanup 경로까지 정리

## 결과물 체크리스트
- [x] RedisKeyRegistry current key standard cutover
- [x] legacy cleanup pattern registry
- [x] Redis cleanup runner
- [x] tests and Gradle check
- [ ] PR merge and deploy
- [ ] old Redis key cleanup

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T19:59:41.738+09:00 — 작업 시작
2026-06-15T20:33:00+09:00 — Redis wire key 표준 전환, old copy migration runner 삭제, legacy cleanup script 추가, `./gradlew check` GREEN

## 비고
