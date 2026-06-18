---
id: T-20260618-1119-expired-dayorder-rtcd7-fix
agent: backend-core
model: opus
branch: agent/backend-core/expired-dayorder-rtcd7-fix
status: RUNNING
startedAt: 2026-06-18T11:19:38.728+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
ExpiredDayOrderSyncWorker KIS 조회일자(RT_CD_7) 결함으로 좀비 ACK day-order 청소 마비 → 익절 청산 deadlock 근본 fix

## 결과물 체크리스트
- [x] 워커 KIS 일별체결조회 날짜범위 정합 + 단위테스트 + RT_CD_7 재발 방지

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-18T11:19:38.738+09:00 — 작업 시작

## 비고
