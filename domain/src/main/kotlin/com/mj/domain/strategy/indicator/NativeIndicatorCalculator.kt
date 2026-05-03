package com.mj.domain.strategy.indicator

import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.catalog.IndicatorCatalog
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
import com.mj.domain.strategy.indicator.port.IndicatorSource
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Kotlin 자체 구현 지표 계산기 — Spring 무의존 순수 도메인 객체 (ADR 0039).
 *
 * 지원 지표 (14개):
 *   ma, ema, rsi, macd, bb, vwap, stochastic_k, stochastic_d,
 *   atr, cci, obv, disparity, adx, volume_ma
 *
 * BigDecimal 기반 — 컨벤션 §4.1 준수.
 * Spring 컴포넌트 등록은 각 모듈의 @Component 래퍼 클래스에서 처리.
 */
class NativeIndicatorCalculator : IndicatorSource {
    override val name: String = "native"
    override val priority: Int = 50

    private val supportedIds =
        setOf(
            "ma",
            "ema",
            "rsi",
            "macd",
            "bb",
            "vwap",
            "stochastic_k",
            "stochastic_d",
            "atr",
            "cci",
            "obv",
            "disparity",
            "adx",
            "volume_ma",
        )

    private val mc = MathContext(12, RoundingMode.HALF_UP)

    override fun availableIndicators(): List<IndicatorDescriptor> = supportedIds.mapNotNull { IndicatorCatalog.BY_ID[it] }

    override fun supports(spec: IndicatorSpec): Boolean = spec.name.lowercase() in supportedIds

