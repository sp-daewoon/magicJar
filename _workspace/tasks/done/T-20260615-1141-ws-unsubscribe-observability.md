---
id: T-20260615-1141-ws-unsubscribe-observability
agent: market-data
model: opus
branch: agent/market-data/ws-unsubscribe-observability
status: DONE
startedAt: 2026-06-15T11:41:02.978+09:00
completedAt: 2026-07-16T23:03:17.135+09:00
pr: #978
parentPhase: ?
---

# 목표
호가(H0UNASP0) unsubscribe invalid tr_type 진단 관측 보강 (결함③)

## 결과물 체크리스트
- [x] KisWebSocketBridge unsubscribe 전송 원문(JSON) 로깅 (sink emit 직전 · emitUnsubscribeMessage 헬퍼)
- [x] invalid tr_type 응답 수신 시 어떤 code/trId 해제였는지 상관관계 로깅 (dumpRecentUnsubscribesForDiagnosis)

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T11:41:02.989+09:00 — 작업 시작
2026-06-15 — 관측 보강 구현 완료(전송 원문 로깅 + OPSP8997 상관관계 덤프), :consumer:test ws GREEN + ktlint GREEN

## 비고
부작용 0 (로깅·관측 전용). trType 값은 KIS 공식 정합 확인됨 — 절대 "2"로 변경 금지(회귀).
이번 사이클은 진단 가능하게만. 원인이 전송 race로 확정되면 별도 fix.
