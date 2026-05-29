package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 상태 4단 enum (ADR 0066 §2-6 F-1 채택 + §5-1 정합).
 *
 * 사용자 결정 (2026-05-12): F-1 status enum 4단.
 *
 * ### 4가지 의도
 * - [ACTIVE] — 평가 path 진입 (default · 등록 직후 default)
 * - [PAUSED] — 사용자 토글 — 평가 skip, 즉시 재활성화 가능
 * - [DISABLED] — sandbox auto-disable (ADR 0047 §2-2-D TIMEOUT·CPU_BUDGET_EXCEEDED), 사용자 명시 `[다시 활성화]` 의무
 * - [DELETED] — soft delete — list 조회 제외 (audit 보존)
 *
 * ### transition 매트릭스 (ADR 0066 §2-6 F-1)
 * - 사용자 액션: ACTIVE ↔ PAUSED (토글) · ANY → DELETED · DISABLED → ACTIVE (사용자 명시 reactivate)
 * - 시스템 액션: ACTIVE → DISABLED (sandbox auto, ADR 0047 §2-2-D)
 *
 * ### 기존 자산 정합
 * 기존 [StrategyDisabledReason] (NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL·RISK_VIOLATION)와 별도 axis.
 * status=DISABLED 일 때 disabledReason ∈ {TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL·RISK_VIOLATION} 매트릭스.
 * status=ACTIVE 일 때 disabledReason=NULL 정합.
 *
 * spec 인용: ADR 0066 §2-6 F-1 + §5-1.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
enum class StrategyStatus {
    ACTIVE,
    PAUSED,
    DISABLED,
    DELETED,
    ;

    /** 평가 path 진입 가능 여부 (ACTIVE만 평가 진입). */
    fun isEvaluable(): Boolean = this == ACTIVE
}
