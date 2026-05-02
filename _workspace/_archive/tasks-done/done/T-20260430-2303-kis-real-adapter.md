---
id: T-20260430-2303-kis-real-adapter
agent: market-data
model: sonnet
branch: agent/market-data/kis-real-adapter
status: DONE
startedAt: 2026-04-30T14:03:57.352Z
completedAt: 2026-04-30T15:30:00.000Z
pr: #32
parentPhase: F_PARALLEL_R1_DISPATCH
---

# 목표
RealKisRestAdapter fetchCandles/fetchQuote 완전 구현 + WireMock IT

## 결과물 체크리스트
- [x] fetchCandles trId=FHKST03010100 (legacy 참조 시 변경)
- [x] fetchQuote trId=FHKST01010100
- [x] 401 invalidate+재발급
- [x] rt_cd 에러 처리 (KisApiException)
- [x] WireMock 5 시나리오 IT
- [x] tokenTtlSeconds 도입

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-01T04:30:00.000Z — 회고 정정: PR #32 main 머지 완료 (R1 dispatch 산출물). Archive로 이동.

## 비고
회고 정정 — task.mjs done/archive 누락분 사후 갱신 (2026-05-01).
