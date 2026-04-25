package kr.spdaewoon.magicjar.strategy.dsl

import kr.spdaewoon.magicjar.core.domain.Candle
import kr.spdaewoon.magicjar.strategy.indicator.Adx
import kr.spdaewoon.magicjar.strategy.indicator.Atr
import kr.spdaewoon.magicjar.strategy.indicator.BollingerBands
import kr.spdaewoon.magicjar.strategy.indicator.Cci
import kr.spdaewoon.magicjar.strategy.indicator.Disparity
import kr.spdaewoon.magicjar.strategy.indicator.Ema
import kr.spdaewoon.magicjar.strategy.indicator.Macd
import kr.spdaewoon.magicjar.strategy.indicator.Rsi
import kr.spdaewoon.magicjar.strategy.indicator.Sma
import kr.spdaewoon.magicjar.strategy.indicator.Stochastic
import kr.spdaewoon.magicjar.strategy.indicator.VolumeOsc
import java.math.BigDecimal

/**
 * DSL이 호출 가능한 지표 함수 카탈로그.
 *
 * 키는 함수명 대문자. 시리즈 한 개를 반환하는 컴퓨터(연산기)를 보관.
 * 백테스트 등에서 같은 식을 여러 번 평가할 때를 대비해, 호출 측에서 캐시한다.
 */
internal object IndicatorRegistry {
    /** 지표 함수: 캔들 + 정수 인자들 → 시리즈(`List<BigDecimal?>`). */
    fun compute(
        name: String,
        args: List<Int>,
        candles: List<Candle>,
    ): List<BigDecimal?> =
        when (name) {
            "SMA" -> Sma.ofClose(candles, requireOneArg(name, args))
            "EMA" -> Ema.ofClose(candles, requireOneArg(name, args))
            "RSI" -> Rsi.ofClose(candles, argOrDefault(args, 0, 14))
            "ATR" -> Atr.of(candles, argOrDefault(args, 0, 14))
            "ADX" -> Adx.of(candles, argOrDefault(args, 0, 14))
            "CCI" -> Cci.of(candles, argOrDefault(args, 0, 20))
            "DISPARITY" -> Disparity.of(candles, argOrDefault(args, 0, 20))
            "AVG_VOLUME" -> VolumeOsc.avgVolume(candles, argOrDefault(args, 0, 20))
            "VOLUME_OSC" -> VolumeOsc.of(candles, argOrDefault(args, 0, 12), argOrDefault(args, 1, 26))
            "MACD" -> Macd.of(candles.map { it.close }, argOrDefault(args, 0, 12), argOrDefault(args, 1, 26), argOrDefault(args, 2, 9)).macd
            "MACD_SIGNAL" ->
                Macd
                    .of(
                        candles.map { it.close },
                        argOrDefault(args, 0, 12),
                        argOrDefault(args, 1, 26),
                        argOrDefault(args, 2, 9),
                    ).signal
            "MACD_HIST" ->
                Macd
                    .of(
                        candles.map { it.close },
                        argOrDefault(args, 0, 12),
                        argOrDefault(args, 1, 26),
                        argOrDefault(args, 2, 9),
                    ).histogram
            "BB_UPPER" -> BollingerBands.ofClose(candles, argOrDefault(args, 0, 20), kArg(args, 1)).upper
            "BB_LOWER" -> BollingerBands.ofClose(candles, argOrDefault(args, 0, 20), kArg(args, 1)).lower
            "BB_MIDDLE" -> BollingerBands.ofClose(candles, argOrDefault(args, 0, 20), kArg(args, 1)).middle
            "STOCH_K" -> Stochastic.of(candles, argOrDefault(args, 0, 14), argOrDefault(args, 1, 3)).k
            "STOCH_D" -> Stochastic.of(candles, argOrDefault(args, 0, 14), argOrDefault(args, 1, 3)).d
            else -> throw DslEvaluationException("unknown function: $name")
        }

    fun isKnown(name: String): Boolean =
        name in
            setOf(
                "SMA",
                "EMA",
                "RSI",
                "ATR",
                "ADX",
                "CCI",
                "DISPARITY",
                "AVG_VOLUME",
                "VOLUME_OSC",
                "MACD",
                "MACD_SIGNAL",
                "MACD_HIST",
                "BB_UPPER",
                "BB_LOWER",
                "BB_MIDDLE",
                "STOCH_K",
                "STOCH_D",
            )

    private fun requireOneArg(
        name: String,
        args: List<Int>,
    ): Int {
        if (args.size != 1) {
            throw DslEvaluationException("$name expects exactly 1 integer arg, got ${args.size}")
        }
        return args[0]
    }

    private fun argOrDefault(
        args: List<Int>,
        idx: Int,
        default: Int,
    ): Int = args.getOrNull(idx) ?: default

    private fun kArg(
        args: List<Int>,
        idx: Int,
    ): BigDecimal {
        // BB의 k는 정수 시그마로 단순화. 기본 2.
        return BigDecimal(args.getOrNull(idx) ?: 2)
    }
}
