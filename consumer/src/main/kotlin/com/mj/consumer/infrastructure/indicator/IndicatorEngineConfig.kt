package com.mj.consumer.infrastructure.indicator

import com.mj.domain.strategy.indicator.IndicatorEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * IndicatorEngine Spring 설정 (ADR 0039 §2-2).
 *
 * 등록 순서:
 *  - NativeIndicatorSource (priority=50)
 *  - KisStrategyBuilderSource (priority=100)
 *
 * 향후 TaLibSource(200), KrxSource(300) 빈 추가 시 자동 편입.
 * IndicatorEngine 내부에서 priority 오름차순으로 정렬 후 첫 번째 지원 source 선택.
 */
@Configuration
class IndicatorEngineConfig {
    @Bean
    fun indicatorEngine(
        nativeSource: NativeIndicatorSource,
        kisSource: KisStrategyBuilderSource,
    ): IndicatorEngine = IndicatorEngine(listOf(nativeSource, kisSource))
}
