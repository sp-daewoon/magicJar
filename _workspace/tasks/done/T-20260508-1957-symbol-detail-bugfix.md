---
id: T-20260508-1957-symbol-detail-bugfix
agent: backend-core
model: sonnet
branch: agent/frontend/symbol-detail-bugfix
status: DONE
startedAt: 2026-05-08T19:57:51.872+09:00
completedAt: 2026-05-08T20:02:58.113+09:00
pr: #249
parentPhase: ?
---

# 목표
CandleQueryService supportedIntervals에 1wk·1mo·1y 추가 (DB 데이터 이미 적재됨)

## 결과물 체크리스트
- [ ] supportedIntervals 8종 확장|API /candles?interval=1wk|1mo|1y 200 OK|기존 5종 회귀 0|application.yml whitelist 동기화 (있다면)

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-08T19:57:51.879+09:00 — 작업 시작

## 비고
