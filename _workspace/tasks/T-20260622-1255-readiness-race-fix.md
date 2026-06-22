---
id: T-20260622-1255-readiness-race-fix
agent: backend-core
model: opus
branch: agent/backend-core/readiness-race-fix
status: RUNNING
startedAt: 2026-06-22T12:55:59.108+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
readiness off-by-one race fix(false positive 제거·진짜 stale 차단 보존)

## 결과물 체크리스트
- [x] DataReadinessService 정정
- [x] 신호평가 경계정렬 정합
- [x] 테스트(race·진짜stale)

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-22T12:55:59.120+09:00 — 작업 시작

## 비고
