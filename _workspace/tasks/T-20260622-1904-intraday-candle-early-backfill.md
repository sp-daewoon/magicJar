---
id: T-20260622-1904-intraday-candle-early-backfill
agent: market-data
model: opus
branch: agent/market-data/intraday-candle-early-backfill
status: RUNNING
startedAt: 2026-06-22T19:04:27.648+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
ACTIVE 전략 종목 인트라데이 캔들 개장 직후 조기 backfill — FVG/OB zone 개장 형성

## 결과물 체크리스트
- [x] ActiveStrategyIntradayWarmupRunner 신규(개장 직후+부팅시 세션중 전략종목 1m range backfill 트리거)
- [x] 진단 PR 본문

## 의존성
- 재사용: SymbolDetailMinuteGapFillService.submitIfNeeded (검증된 on-demand 1m range backfill 경로)
- 재사용: ActiveStrategyCodeProvider.activeCodes (ACTIVE 전략 종목 단일 경로)

## 마지막 활동
2026-06-22T19:04:27.658+09:00 — 작업 시작
2026-06-22 — 진단 완료(인트라데이 과거 시리즈 backfill 이 전략 활성화 시점에 미발사) + ActiveStrategyIntradayWarmupRunner 추가 + 테스트 4건 + ktlint/test GREEN

## 비고
- 로그의 [TodayCandleSeed] 10:25 는 1d 일봉 시드(별개) — 인트라데이 5m/3m 분봉 미형성의 원인이 아님.
- 손익/비용/order_execution 무관(follow-up H 영역) — 캔들 ingestion 만.
