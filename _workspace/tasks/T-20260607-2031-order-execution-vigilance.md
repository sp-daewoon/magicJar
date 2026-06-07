---
id: T-20260607-2031-order-execution-vigilance
agent: strategy
model: opus
branch: agent/orchestrator/order-execution-vigilance
status: RUNNING
startedAt: 2026-06-07T20:31:24.347+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
R1 EntryPricePolicy + RiskPlan 진입 설정

## 결과물 체크리스트
- [x] EntryPricePolicy 계산기 — strategy-engine/entry/EntryPricePolicy.kt (sealed Decision · 시장가 fallback 0 · KrxTickSize 호가단위) + 익절 LIMIT 헬퍼
- [x] RiskPlan 3종 — domain/strategy/EntryPolicy.kt (U-1 묶음 객체) + StrategyDefinition.entryPolicy add-only(null=현행) + ResolvedEntryPolicy default
- [x] 단위 테스트 — EntryPricePolicyTest(16) · KrxTickSizeTest(7) · EntryPolicyTest(14) · ./gradlew check GREEN · ktlint GREEN

## 의존성
- ADR 0114 §6-1 시그니처 초안(R1 확정) · ScaleInExitCalculator 선례(순수성·위치·테스트 패턴)
- R2가 본 계약(EntryPriceInput/EntryPriceDecision/EntryPolicy)을 배선(consumer BUY 경로·entry_policy JSONB·호가 source)

## U 의제 결정 (R1)
- **U-1 (묶음 vs 평면)**: 묶음 객체 `EntryPolicy` 채택 — ScaleInPlan 선례 정합·응집·확장성. R2가 entry_policy JSONB 컬럼 1개.
- **U-3 (stale 임계)**: 입력 주입(`maxQuoteAgeSec`) + default 보수 3초(`DEFAULT_MAX_QUOTE_AGE_SEC`). 미래 timestamp(시계 skew)는 stale 아님.
- **호가단위**: KrxTickSize 순수 함수 내장(2023-01-25 KRX 7단계). cap·익절 LIMIT 내림 정렬(자본 보호 — 올림 시 캡 자기 부정).
- **Flyway**: R1 신규 불요(domain+계산기만). R2 영속 wiring 시 entry_policy JSONB add-only 컬럼(next-available V72+ — R2 audit).

## 마지막 활동
2026-06-07T20:31:24.356+09:00 — 작업 시작
2026-06-07 — R1 구현 완료(EntryPricePolicy·KrxTickSize·EntryPolicy + 테스트 37건 GREEN + ./gradlew check GREEN)

## 비고
R2/R3 위임 유지: U-2(잔량 임계 — ThinLiquidity Skip 예약)·U-4(취소 워커)·U-5(익절 미체결)·U-6(전략 삭제 fallback)·U-7(1m 폴링 post-MVP)·U-8(HOLDING grace)·U-9(NXT sell-enabled).
