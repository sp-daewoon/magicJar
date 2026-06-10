---
id: T-20260609-1312-strategy-position-replay-harden
agent: strategy
model: opus
branch: hotfix/strategy-position-replay-harden
status: DONE
startedAt: 2026-06-09T13:12:02.619+09:00
completedAt: 2026-06-09T13:20:28.146+09:00
pr: #841
parentPhase: ?
---

# 목표
strategy_position replay TOCTOU 직렬화(advisory lock)·테스트 보강

## 결과물 체크리스트
- [x] TOCTOU advisory-lock 직렬화
- [x] replay 테스트 매트릭스(sentinel·빈executions·null orderId·순차 멱등·동시 직렬화·under-count 명세)
- [x] :consumer:check GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-09T13:12:02.628+09:00 — 작업 시작

## 비고
