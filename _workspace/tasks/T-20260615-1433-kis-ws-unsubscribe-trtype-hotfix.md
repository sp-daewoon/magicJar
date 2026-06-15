---
id: T-20260615-1433-kis-ws-unsubscribe-trtype-hotfix
agent: market-data
model: opus
branch: agent/market-data/kis-ws-unsubscribe-trtype-hotfix
status: RUNNING
startedAt: 2026-06-15T14:33:18.755+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KIS WS unsubscribe OPSP8997 invalid_tr_type 잔불 제거

## 결과물 체크리스트
- [x] tr_type 2 unsubscribe 정정
- [x] 회귀 테스트
- [x] 보고서/changelog
- [ ] 배포 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T14:35:53.163+09:00 — unsubscribe tr_type=2 정정 + WS targeted/full consumer check GREEN

## 비고
