---
id: T-20260521-1118-kis-token-egw00123-self-heal
agent: backend-core
model: opus
branch: hotfix/kis-token-egw00123-self-heal
status: DONE
startedAt: 2026-05-21T11:18:05.988+09:00
completedAt: 2026-05-21T11:25:51+09:00
pr: 641
parentPhase: ?
---

# 목표
KIS 토큰 EGW00123 self-heal — cache evict + 1회 재발급

## 결과물 체크리스트
- [x] KisTokenCacheHelper.evictToken 추가
- [x] ApiKisBalanceQueryAdapter EGW00123 catch+1회 재시도
- [x] 단위 테스트 2건
- [x] gradle :api:check GREEN
- [x] PR 생성 (#641 머지 2026-05-21 02:25:51Z)

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-21T11:25:51+09:00 — PR #641 머지 (커밋 775c87fe)

## 비고
2026-05-22 orchestrator 일괄 audit — pr 필드 미갱신 stale 잔재 정리. PR 머지 흔적 GREEN.
