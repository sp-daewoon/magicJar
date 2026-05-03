package com.mj.consumer.infrastructure.indicator

import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.IndicatorDescriptor
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.indicator.catalog.IndicatorCatalog
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
import com.mj.domain.strategy.indicator.port.IndicatorSource
import org.springframework.stereotype.Component

/**
 * KIS Strategy Builder 80지표 소스 — consumer 모듈 Spring 컴포넌트 (ADR 0039, priority=100).
 *
 * R1: descriptor 카탈로그(80개 메타) 노출 + compute는 NativeIndicatorSource 위임.
 * R2(후속): KIS Python 호출 또는 알고리즘 직접 port.
 */
@Component
class KisStrategyBuilderSource(
    private val nativeSource: NativeIndicatorSource,
) : IndicatorSource {
    override val name: String = "kis-strategy-builder"
    override val priority: Int = 100

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
        return if (nativeSource.supports(normalized)) {
            nativeSource.calculate(normalized, candles)
        } else {
            throw IndicatorComputeException(
                "KisStrategyBuilderSource: indicator '${spec.name}' is registered in catalog " +
                    "but not yet implemented. Scheduled for R2.",
            )
        }
    }
}
