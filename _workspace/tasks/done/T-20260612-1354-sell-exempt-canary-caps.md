---
id: T-20260612-1354-sell-exempt-canary-caps
agent: backend-core
model: opus
branch: hotfix/sell-exempt-canary-caps
status: DONE
startedAt: 2026-06-12T13:54:11.791+09:00
completedAt: 2026-06-12T13:59:50.090+09:00
pr: #928
parentPhase: ?
---

# 목표
F8: 매도 주문 canary cap 전면 면제 + 매도 가격 산정 실패 시 MARKET fallback (라이브 13:31 청산 차단 incident)

## 결과물 체크리스트
- [x] evaluateCanaryHardCap SELL 면제(QTY/NOTIONAL)
- [x] publishSell LIMIT산정실패 MARKET fallback 로그 가시화
- [x] 테스트 회귀

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T13:54:11.800+09:00 — 작업 시작

## 비고
