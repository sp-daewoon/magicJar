---
id: T-20260515-1811-kis-ws-approval-revert-and-self-heal
agent: market-data
model: sonnet
branch: hotfix/kis-ws-approval-revert-and-self-heal
status: DONE
startedAt: 2026-05-15T18:11:04.679+09:00
completedAt: 2026-05-17T11:43:08.345+09:00
pr: null
parentPhase: ?
---

# 목표
ADR 0076 Phase 1 A-1+F-1 — approval_key 24h reuse 복원 + OPSP8996 self-heal retry

## 결과물 체크리스트
- [x] A-1: doConnect invalidate 제거 + approval_key 24h TTL reuse
- [x] F-1: OPSP8996 msg_cd 감지 + 1회 retry + 2회 fail skip
- [x] 단위 테스트 3건 (cache HIT 0건 발급·OPSP8996 retry·2회 fail skip)
- [x] gradle check GREEN
- [x] CHANGELOG 갱신

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-15T18:11:04.686+09:00 — 작업 시작

## 비고
