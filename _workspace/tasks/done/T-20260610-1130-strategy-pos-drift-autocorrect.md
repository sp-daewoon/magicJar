---
id: T-20260610-1130-strategy-pos-drift-autocorrect
agent: backend-core
model: opus
branch: agent/backend-core/strategy-pos-drift-autocorrect
status: DONE
startedAt: 2026-06-10T11:30:02.566+09:00
completedAt: 2026-06-10T11:53:37.748+09:00
pr: #876
parentPhase: ?
---

# 목표
ADR 0115 strategy_position underflow drift 자동보정 가동(execution ledger 귀속 근거 정정)

## 결과물 체크리스트
- [x] ledger 귀속 순수함수
- [x] reconcile 어댑터 배선
- [x] BalanceReconcileJob 통합
- [x] incident 재현 테스트
- [x] ADR amend
- [x] CHANGELOG

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-10T11:30:02.579+09:00 — 작업 시작

## 비고
