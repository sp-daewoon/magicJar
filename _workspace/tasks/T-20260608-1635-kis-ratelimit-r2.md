---
id: T-20260608-1635-kis-ratelimit-r2
agent: backend-core
model: opus
branch: agent/backend-core/kis-ratelimit-r2
status: RUNNING
startedAt: 2026-06-08T16:35:23.812+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
ADR 0116 R2 — KIS rate limit refill 모델 정합 검증 + 스케줄러 분산 확인 + priority 버킷 관측 metric wiring

## 결과물 체크리스트
- [x] refill 근거 주석(3 어댑터)
- [x] 버킷 사용률 metric
- [x] priority timeout metric
- [x] EGW00201 last_bucket metric
- [x] 스케줄러 분산 확인
- [x] 단위 테스트
- [x] gradle check GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-08T16:35:23.824+09:00 — 작업 시작

## 비고
