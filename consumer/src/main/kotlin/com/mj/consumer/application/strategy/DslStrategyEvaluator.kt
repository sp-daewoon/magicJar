package com.mj.consumer.application.strategy

import com.mj.domain.strategy.dsl.BarStrategyEvaluator
import com.mj.domain.strategy.dsl.CompiledExpression
import org.springframework.stereotype.Component

/**
 * 전략 DSL 평가기. BarStrategyEvaluator를 컴파일 캐시와 함께 래핑한다.
 *
 * 백테스트 엔진은 직접 domain의 BarStrategyEvaluator를 사용한다.
 * 본 클래스는 실시간 신호 평가에서 식 캐시를 관리한다.
 */
@Component
class DslStrategyEvaluator {
    private val inner = BarStrategyEvaluator()
    private val cache = HashMap<String, CompiledExpression>()

    fun compile(expr: String): CompiledExpression =
        synchronized(cache) {
            cache.getOrPut(expr) { inner.compile(expr) }
        }
}
