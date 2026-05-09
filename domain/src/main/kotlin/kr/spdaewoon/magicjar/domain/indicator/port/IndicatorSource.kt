package kr.spdaewoon.magicjar.domain.indicator.port

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 지표 계산 Source 플러그인 인터페이스 (ADR 0021 §2-1 LOCK).
 *
 * 1차 구현체:
 * - KisStrategyBuilderSource (priority=100, 80지표 전체)
 * - NativeIndicatorSource    (priority=50,  5종: SMA·EMA·RSI·MACD·BB)
 *
 * priority 숫자가 낮을수록 우선 선택 (Native=50 < KIS=100).
 * IndicatorEngine은 supports() == true인 source 중 minByOrNull(priority)로 단일 선택.
 */
interface IndicatorSource {
    /** source 식별자 ("kis-strategy-builder" · "native" · "ta-lib" · "krx") */
    val name: String

    /** 낮을수록 우선 선택 (ADR 0021 §2-3) */
    val priority: Int

    /** 해당 spec(지표 ID + params)을 계산할 수 있으면 true */
    fun supports(spec: IndicatorSpec): Boolean

    /**
     * 지표 계산.
     *
     * @throws IndicatorException.InsufficientCandlesException candles.size < spec 필요 최소 기간
     * @throws IndicatorException.CalculationFailureException  내부 계산 오류
     */
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult
}
