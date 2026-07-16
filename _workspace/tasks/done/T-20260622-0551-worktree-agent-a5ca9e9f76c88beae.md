---
id: T-20260622-0551-worktree-agent-a5ca9e9f76c88beae
agent: backend-core
model: opus
branch: agent/backend-core/autopilot-eval-wiring
status: DONE
startedAt: 2026-06-22T05:51:25.585+09:00
completedAt: 2026-06-22T06:30:00.000+09:00
pr: 1091
parentPhase: autopilot-phase1
---

# 목표
autopilot 평가경로 wiring(enabled 연동·BUY/청산 비대칭)

## 결과물 체크리스트
- [x] 3쿼리 owner 파라미터화 (BUY 구동 2쿼리 `owner IN (:owners)` + 청산 쿼리 비대칭 `(USER AND DISABLED) OR AUTOPILOT`)
- [x] 호출자 enabled 연동 (ConsumerStrategyLookupJpaAdapter·ActiveStrategyCodeProvider·ManagedCandleUniverseProvider — buyEvalOwners())
- [x] Guard disabled-BUY 차단 (AutopilotBuyGuard 벨트앤서스펜더 Reject + listener isAutopilotBuy 진입)
- [x] 테스트 (+12, ConsumerStrategyLookupJpaAdapterTest 17·AutopilotBuyGuardTest 10·MagicjarAutopilotPropertiesTest 3 GREEN)

## 의존성
- 없음 (add-only · 기존 autopilot Phase 0 하니스 위 wiring)

## 마지막 활동
2026-06-22T06:30 — PR #1091 생성, :consumer:check + ktlintCheck + bootJar GREEN

## 비고
브랜치 rename(worktree-agent-* → agent/backend-core/autopilot-eval-wiring)으로 task.mjs done 매칭 실패 → 수동 마킹.
owner='USER' BUY 구동 하드필터 grep 전수 0건 잔존 확인.
