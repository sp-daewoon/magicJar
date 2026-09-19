---
id: T-20260919-2215-reconcile-db-active-mode
agent: backend-core
model: opus
branch: agent/backend-core/reconcile-db-active-mode
status: DONE
startedAt: 2026-09-19T22:15:25.911+09:00
completedAt: 2026-09-19T22:20:42.372+09:00
pr: #1226
parentPhase: ?
---

# 목표
GAP-2 잔여 — reconcile/balance/snapshot 경로 8곳이 accountType을 env magicjarMode 대신 DB 활성모드(ActiveAccountTypeResolver) 기준으로. 잔고/포지션/원장 대조가 활성모드와 정합. env상한 절대 유지, 실주문0.

## 결과물 체크리스트
- [x] ExecutionCostReconcileWorker
- [x] KisReconcileBalanceAdapter
- [x] KisLedgerSelfHealWorker
- [x] ManualReconcileService
- [x] BalanceReconcileJob
- [x] ConsumerRiskPositionAdapter
- [x] KisCapitalSnapshotProviderAdapter
- [x] ExternalOrderAccountResolver 정합
- [x] 회귀 확인

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-19T22:15:25.917+09:00 — 작업 시작

## 비고
