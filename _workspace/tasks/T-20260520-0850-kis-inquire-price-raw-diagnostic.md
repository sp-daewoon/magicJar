---
id: T-20260520-0850-kis-inquire-price-raw-diagnostic
agent: backend-core
model: opus
branch: hotfix/kis-inquire-price-raw-diagnostic
status: RUNNING
startedAt: 2026-05-20T08:50:04.037+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
옵션 1: KisRestPriceMetricsAdapter endpoint를 inquire-daily-itemchartprice (FHKST03010100) output1로 교체 — NXT 시간외 영향 제거 + MTS·candle DB 정합. previousClose 매핑 stck_sdpr→stck_prdy_clpr 정확화. 진단 로그 정리.

## 결과물 체크리스트
- [x] KisRestPriceMetricsAdapter endpoint 교체
- [x] PriceMetrics KDoc 정정
- [x] 진단 로그 제거
- [x] SymbolMetaService unchanged 검증
- [ ] 통합 빌드+SK하이닉스 curl evidence

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-20T08:50:04.050+09:00 — 작업 시작

## 비고
