---
id: T-20260919-2054-active-trading-mode-api
agent: backend-core
model: opus
branch: agent/backend-core/active-trading-mode-api
status: DONE
startedAt: 2026-09-19T20:54:34.191+09:00
completedAt: 2026-09-19T20:59:38.143+09:00
pr: #1216
parentPhase: ?
---

# 목표
활성 주문 모드 조회/변경 API — GET /api/system-config에 activeTradingMode 노출 + PUT /api/system-config/trading-mode 변경. MOCK 항상 허용, REAL은 env 상한+동의 시에만. 재기동 없는 전환 창구.

## 결과물 체크리스트
- [x] SystemConfigResponse activeTradingMode 필드
- [x] TradingModeUpdateRequest DTO
- [x] PUT endpoint
- [x] OnboardingService.updateActiveTradingMode
- [x] tests

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-19T20:54:34.201+09:00 — 작업 시작

## 비고
