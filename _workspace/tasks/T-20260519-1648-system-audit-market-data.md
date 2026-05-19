---
id: T-20260519-1648-system-audit-market-data
agent: market-data
model: opus
branch: agent/market-data/system-audit-2026-05-19
status: DONE
startedAt: 2026-05-19T16:48:36.203+09:00
completedAt: 2026-05-19T16:58:10.228+09:00
pr: #555
parentPhase: system-audit
---

# 목표
시스템 점검 — market-data 영역 + hotfix 의제

## 결과물 체크리스트
- [x] KIS REST/WS 어댑터 매트릭스
- [x] KIS-first raw fetch 정합
- [x] 뉴스·공시 path 점검
- [x] rate limit 가드 매트릭스
- [x] hotfix 의제 도출

## 의존성
- chart-curator 한국석유 보고서(`_workspace/reports/2026-05-19-kor-petroleum-investigation-report.md`) cross-ref
- ADR 0037 (KIS Rate Limit) · ADR 0038 (KIS OAuth) · ADR 0048 (KIS WS) · ADR 0063 (NXT) · ADR 0075 (T-NEWS) · ADR 0076 (NXT/시간외) · ADR 0081 (T-ACCT) · ADR 0084 (P0-1·P0-3) · ADR 0086 (§11 ASC 정규화)

## 마지막 활동
2026-05-19T17:00:00.000+09:00 — 보고서 발행 `_workspace/reports/2026-05-19-system-audit-market-data.md` + 5 deliverable 완료

## 비고

### 핵심 발견 매트릭스 (보고서 §0)

- KIS REST 15개 adapter — KIS-first raw 인용 100% (15/15) 정합
- KIS WebSocket — 재연결·heartbeat·OPSP8996·onReconnect backfill hook 매트릭스 OK
- 뉴스·공시 path — Naver(분당 17건 Redis ZSET 가드) + DART(1분 폴링) OK
- **ADR 0037 Bucket4j stub 상태** — KisRateLimiterPort 미구현, sleep(50ms) 마진만 적용 → P1-1 hotfix
- **CandleRefreshService.parseKisDailyOutput2 ASC 정규화 누락** — api 모듈 별도 path drift → P0-1 hotfix
- **spec 표기 drift** — ADR 0037 "초당 10건" vs 코드 주석 15+ 위치 "20 req/s" → P2-1 hotfix

### hotfix 의제 (4건)

- P0-1: `CandleRefreshService.parseKisDailyOutput2` ASC 정규화 추가 (api 모듈, 즉시)
- P1-1: ADR 0037 Bucket4j 실 구현 + KisRateLimiterPort 합류 (architect 결정 후)
- P1-2: EGW00201 / HTTP 429 명시 backoff + 재시도 매트릭스 (P1-1 합류)
- P2-1: spec 표기 drift "20 req/s" → "10 req/s" replace_all (docs + KDoc, 즉시)

