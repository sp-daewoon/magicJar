---
id: T-20260919-2101-active-trading-mode-toggle
agent: frontend
model: opus
branch: agent/frontend/active-trading-mode-toggle
status: DONE
startedAt: 2026-09-19T21:01:07.588+09:00
completedAt: 2026-09-19T21:06:42.603+09:00
pr: #1218
parentPhase: ?
---

# 목표
활성 주문 모드 전환 UI — 설정 화면 MOCK/REAL 토글(PUT /api/system-config/trading-mode) + RuntimeFlagsIndicator가 DB 활성모드 표시. 시세 REAL 고정 무관. REAL 전환은 조건 충족 시에만.

## 결과물 체크리스트
- [x] onboarding.ts activeTradingMode 타입+useUpdateTradingMode
- [x] runtime.ts activeTradingMode
- [x] TradingModeSection 컴포넌트
- [x] RuntimeFlagsIndicator 활성모드 반영
- [x] 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-19T21:01:07.596+09:00 — 작업 시작

## 비고
