---
id: T-20260612-1507-capital-retry-no-zero-fabricate
agent: backend-core
model: opus
branch: hotfix/capital-retry-no-zero-fabricate
status: RUNNING
startedAt: 2026-06-12T15:07:09.061+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
F10 잔고조회 실패 처리 정정 — rate limit 재시도+stale-cache+skip, 0원 fabricate 제거

## 결과물 체크리스트
- [x] CapitalOutcome sealed 도입
- [x] ConsumerKisBalanceQueryAdapter 재시도 강화
- [x] snapshot cache
- [x] listener skip 경로
- [x] 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T15:07:09.070+09:00 — 작업 시작

## 비고
