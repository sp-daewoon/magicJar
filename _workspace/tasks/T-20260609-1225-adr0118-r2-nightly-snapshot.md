---
id: T-20260609-1225-adr0118-r2-nightly-snapshot
agent: market-data
model: opus
branch: agent/market-data/adr0118-r2-nightly-snapshot
status: RUNNING
startedAt: 2026-06-09T12:25:38.079+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
ADR 0118 R2 — 야간 23:00 멀티시세 snapshot 스케줄러 + today 일봉 공식 보정(DO UPDATE) + NXT/정규장 토글

## 결과물 체크리스트
- [x] MarketDailySnapshotScheduler 신규(consumer @Scheduled 23:00)
- [x] today 봉 공식 보정 DO UPDATE(today-only 경계)
- [x] NXT/정규장 분기 config 토글(양쪽 빌드 default NXT)
- [x] ENV 토글(application.yml + .env.example)
- [x] 단위테스트(영업일게이트·chunk30·today DO UPDATE·과거봉 불변·NXT토글·100콜산정·priority BG)

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-09T12:25:38.089+09:00 — 작업 시작

## 비고
