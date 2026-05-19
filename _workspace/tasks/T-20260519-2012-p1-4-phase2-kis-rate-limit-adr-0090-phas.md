---
id: T-20260519-2012-p1-4-phase2-kis-rate-limit-adr-0090-phas
agent: market-data
model: opus
branch: agent/market-data/p1-4-phase2-kis-rate-limit-adr-0090-phase1
status: RUNNING
startedAt: 2026-05-19T20:12:41.393+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
P1-4 Phase 2 KIS rate limit 통합 + ADR 0090 Phase 1 instrument_status_history

## 결과물 체크리스트
- [x] KIS 어댑터 20+ rate limiter acquire 통합
- [x] EGW00201/HTTP 429 backoff
- [x] spec drift 10 req/s 정정
- [x] Flyway V42 instrument_status_history
- [x] market-data status 변화 detect cron
- [x] 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-19T20:12:41.403+09:00 — 작업 시작

## 비고
