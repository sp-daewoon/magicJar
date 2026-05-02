---
id: T-20260503-0147-track2-r1-market-session-egw
agent: backend-core
model: sonnet
branch: agent/backend-core/track2-r1-market-session-egw
status: RUNNING
startedAt: 2026-05-02T16:47:50.793Z
completedAt: null
pr: null
parentPhase: V2_BASELINE_CONFIRMED
---

# 목표
트랙2 R1 — KRX 캘린더 V10 + MarketSession 도메인 + EGW00202 hotfix

## 결과물 체크리스트
- [ ] Flyway V10 market_calendar 시드
- [ ] MarketSession sealed class + Resolver
- [ ] RealKisOrderAdapter raw HTTP 로깅
- [ ] EGW00202 hashkey 헤더 fix
- [ ] 5 KIS 메서드 회귀 차단

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-02T16:59:03.310Z — T2-T7 hashkey 헤더 + Accept/User-Agent + BUY SLL_TYPE 제거 EGW00202 hotfix 완료

## 비고
