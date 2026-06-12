---
id: T-20260612-1540-remove-detail-orderbook-ui
agent: frontend
model: opus
branch: refactor/remove-detail-orderbook-ui
status: DONE
startedAt: 2026-06-12T15:40:49.226+09:00
completedAt: 2026-06-12T15:47:21.974+09:00
pr: #938
parentPhase: ?
---

# 목표
종목 상세 화면 호가창(orderbook) UI·STOMP 구독·REST alias 제거 (최고 관리자 결재 — market-data DETAIL 호가 구독 제거 병렬)

## 결과물 체크리스트
- [x] SymbolOrderbook.tsx 삭제
- [x] SymbolDetailPage 호가 구독·state·렌더 제거
- [x] api/symbol.ts 호가 타입·alias 정리
- [x] 테스트 갱신
- [ ] typecheck+build GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T15:40:49.232+09:00 — 작업 시작

## 비고
