---
id: T-20260525-1509-market-calendar-holiday
agent: orchestrator
model: opus
branch: agent/orchestrator/market-calendar-holiday
status: DONE
startedAt: 2026-05-25T15:09:46.904+09:00
completedAt: 2026-05-25T15:25:53.573+09:00
pr: null
parentPhase: ?
---

# 목표
KRX 휴장일 게이트 보강과 웹 시장 캘린더/일정 관리 기능을 반영하고 ADR·task·검증 evidence를 정합화

## 결과물 체크리스트
- [x] 휴장일 게이트 코드
- [x] 시장 캘린더 API와 DB
- [x] 시장 캘린더 웹 UI
- [x] ADR 0104와 인덱스 정합
- [x] 테스트·배포 evidence

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-25T15:09:46.912+09:00 — 작업 시작

## 비고
- 검증: `./gradlew :batch:test :api:test :market-trend-engine:test` GREEN, `./gradlew :api:ktlintCheck :batch:ktlintCheck :api:test` GREEN.
- 프론트 검증: `pnpm typecheck`, `pnpm test -- --run src/features/market/__tests__/MarketCalendarPage.test.tsx src/features/market/__tests__/MarketIndexStrip.test.tsx src/features/market/__tests__/market-api.test.ts`, `pnpm build` GREEN.
- 런타임 evidence: `GET /api/market/calendar?from=2026-05-25&to=2026-05-25` → `isBusinessDay=false`, `holidayName=부처님오신날`; `/market/calendar` Playwright smoke → title/holiday 표시, console error 0.
- 데이터 점검: `market_index_snapshot=0`, `sector_index_snapshot=0`, 지수 candle(`0001`,`1001`,`.IXIC`,`.SPX`) 0건 → 지수 UI `dataInsufficient=true` 원인은 휴장일이 아니라 초기 지수 데이터 부재.
- 배포 evidence: docker compose `api-app`, `batch-app`, `frontend` healthy 확인.
