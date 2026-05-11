---
id: T-20260511-1110-tick-sync-display
agent: frontend
model: sonnet
branch: agent/frontend/tick-sync-display
status: DONE
startedAt: 2026-05-11T11:10:10.757+09:00
completedAt: 2026-05-11T11:23:32.221+09:00
pr: #289
parentPhase: ?
---

# 목표
현재가·등락률·등락가 싱크 결함 frontend mapping (사용자 dogfooding hotfix)

## 결과물 체크리스트
- [x] TickPayload 인터페이스 확장
- [x] SymbolDetail 컴포넌트 changeRate STOMP source 우선
- [x] 색상·부호 표시 한국식 정합
- [x] fallback REST 응답
- [x] 단위 테스트

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-11T11:10:10.765+09:00 — 작업 시작

## 비고
