package com.mj.consumer.application.signal

import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.signal.SignalEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Signal Engine Spring 설정 (트랙11 R1, ADR 0041).
 *
 * IndicatorEngine은 [com.mj.consumer.infrastructure.indicator.IndicatorEngineConfig]에서
 * 이미 빈 등록됨 — 의존성 주입.
 */
@Configuration
class SignalEngineConfig {
    @Bean
    fun strategyDslEvaluator(indicatorEngine: IndicatorEngine): StrategyDslEvaluator = StrategyDslEvaluator(indicatorEngine)

    @Bean
    fun signalEngine(dslEvaluator: StrategyDslEvaluator): SignalEngine = SignalEngine(dslEvaluator)

    @Bean
    fun candleBuffer(): CandleBuffer = CandleBuffer()

    @Bean
    fun activeStrategyRegistry(): ActiveStrategyRegistry = ActiveStrategyRegistry()
}
