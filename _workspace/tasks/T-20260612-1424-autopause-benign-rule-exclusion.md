---
id: T-20260612-1424-autopause-benign-rule-exclusion
agent: backend-core
model: opus
branch: hotfix/autopause-benign-rule-exclusion
status: DONE
startedAt: 2026-06-12T14:24:12.498+09:00
completedAt: 2026-06-12T14:31:22.833+09:00
pr: #932
parentPhase: ?
---

# 목표
AutoPauseGuard benign rule exclusion — 무해 가드(SELL_WITHOUT_POSITION·MAX_CONCURRENT_POSITIONS·SELL_QUANTITY_UNAVAILABLE)를 위반 카운트에서 제외해 상태형 exit 전략 자동정지 결함 fix (F9)

## 결과물 체크리스트
- [x] RiskRule 제외 set 상수화
- [x] countRecentBlockedByStrategy excludedRules 파라미터
- [x] port+2 adapter+JPQL 배선
- [x] AutoPauseGuardTest 회귀
- [x] gradle check+ktlint GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T14:24:12.504+09:00 — 작업 시작

## 비고
