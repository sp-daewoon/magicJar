---
id: T-20260605-1522-order-path-ratelimit-priority
agent: backend-core
model: opus
branch: agent/backend-core/order-path-ratelimit-priority
status: DONE
startedAt: 2026-06-05T15:22:42.622+09:00
completedAt: 2026-06-05T15:40:42.179+09:00
pr: #749
parentPhase: ?
---

# 목표
주문 path KIS rate limit 우선권 보호

## 결과물 체크리스트
- [x] priority 차등 라우팅
- [x] approval limiter 합류
- [x] hashkey+order 묶음 보호
- [x] account refresh single-flight
- [x] 회귀 테스트
- [x] CHANGELOG row

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-05T15:22:42.637+09:00 — 작업 시작

## 비고
