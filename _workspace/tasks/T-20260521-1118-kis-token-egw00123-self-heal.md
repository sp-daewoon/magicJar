---
id: T-20260521-1118-kis-token-egw00123-self-heal
agent: backend-core
model: opus
branch: hotfix/kis-token-egw00123-self-heal
status: RUNNING
startedAt: 2026-05-21T11:18:05.988+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KIS 토큰 EGW00123 self-heal — cache evict + 1회 재발급

## 결과물 체크리스트
- [x] KisTokenCacheHelper.evictToken 추가
- [x] ApiKisBalanceQueryAdapter EGW00123 catch+1회 재시도
- [x] 단위 테스트 2건
- [x] gradle :api:check GREEN
- [ ] PR 생성

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-21T11:18:05.998+09:00 — 작업 시작

## 비고
