---
id: T-20260610-0510-rollup-perf-opt
agent: market-data
model: opus
branch: agent/market-data/rollup-perf-opt2
status: DONE
startedAt: 2026-06-10T05:10:00.000+09:00
completedAt: 2026-06-10T05:40:00.000+09:00
pr: #869
parentPhase: ADR-0120-amend
---

# 목표
1d→1wk/1mo/1y 롤업(ADR 0120) 성능·무지성 재생성 최적화 — batch upsert + R2 incremental + 비동기 병렬 admin (실측 ~3종목/분·45/2,517 fix)

## 결과물 체크리스트
- [x] ConsumerCandleRepositoryAdapter.upsertAll → JDBC batchUpdate (semantics 보존·공유 경로 회귀 0)
- [x] DailyFamilyRollupRunner.rollupDetailed(code, recentDays) — R2 incremental + 경계 정확성
- [x] MarketDailyRollupService 코루틴 Semaphore 병렬 + runRollupAsync (202+runId)
- [x] admin 비동기 202 ACCEPTED + GET /status 상태조회
- [x] ADR 0120 amend §9 (batch·incremental·async)
- [x] 단위테스트 (batch 4 + incremental 2 + async 2)
- [x] :consumer:check GREEN

## 의존성
- ADR 0120 Phase 1 (PR #863/#864) — 본 작업은 그 위 성능 amend.

## 마지막 활동
2026-06-10T05:40:00.000+09:00 — 구현·테스트·ADR amend·CHANGELOG 완료, :consumer:check GREEN

## 비고
upsertAll 공유 경로(R2 snapshot·AggregateBackfillRunner) 회귀 0 최우선. KIS 콜 0·mock 0·LLM 0. CandleQueryService(api) 무변경 — 실시간 라이브 merge 회귀 0.
