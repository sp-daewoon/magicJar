package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL AST 노드 sealed class root.
 *
 * 서브타입:
 * - [IndicatorRef] — 지표 참조 (KIS raw Indicator rename)
 * - [LiteralValue] — 리터럴 값 (KIS raw Value rename)
 * - [ArithmeticExpr] — 산술 표현식
 * - [Condition] — 단일 조건
 * - [CompositeCondition] — 복합 조건 (AND/OR)
 *
 * ADR 0022 §2-1, spec §6-2 LOCK.
 */
sealed class DslNode
