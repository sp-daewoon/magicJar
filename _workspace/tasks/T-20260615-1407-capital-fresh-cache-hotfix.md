---
id: T-20260615-1407-capital-fresh-cache-hotfix
agent: market-data
model: opus
branch: agent/market-data/kis-capital-pressure-hotfix
status: RUNNING
startedAt: 2026-06-15T14:07:30.224+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KIS EGW00215 잔고조회 자가압박 완화: capital fresh-cache short reuse + cache-miss cooldown

## 결과물 체크리스트
- [x] EGW00215 로그 근거 정리
- [x] capital fresh-cache 보강
- [x] PR #984 배포 후 재기동 cache-miss/정각 reconcile 재발 로그 정리
- [x] capital direct failure cooldown 보강
- [x] BUY capital preflight 로 체결 backfill 선호출 회피
- [x] BalanceReconcileJob 시장 OPEN cron 30초 분산
- [x] 회귀 테스트
- [ ] 추가 PR merge + 배포 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T14:10:03.484+09:00 — capital fresh-cache 90s 보강 + consumer targeted/full check GREEN
2026-06-15T14:29:00+09:00 — PR #984 배포 후 14:15 KST EGW00215 재발 확인, capital cooldown/preflight + reconcile 30s 분산 보강

## 비고
