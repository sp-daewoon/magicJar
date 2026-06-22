---
id: T-20260622-1257-worktree-agent-a836fffb9c00618fa
agent: market-data
model: opus
branch: agent/market-data/ws-midsession-tick-fix
status: RUNNING
startedAt: 2026-06-22T12:57:52.236+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
장중 신규구독 종목 H0UNCNT0 시세 전달 fix

## 결과물 체크리스트
- [x] 근본원인 진단 — KIS WS 등록 한도(40) silent 초과. STRATEGY 활성 1종목당 체결가(H0UNCNT0)+호가(H0UNASP0) 2채널인데, tick router는 PRICE_TICK_MAX(30) 단독만·orderbook router는 한도 enforce 0 → 총합 40 초과를 인지 못한 채 발사 → KIS가 overflow 등록을 "acked but not streaming"으로 영구 굶김. 부팅-구독(먼저 등록·한도 내)만 정상 streaming.
- [x] WS 신규구독 frame 전달 수정 — WsSlotBudgetLedger.tryRegister 선제 admission 게이트(R2). 체결가·호가 router가 발사 직전 atomic 한도 확인+점유, 초과 시 KIS 발사 보류(graceful REST fallback). 멱등 재구독 통과·HOLDING evict LOCK 보존·ADR 0124 wire 불변·롤백 toggle. 첫 frame INFO 확정 로그 추가.
- [x] 테스트 — ledger admission 7 + 라이브 재현 2(한도포화 보류·회수후 재구독). :consumer:check·ktlintCheck GREEN.

## 의존성
- ADR 0123(WsSlotBudgetLedger R1) · ADR 0124(H0UNCNT0 단일채널) 정합 보존.

## 마지막 활동
2026-06-22 — 진단·수정·테스트 완료, PR 생성.

## 비고
근본 원인은 ADR 0123이 R1(회계만·차단 0)으로 남겨둔 "선제 admission(R2)" 미구현. 본 PR이 R2 admission 게이트로 silent overflow를 명시 보류로 전환. 라이브 배포는 orchestrator 마감 후.
