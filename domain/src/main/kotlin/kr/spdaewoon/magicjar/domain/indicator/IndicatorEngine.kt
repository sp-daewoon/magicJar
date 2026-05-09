package kr.spdaewoon.magicjar.domain.indicator

import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 지표 라우팅 엔진 (ADR 0021 §2-2).
 *
 * 등록된 [IndicatorSource] 중 spec.name을 supports 하는 source 중
 * priority 가장 낮은(= 우선) 단일 source를 선택하여 calculate() 위임.
 *
 * 다중 모듈(api·consumer)에 동일 bean 등록 — sources 는 stateless라 multi-instance 안전.
 */
class IndicatorEngine(
    private val sources: List<IndicatorSource>,
) {
    /**
     * 지표 계산.
     *
     * @throws IndicatorException.UnsupportedSpecException spec.name 지원 source 없을 때
     */
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val source =
            sources
                .filter { it.supports(spec) }
                .minByOrNull { it.priority }
                ?: throw IndicatorException.UnsupportedSpecException(spec.name)
        return source.calculate(spec, candles)
    }

    /** 전체 등록 source 이름 목록 */
    fun registeredSources(): List<String> = sources.map { it.name }
}
