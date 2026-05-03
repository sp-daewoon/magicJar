package com.mj.api.application.signal

import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.signal.SignalEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * api 모듈의 Signal Engine 빈 구성 (트랙11 R1, ADR 0041).
 *
 * SignalEngine은 [com.mj.api.controller.SignalController]의 `/api/signals/test` 평가에서 사용.
 *
 * StrategyDslEvaluator는 [com.mj.api.infrastructure.dsl.StrategyDslConfig]에서 등록됨 — 의존 주입.
 */
@Configuration
class SignalEngineConfig {
    @Bean
    fun signalEngine(dslEvaluator: StrategyDslEvaluator): SignalEngine = SignalEngine(dslEvaluator)
}
