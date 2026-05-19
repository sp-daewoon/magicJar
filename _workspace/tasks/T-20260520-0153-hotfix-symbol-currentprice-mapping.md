---
id: T-20260520-0153-hotfix-symbol-currentprice-mapping
agent: backend-core
model: opus
branch: agent/backend-core/hotfix-symbol-currentprice-mapping
status: RUNNING
startedAt: 2026-05-20T01:53:29.326+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
SymbolMetaService currentPrice가 stck_sdpr(전일종가) fallback으로 잘못 표시되는 결함 hotfix — quote:last:{code} Redis 빈 경우 KIS stck_prpr 매핑 부재로 발생 (SK하이닉스 1,840,000 vs HTS 종가 1,719,000)

## 결과물 체크리스트
- [x] PriceMetrics currentPrice 필드 추가
- [x] KisRestPriceMetricsAdapter stck_prpr 매핑
- [x] SymbolMetaService fallback 순서 정정
- [ ] 통합 빌드 GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-20T01:53:29.335+09:00 — 작업 시작

## 비고
