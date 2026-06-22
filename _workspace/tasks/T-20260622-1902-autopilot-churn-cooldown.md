---
id: T-20260622-1902-autopilot-churn-cooldown
agent: backend-core
model: opus
branch: agent/backend-core/autopilot-churn-cooldown
status: RUNNING
startedAt: 2026-06-22T19:02:58.297+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
owner=AUTOPILOT 재진입 churn 억제: 청산(전량 SELL) 후 N분 내 같은 code 재매수 suppress (쿨다운). 수익기회 보존·정당 추세추종 허용·USER 무영향·SELL 무영향·fail-open·enabled=false zero-regression.

## 결과물 체크리스트
- [x] AutopilotReentryCooldownGuard(record+evaluate)
- [x] RedisKeyRegistry.Autopilot.reentryCooldown
- [x] MagicjarAutopilotProperties cooldown 설정
- [x] applySell 청산 hook
- [x] applyAutopilotBuyGuard 게이트 삽입
- [x] 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-22T19:02:58.307+09:00 — 작업 시작

## 비고
