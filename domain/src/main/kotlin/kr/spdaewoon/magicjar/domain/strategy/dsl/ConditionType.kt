package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 조건 유형 — KIS raw parser.py L23 ConditionType 1:1 port.
 *
 * Source: parser.py:L23 ConditionType enum
 * KIS raw name: ConditionType (rename 없음)
 */
enum class ConditionType {
    CROSSOVER,
    THRESHOLD,
    BREAKOUT,
    PATTERN,
    COMPOSITE,
}
