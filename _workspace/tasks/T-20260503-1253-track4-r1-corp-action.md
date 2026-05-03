---
id: T-20260503-1253-track4-r1-corp-action
agent: market-data
model: sonnet
branch: agent/market-data/track4-r1-corp-action
status: RUNNING
startedAt: 2026-05-03T03:53:49.168Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
트랙4 R1 — KIS Corp Action 다운로드 + raw/adjusted 두 컬럼 + 룩어헤드 차단

## 결과물 체크리스트
- [ ] KisCorpActionPort + Real/Stub adapter + WireMock IT
- [ ] V13 corp_action + V14 candle adjusted columns
- [ ] AdjustedPriceCalculator
- [ ] CorpActionBackfillJob @Scheduled
- [ ] 룩어헤드 차단 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-03T03:53:49.176Z — 작업 시작

## 비고
