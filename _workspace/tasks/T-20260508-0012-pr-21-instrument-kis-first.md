---
id: T-20260508-0012-pr-21-instrument-kis-first
agent: market-data
model: sonnet
branch: agent/market-data/pr-21-instrument-kis-first
status: RUNNING
startedAt: 2026-05-08T00:12:31.236+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
PR-21a market-data — KIS 종목 마스터 본격 파싱 (KOSPI 70 widths + KOSDAQ 64 widths Python 1:1 정합) + Flyway V5 (70 컬럼 add-only ALTER + 8 CHECK + 8 인덱스) + InstrumentType enum 'DR' add + scrt_grp_cls_code 권위 매핑 + KisInstrumentRaw data class + InstrumentSyncJob upsert 70+ 필드 보강 + smoke 1건 (KIS-first 정책 위반 정정 + 자체 추론 0건 — pr-21-kis-header-raw-verification.md 직접 인용)

## 결과물 체크리스트
- [x] api/src/main/resources/db/migration/V5__instrument_kis_first_expansion.sql + batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt(보강) + KisInstrumentRaw.kt(70+ 필드) + batch/.../job/instrument/InstrumentSyncJob.kt(upsert 보강) + domain/.../instrument/Instrument.kt(InstrumentType 'DR' enum add) + batch/.../persistence/BatchInstrumentJpaEntity.kt(write 필요 필드 보강) + smoke 1건

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-08T00:12:31.252+09:00 — 작업 시작

## 비고
