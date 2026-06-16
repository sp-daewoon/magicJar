---
id: T-20260616-1722-risk-position-partial-render
agent: backend-core
model: opus
branch: agent/backend-core/risk-position-partial-render
status: RUNNING
startedAt: 2026-06-16T17:22:50.610+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
포지션 한도 사용량 capital 미상 시 config 파생값 부분렌더

## 결과물 체크리스트
- [x] RiskUsageService.buildPositionUsage capital nullable 완화
- [x] PositionUsage DTO 순자산 의존 6필드 nullable화
- [x] RiskUsageServiceTest capital-null 케이스

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-16T17:22:50.620+09:00 — 작업 시작

## 비고
