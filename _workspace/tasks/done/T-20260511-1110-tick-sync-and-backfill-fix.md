---
id: T-20260511-1110-tick-sync-and-backfill-fix
agent: market-data
model: sonnet
branch: agent/market-data/tick-sync-and-backfill-fix
status: DONE
startedAt: 2026-05-11T11:10:23.763+09:00
completedAt: 2026-05-11T12:13:00.000+09:00
pr: #290
parentPhase: ?
---

# 목표
사용자 dogfooding 결함 1 — KIS WS 체결가 tick 등락 필드(PRDY_VRSS·PRDY_CTRT·PRDY_VRSS_SIGN) backend forward (frontend PR #289 STOMP 시그니처 정합).

**scope 축소 (orchestrator 재정정 2026-05-11T13:xx)**: 본 task는 결함 1(등락 forward path)만 단일 PR로 발행. 결함 2(일봉 sparse) + 결함 3(1분봉 깨짐) backfill은 별도 follow-up task로 분리 (직전 초안 `CandleReconciler.kt`가 KisRestPort 우회·credential 첫 row 사용 등 영역/멀티유저 결함 → 폐기 후 재설계 필요).

## 결과물 체크리스트
- [x] TickEvent priceChange/changeRate/changeSign 필드 추가 (domain)
- [x] parseH0Stcnt0 등락 필드 추출 (KisWsMessageParser)
- [x] Redis quote:last + STOMP payload 등락 필드 확장 (KisWebSocketBridge)
- [x] KIS-first raw URL 인용 (open-trading-api ccnl_krx cols)
- [x] 단위 테스트 (KisWsMessageParserTest 2건 추가 — 정상·blank)
- [ ] 사용자 dogfooding 통합 부팅 검증 (PR 머지 후 본 사이클 종결 단계)
- [ ] backlog 갱신 — 결함 2·3 backfill 후속 task 명시

## 의존성
- frontend PR #289 — STOMP 시그니처 정합 (`priceChange`·`changeRate`·`changeSign` 3 필드)
- 머지 순서: 본 PR(backend) → frontend PR #289 (PR #289 description 명시)

## 마지막 활동
2026-05-11T11:10:23.771+09:00 — 작업 시작
2026-05-11T13:xx+09:00 — orchestrator 직전 초안 결함 진단 후 scope 축소 (결함 1만), backfill은 후속 task로 분리

## 비고
- 직전 초안 `CandleReconciler.kt` 결함:
  - KisRestPort 추상 우회 (분봉은 webClient + appKey/secret 직접 사용)
  - REAL credential `firstOrNull` 사용 → 멀티 유저 시 watchlist 종목 owner와 무관한 token으로 backfill
  - 분봉 페이징 종료 조건 문자열 비교 (zero-pad 깨지면 무한루프 risk)
  - 테스트 검증력 0 (mockk relaxed만)
  → 폐기 후 follow-up task에서 KisRestPort 확장(fetchIntradayMinuteCandles) + Owner-token 매핑 + 안전성 보강
- backfill follow-up은 본 PR 머지 + 사용자 dogfooding 검증 완료 후 새 task로 시작
