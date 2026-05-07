---
id: T-20260508-0012-pr-21-instrument-jpa-expansion
agent: backend-core
model: sonnet
branch: agent/backend-core/pr-21-instrument-jpa-expansion
status: RUNNING
startedAt: 2026-05-08T00:12:37.260+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
PR-21b backend-core — InstrumentJpaEntity 83 필드 보강 (Tier 1·2·3·4 70 add-only) + InstrumentRepository 조회 메서드 보강 (findActiveTradeable 등) + InstrumentSearchService 응답 보강 (type 분류 + 시총·매매 가드 노출) + WatchlistEntryDto 보강 (type·marketCapPrev·tradingHalt·managementStock·industryMedium 6 필드 추가) + SymbolMetaService 응답 보강 + InstrumentDto 보강 + smoke 1건

## 결과물 체크리스트
- [ ] api/.../persistence/InstrumentJpaEntity.kt(70 필드 보강) + api/.../persistence/InstrumentJpaRepository.kt(메서드 추가) + api/.../persistence/InstrumentRepositoryAdapter.kt(implement) + api/.../service/InstrumentSearchService.kt(응답 보강) + api/.../service/SymbolMetaService.kt(보강) + api/.../service/WatchlistService.kt(응답 보강) + api/.../controller/{InstrumentDto
- [ ] WatchlistDto}.kt(필드 추가) + domain/.../instrument/port/InstrumentRepository.kt(메서드 시그니처 add) + smoke 1건

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-08T00:12:37.266+09:00 — 작업 시작

## 비고
