package com.mj.domain.strategy.port

import java.math.BigDecimal

/**
 * 전략 조건식 DSL 평가기. 구현체는 strategy 모듈이 제공한다.
 * `context`는 시점 스냅샷의 지표 이름 → 값 맵 (예: `"rsi14" to 30.0`, `"close" to 65000`).
 */
interface StrategyEvaluator {
    fun evaluate(
        expr: String,
        context: EvaluationContext,
    ): Boolean
}

data class EvaluationContext(
    val values: Map<String, BigDecimal>,
)
