package com.mj.api.infrastructure.indicator

import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.IndicatorDescriptor
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import com.mj.domain.strategy.indicator.catalog.IndicatorCatalog
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
import com.mj.domain.strategy.indicator.port.IndicatorSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * api 모듈 IndicatorEngine 설정 (ADR 0039).
 *
 * Gradle 의존 역방향 금지(consumer ↔ api)로 consumer 빈을 직접 주입할 수 없다.
 * domain의 NativeIndicatorCalculator를 사용해 api 자체 빈을 구성한다.
 */
@Configuration
class ApiIndicatorEngineConfig {
    @Bean
    fun nativeIndicatorSource(): IndicatorSource = NativeIndicatorCalculator()

    @Bean
    fun kisStrategyBuilderSource(nativeIndicatorSource: IndicatorSource): IndicatorSource =
        ApiKisStrategyBuilderSource(nativeIndicatorSource)

    @Bean
    fun indicatorEngine(
        nativeIndicatorSource: IndicatorSource,
        kisStrategyBuilderSource: IndicatorSource,
    ): IndicatorEngine = IndicatorEngine(listOf(nativeIndicatorSource, kisStrategyBuilderSource))
}

/** api 모듈 내부용 KIS Strategy Builder source */
private class ApiKisStrategyBuilderSource(
    private val delegate: IndicatorSource,
) : IndicatorSource {
    override val name = "kis-strategy-builder"
    override val priority = 100

    override fun availableIndicators(): List<IndicatorDescriptor> = IndicatorCatalog.ALL

    override fun supports(spec: IndicatorSpec): Boolean = IndicatorCatalog.BY_ID.containsKey(spec.name.lowercase())

    override fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val normalized = spec.copy(name = spec.name.lowercase())
        if (!supports(normalized)) {
            throw IndicatorComputeException("KisStrategyBuilderSource does not support '${spec.name}'")
        }
        return if (delegate.supports(normalized)) {
            delegate.calculate(normalized, candles)
        } else {
            throw IndicatorComputeException(
                "indicator '${spec.name}' is in catalog but not yet implemented (R2 예정).",
            )
        }
    }
}
