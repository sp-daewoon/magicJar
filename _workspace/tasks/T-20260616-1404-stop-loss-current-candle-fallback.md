---
id: T-20260616-1404-stop-loss-current-candle-fallback
agent: backend-core
model: opus
branch: agent/backend-core/stop-loss-current-candle-fallback
status: DONE
startedAt: 2026-06-16T14:04:45.777+09:00
completedAt: 2026-06-16T14:11:59.045+09:00
pr: #1013
parentPhase: ?
---

# 목표
후성 #86 손절 미발화 P0 보정: 단순 전략 손절 평가와 no-tick current candle 복구

## 결과물 체크리스트
- [x] 비-scaleIn fixedStopLoss 평가 추가
- [x] no-tick fallback current 1m 복구
- [x] polling 가격 손절 안전망 연결
- [x] 회귀 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-16T14:04:45.795+09:00 — 작업 시작
2026-06-16T14:11:02+09:00 — 구현 및 검증 완료

## 검증
- `./gradlew :consumer:test --tests "kr.spdaewoon.magicjar.consumer.signal.RealtimeSignalGeneratorTest" --tests "kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcherTest" --tests "kr.spdaewoon.magicjar.consumer.reconciliation.NoTickMinuteFallbackServiceTest" --tests "kr.spdaewoon.magicjar.consumer.ingestion.ActiveStrategyCandleFeedPollerTest"`
- `./gradlew :consumer:bootJar`

## 비고
