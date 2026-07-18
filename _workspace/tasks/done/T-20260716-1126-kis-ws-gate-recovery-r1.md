---
id: T-20260716-1126-kis-ws-gate-recovery-r1
agent: market-data
model: opus
branch: pr/kis-ws-gate-recovery
status: DONE
startedAt: 2026-07-16T11:26:46.092+09:00
completedAt: 2026-07-19T02:48:18.287+09:00
pr: #1179
parentPhase: KIS WS Gate Recovery Phase 1
---

# 목표
KIS WS Gate Recovery 1차: REAL/prod market-data 고정과 MOCK execution fail-closed 분리

## 결과물 체크리스트
- [x] ADR 0142 및 구현 spec/policy 정합
- [x] typed WS config와 endpoint/account/TR validator
- [x] MOCK에서 H0STCNI9 co-subscribe 차단 및 NOT_CONFIGURED 상태
- [x] 신규 BUY fail-closed와 SELL/cancel/reconcile 보존
- [x] targeted tests 및 전체 JVM/governance 검증
- [x] CHANGELOG 기록

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-07-16T11:26:46.103+09:00 — 작업 시작

## 비고
