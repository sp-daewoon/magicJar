---
id: T-20260610-1655-daytrade-paper-r1-capital-port
agent: backend-core
model: opus
branch: agent/backend-core/daytrade-paper-r1-capital-port
status: DONE
startedAt: 2026-06-10T16:55:22.581+09:00
completedAt: 2026-06-11T00:17:53.167+09:00
pr: #903
parentPhase: ADR-0122-R1
---

# 목표
ADR 0122 R1 PaperCapitalSnapshotProvider 포트 + capital 분기 시그니처 (도메인+시그니처 라운드)

## 결과물 체크리스트
- [x] PaperCapitalSnapshotProvider 포트 (domain port, Spring 의존 0)
- [x] capital 분기 시그니처 (StrategyOrigin enum 직접 의존 0 — CapitalSnapshotSelector selector 계약)
- [x] 단위 테스트 (CapitalSnapshotSelectorTest 5건 — 시그니처/계약 수준)
- [x] PR #903

## 의존성
- 선행: ADR 0115 P2-⑥ OrderTrigger enum (main 존재 확인됨)
- 병렬(add-only): strategy-engine — Strategy.origin + StrategyOrigin enum (파일 touch 금지)

## 마지막 활동
2026-06-10T16:55:22.591+09:00 — 작업 시작

## 비고
- spec: _workspace/specs/2026-06-10-daytrade-mock-paper-dispatch-spec.md §3-R1 계약 2
- R1 범위만 — 어댑터 배선·영속·REST·Flyway·RiskConfig 페이퍼 프로필 실구현은 R2 몫(선침범 금지)
