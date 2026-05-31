---
id: T-20260601-0145-friendly-risk-alerts
agent: backend-core
model: opus
branch: agent/backend-core/friendly-risk-alerts
status: DONE
startedAt: 2026-06-01T01:45:26.383+09:00
completedAt: 2026-06-01T01:59:20.001+09:00
pr: #702
parentPhase: ?
---

# 목표
리스크 차단 알림을 한국어 설명과 설정 수식이 포함된 친절한 문구로 개선

## 결과물 체크리스트
- [x] RiskRule별 한국어 알림 문구와 수식 생성
- [x] STOMP/Slack/backfill 토스트에 동일 설명 반영
- [x] 테스트와 task 기록

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-01T01:45:26.392+09:00 — 작업 시작

## 비고
방향 가드: 리스크 계산·주문 차단 정책은 변경하지 않고, `risk.violation.v1` payload와 프론트/Slack 표시 문구만 add-only 확장.

검증:
- `./gradlew :domain:test --tests "kr.spdaewoon.magicjar.domain.risk.RiskAlertMessageTest"` PASS
- `./gradlew :consumer:test --tests "kr.spdaewoon.magicjar.consumer.notification.NotificationFormatterTest" --tests "kr.spdaewoon.magicjar.consumer.notification.NotificationDispatcherTest"` PASS
- `./gradlew :consumer:compileKotlin :api:compileKotlin` PASS
- `./gradlew ktlintCheck` PASS
- `pnpm --dir frontend test src/api/__tests__/riskAlertMessage.test.ts` PASS
- `pnpm --dir frontend typecheck` PASS
- `pnpm --dir frontend build` PASS
- `./gradlew check` PASS
