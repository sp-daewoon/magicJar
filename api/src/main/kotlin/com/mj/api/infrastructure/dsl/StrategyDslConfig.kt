package com.mj.api.infrastructure.dsl

import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.indicator.IndicatorEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Strategy DSL Spring 빈 구성 (ADR 0040).
 *
 * - Parser·Presets는 자체 @Component
 * - Evaluator는 IndicatorEngine 의존 (ADR 0039 IndicatorSource pluggable engine)
 */
@Configuration
class StrategyDslConfig {
    @Bean
    fun strategyDslEvaluator(indicatorEngine: IndicatorEngine): StrategyDslEvaluator = StrategyDslEvaluator(indicatorEngine)
}
