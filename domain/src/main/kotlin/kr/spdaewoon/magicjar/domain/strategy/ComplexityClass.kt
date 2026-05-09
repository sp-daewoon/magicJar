package kr.spdaewoon.magicjar.domain.strategy

/**
 * DSL 전략 복잡도 등급 — ADR 0047 §2-1-B 정합.
 *
 * - LOW: 빠른 평가 예상 (간단한 단일 지표 조건)
 * - MEDIUM: 보통 (복합 조건 또는 다중 지표)
 * - HIGH: 복잡 (많은 지표 호출 또는 깊은 AST)
 * - CRITICAL: 매우 복잡 (sandbox 한도 근접 — 사용자 경고 모달 표시)
 */
enum class ComplexityClass { LOW, MEDIUM, HIGH, CRITICAL }
