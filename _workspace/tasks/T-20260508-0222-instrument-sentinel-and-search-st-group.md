---
id: T-20260508-0222-instrument-sentinel-and-search-st-group
agent: orchestrator
model: opus
branch: hotfix/instrument-sentinel-and-search-st-group
status: RUNNING
startedAt: 2026-05-08T02:22:20.438+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
instrument 종목마스터 KIS sentinel sanitize + watchlist/검색 securities_group=ST 필터

## 결과물 체크리스트
- [x] (1) KisInstrumentMasterAdapter sanitizeRoe + sanitizeFinancialLong (2) /api/instruments/search securities_group=ST (보통주+우선주) 필터 (3) WatchlistService 응답 필터 + delisted 라벨 (4) domain InstrumentRepository.findActiveStocks 추가

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-08T02:22:20.448+09:00 — 작업 시작

## 비고
