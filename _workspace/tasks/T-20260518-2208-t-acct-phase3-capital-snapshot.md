---
id: T-20260518-2208-t-acct-phase3-capital-snapshot
agent: backend-core
model: opus
branch: agent/backend-core/t-acct-phase3-capital-snapshot
status: DONE
startedAt: 2026-05-18T22:08:11.933+09:00
completedAt: 2026-05-18T22:15:54.397+09:00
pr: #499
parentPhase: ?
---

# 목표
T-ACCT Phase 3 — consumer/risk CapitalSnapshotProvider 실제 어댑터 교체 (POSITION_LIMIT 활성화)

## 결과물 체크리스트
- [x] KisCapitalSnapshotProviderAdapter 신규
- [x] default 어댑터 supersede @ConditionalOnMissingBean 유지
- [x] 단위 테스트 (cache hit·miss·exception)
- [x] gradle :consumer:check GREEN
- [x] CHANGELOG row add-only

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-18T22:08:11.940+09:00 — 작업 시작

## 비고
