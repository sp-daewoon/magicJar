package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 auto-disable 사유 — ADR 0047 §2-2-D 정합.
 *
 * - NULL: 비활성 아님 (정상 상태)
 * - TIMEOUT: 평가 timeout 1회 발생 (100ms 초과)
 * - CPU_BUDGET_EXCEEDED: CPU budget 초과 (50ms 초과)
 * - MANUAL: 사용자 수동 비활성화
 *
 * DB 컬럼 strategy.disabled_reason — Flyway V26 추가.
 */
enum class StrategyDisabledReason { NULL, TIMEOUT, CPU_BUDGET_EXCEEDED, MANUAL }
