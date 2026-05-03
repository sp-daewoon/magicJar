package com.mj.domain.strategy.indicator

import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
import com.mj.domain.strategy.indicator.port.IndicatorSource

/**
 * 등록된 IndicatorSource 중 spec을 지원하는 source를 우선순위로 라우팅한다 (ADR 0039 §2-2).
 *
 * - priority 낮을수록 우선 선택 (native=50 < kis=100 < ta-lib=200)
 * - 지원 source 없으면 IndicatorComputeException 발생
 */
class IndicatorEngine(
    private val sources: List<IndicatorSource>,
) {
    init {
        require(sources.isNotEmpty()) { "IndicatorEngine requires at least one source" }
    }

    /**
     * spec에 맞는 최우선 source로 계산한다.
     *
     * @throws IndicatorComputeException 지원 source 없거나 계산 실패 시
     */
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val source =
            sources
                .filter { it.supports(spec) }
                .minByOrNull { it.priority }
                ?: throw IndicatorComputeException(
                    "No source supports indicator '${spec.name}'. " +
                        "Available: ${sources.flatMap { it.availableIndicators() }.map { it.id }.distinct().sorted()}",
                )
        return source.calculate(spec, candles)
    }

    /** 등록된 모든 source의 지표 descriptor 합집합 (id 중복 시 priority 낮은 source의 것이 우선) */
    fun allDescriptors(): List<IndicatorDescriptor> {
        val sortedSources = sources.sortedBy { it.priority }
        val seen = mutableSetOf<String>()
        return sortedSources.flatMap { source ->
            source.availableIndicators().filter { seen.add(it.id) }
        }
    }

    /** spec을 지원하는 source 목록 (우선순위 순) */
    fun supportingSources(spec: IndicatorSpec): List<IndicatorSource> = sources.filter { it.supports(spec) }.sortedBy { it.priority }
}
