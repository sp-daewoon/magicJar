---
id: T-20260522-1713-kis-tps-comprehensive-fix
agent: market-data
model: opus
branch: agent/market-data/kis-tps-comprehensive-fix
status: RUNNING
startedAt: 2026-05-22T17:13:06.833+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
KIS_TPS_EXCEEDED 종합 fix — H1(페이지 burst 완화) + H4(EGW00201 1초 backoff + 1회 재시도) + 보고서 §8·§9 amend

## 결과물 체크리스트
- [x] CandleRefreshService 페이지 sleep 추가
- [x] EGW00201 retry logic 합류
- [x] LiveDailyCandleScheduler priority=BACKFILL 분리
- [x] 보고서 §8 LIVE 검증 evidence + §9 H1+H4 재진단 amend

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-22T17:13:06.843+09:00 — 작업 시작

## 비고
