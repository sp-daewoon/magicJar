---
id: T-20260610-0400-r1-trade-intensity-ingest
agent: market-data
model: opus
branch: agent/market-data/r1-trade-intensity-ingest
status: DONE
startedAt: 2026-06-10T04:00:00.000+09:00
completedAt: 2026-06-10T04:12:00.000+09:00
pr: #867
parentPhase: ADR 0117 R1-A
---

# 목표
R1-A 체결강도 흡수 — KIS WS H0STCNT0 index 14·18·19·20 + TickEvent 필드 (계산 신설 0, KIS 완성값 흡수만)

## 결과물 체크리스트
- [x] WS 파서 index 14·18 추출 (CTTR·ACML_TR_PBMN) + graceful null
- [x] TickEvent tradeIntensity·turnoverAccum nullable add-only
- [x] 필드 게이트 <34 → <21 완화 + BSOP_DATE/TIME getOrNull graceful
- [x] Kafka 전파 (publishTickEvent add + 소비처 하위호환 점검)
- [x] 테스트 5건 (추출·blank null·게이트 graceful·reject·NXT)

## 의존성
- 없음 (독립 best-effort tick 흡수, R2와 무관)

## 마지막 활동
2026-06-10T04:00:00.000+09:00 — 작업 시작

## 비고
- DSL trade_intensity 식별자는 R1-B(strategy) 영역 — 본 PR 제외 (한 PR=한 영역)
- 자체 산식 fallback(19·20 비율)은 범위 외 (spec §3-R1 KIS 직공급 권장)
