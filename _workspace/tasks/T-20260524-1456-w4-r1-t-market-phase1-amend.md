---
id: T-20260524-1456-w4-r1-t-market-phase1-amend
agent: market-data
model: opus
branch: agent/market-data/w4-r1-t-market-phase1-amend
status: RUNNING
startedAt: 2026-05-24T14:56:54.750+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
T-MARKET Phase 1 — KIS adapter 5 + cron Job 2 + Flyway V47·V48

## 결과물 체크리스트
- [x] domain port MarketIndexFetcherPort
- [x] MarketIndexSnapshotRepository
- [x] SectorIndexSnapshotRepository
- [x] domain DTO IndexCode + MarketIndexSnapshot + SectorIndexSnapshot
- [x] KIS adapter 5 (FHPUP02100000 + FHPUP02120000 + FHPUP02140000 + HHDFS00000300 + HHDFS76240000)
- [x] JPA entity·repository·adapter 2쌍
- [x] batch job MarketIndexSyncJob
- [x] batch job MarketIndexDailyCloseJob
- [x] Flyway V47 market_index_snapshot
- [x] Flyway V48 sector_index_snapshot
- [x] KisCallPriority INGESTION_LOW enum
- [x] Kafka topic market.index.updated.v1
- [x] Kafka topic market.sector.updated.v1
- [x] 단위 테스트 ~10건
- [x] CHANGELOG slot in-place edit

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-24T14:56:54.756+09:00 — 작업 시작

## 비고