    override fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.isEmpty()) {
            return IndicatorResult(spec, emptyList(), name)
        }
        val points =
            when (spec.name.lowercase()) {
                "ma" -> computeMa(spec, candles)
                "ema" -> computeEma(spec, candles)
                "rsi" -> computeRsi(spec, candles)
                "macd" -> computeMacd(spec, candles)
                "bb" -> computeBb(spec, candles)
                "vwap" -> computeVwap(candles)
                "stochastic_k", "stochastic_d" -> computeStochastic(spec, candles)
                "atr" -> computeAtr(spec, candles)
                "cci" -> computeCci(spec, candles)
                "obv" -> computeObv(candles)
                "disparity" -> computeDisparity(spec, candles)
                "adx" -> computeAdx(spec, candles)
                "volume_ma" -> computeVolumeMa(spec, candles)
                else -> throw IndicatorComputeException("NativeIndicatorCalculator does not support '${spec.name}'")
            }
        return IndicatorResult(spec, points, name)
    }

    // -------------------------------------------------------------------------
    // MA (단순이동평균)
    // -------------------------------------------------------------------------
    private fun computeMa(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 20)
        return Sma.ofClose(candles, period).zipWithCandles(candles, "ma")
    }

    // -------------------------------------------------------------------------
    // EMA
    // -------------------------------------------------------------------------
    private fun computeEma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 20)
        return Ema.ofClose(candles, period).zipWithCandles(candles, "ema")
    }

    // -------------------------------------------------------------------------
    // RSI (Wilder)
    // -------------------------------------------------------------------------
    private fun computeRsi(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 14)
        return Rsi.ofClose(candles, period).zipWithCandles(candles, "rsi")
    }

    // -------------------------------------------------------------------------
    // MACD
    // -------------------------------------------------------------------------
    private fun computeMacd(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val fast = intParam(spec, "fast", 12)
        val slow = intParam(spec, "slow", 26)
        val signal = intParam(spec, "signal", 9)
        val result = Macd.ofClose(candles, fast, slow, signal)
        return candles.indices.mapNotNull { i ->
            val macd = result.macd[i]
            val sig = result.signal[i]
            val hist = result.histogram[i]
            if (macd == null && sig == null && hist == null) return@mapNotNull null
            IndicatorPoint(
                time = candles[i].openTime,
                values =
                    buildMap {
                        macd?.let { put("macd", it) }
                        sig?.let { put("signal", it) }
                        hist?.let { put("histogram", it) }
                    },
            )
        }
    }

    // -------------------------------------------------------------------------
    // BB (볼린저 밴드)
    // -------------------------------------------------------------------------
    private fun computeBb(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 20)
        val stdDev = doubleParam(spec, "std_dev", 2.0)
        val result = BollingerBands.ofClose(candles, period, BigDecimal(stdDev))
        return candles.indices.mapNotNull { i ->
            val mid = result.middle[i]
            val upper = result.upper[i]
            val lower = result.lower[i]
            if (mid == null && upper == null && lower == null) return@mapNotNull null
            IndicatorPoint(
                time = candles[i].openTime,
                values =
                    buildMap {
                        upper?.let { put("bb_upper", it) }
                        mid?.let { put("bb_middle", it) }
                        lower?.let { put("bb_lower", it) }
                    },
            )
        }
    }

    // -------------------------------------------------------------------------
    // VWAP
    // -------------------------------------------------------------------------
    private fun computeVwap(candles: List<Candle>): List<IndicatorPoint> {
        var cumTpv = BigDecimal.ZERO
        var cumVol = BigDecimal.ZERO
        val three = BigDecimal("3")
        return candles.map { c ->
            val tp =
                c.high
                    .add(c.low)
                    .add(c.close)
                    .divide(three, mc)
            val vol = BigDecimal(c.volume)
            cumTpv = cumTpv.add(tp.multiply(vol, mc))
            cumVol = cumVol.add(vol)
            val vwap = if (cumVol.signum() == 0) tp else cumTpv.divide(cumVol, mc)
            IndicatorPoint(time = c.openTime, values = mapOf("vwap" to vwap))
        }
    }

    // -------------------------------------------------------------------------
    // Stochastic %K / %D
    // -------------------------------------------------------------------------
    private fun computeStochastic(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val kPeriod = intParam(spec, "k_period", 14)
        val dPeriod = intParam(spec, "d_period", 3)
        val result = Stochastic.of(candles, kPeriod, dPeriod)
        return when (spec.name.lowercase()) {
            "stochastic_k" -> result.k.zipWithCandles(candles, "stochastic_k")
            "stochastic_d" -> result.d.zipWithCandles(candles, "stochastic_d")
            else -> result.k.zipWithCandles(candles, "stochastic_k")
        }
    }

    // -------------------------------------------------------------------------
    // ATR (Wilder)
    // -------------------------------------------------------------------------
    private fun computeAtr(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 14)
        return Atr.of(candles, period).zipWithCandles(candles, "atr")
    }

    // -------------------------------------------------------------------------
    // CCI
    // -------------------------------------------------------------------------
    private fun computeCci(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 20)
        return Cci.of(candles, period).zipWithCandles(candles, "cci")
    }

    // -------------------------------------------------------------------------
    // OBV
    // -------------------------------------------------------------------------
    private fun computeObv(candles: List<Candle>): List<IndicatorPoint> {
        var obv = BigDecimal.ZERO
        return candles.mapIndexed { i, c ->
            if (i > 0) {
                obv =
                    when {
                        c.close > candles[i - 1].close -> obv.add(BigDecimal(c.volume))
                        c.close < candles[i - 1].close -> obv.subtract(BigDecimal(c.volume))
                        else -> obv
                    }
            }
            IndicatorPoint(time = c.openTime, values = mapOf("obv" to obv))
        }
    }

    // -------------------------------------------------------------------------
    // Disparity
    // -------------------------------------------------------------------------
    private fun computeDisparity(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 20)
        return Disparity.of(candles, period).zipWithCandles(candles, "disparity")
    }

    // -------------------------------------------------------------------------
    // ADX
    // -------------------------------------------------------------------------
    private fun computeAdx(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 14)
        return Adx.of(candles, period).zipWithCandles(candles, "adx")
    }

    // -------------------------------------------------------------------------
    // Volume MA
    // -------------------------------------------------------------------------
    private fun computeVolumeMa(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): List<IndicatorPoint> {
        val period = intParam(spec, "period", 20)
        return VolumeOsc.avgVolume(candles, period).zipWithCandles(candles, "volume_ma")
    }

    // -------------------------------------------------------------------------
    // 내부 유틸
    // -------------------------------------------------------------------------

    private fun intParam(
        spec: IndicatorSpec,
        key: String,
        default: Int,
    ): Int =
        when (val v = spec.params[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            null -> default
            else -> default
        }

    private fun doubleParam(
        spec: IndicatorSpec,
        key: String,
        default: Double,
    ): Double =
        when (val v = spec.params[key]) {
            is Double -> v
            is Float -> v.toDouble()
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            null -> default
            else -> default
        }

    private fun List<BigDecimal?>.zipWithCandles(
        candles: List<Candle>,
        key: String,
    ): List<IndicatorPoint> =
        this.mapIndexedNotNull { i, v ->
            v ?: return@mapIndexedNotNull null
            IndicatorPoint(time = candles[i].openTime, values = mapOf(key to v))
        }
}
