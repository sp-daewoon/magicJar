package kr.spdaewoon.magicjar.indicator.impl.smartmoney

import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.BigDecimal
import java.math.RoundingMode

internal object SmartMoneyIndicators {
    private val ZERO = BigDecimal.ZERO
    private val ONE = BigDecimal.ONE
    private val HUNDRED = BigDecimal("100")

    private enum class Side {
        BULLISH,
        BEARISH,
    }

    private data class Zone(
        val side: Side,
        val top: BigDecimal,
        val bottom: BigDecimal,
        val originIndex: Int,
    ) {
        val mid: BigDecimal = top.add(bottom).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
    }

    fun fvg(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val mitigationPct = pctParam(spec, "mitigationPct", BigDecimal("50"))
        var bullish: Zone? = null
        var bearish: Zone? = null
        val series = mutableListOf<IndicatorPoint>()

        for (i in candles.indices) {
            val current = candles[i]
            bullish = bullish?.takeUnless { i > it.originIndex && isMitigated(it, current, mitigationPct) }
            bearish = bearish?.takeUnless { i > it.originIndex && isMitigated(it, current, mitigationPct) }

            if (i >= 2) {
                val left = candles[i - 2]
                if (current.lowRaw > left.highRaw) {
                    bullish =
                        Zone(
                            side = Side.BULLISH,
                            top = current.lowRaw,
                            bottom = left.highRaw,
                            originIndex = i,
                        )
                }
                if (current.highRaw < left.lowRaw) {
                    bearish =
                        Zone(
                            side = Side.BEARISH,
                            top = left.lowRaw,
                            bottom = current.highRaw,
                            originIndex = i,
                        )
                }
            }

            series.add(
                IndicatorPoint(
                    time = current.ts,
                    values = fvgValues(bullish, bearish, current),
                ),
            )
        }

        return IndicatorResult(spec, series, "smart-money")
    }

    fun orderBlock(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val swingLookback = spec.intParam("swingLookback", 20).coerceAtLeast(2)
        val searchLookback = spec.intParam("searchLookback", 20).coerceAtLeast(1)
        val mitigationPct = pctParam(spec, "mitigationPct", BigDecimal("50"))
        var bullish: Zone? = null
        var bearish: Zone? = null
        val series = mutableListOf<IndicatorPoint>()

        for (i in candles.indices) {
            val current = candles[i]
            bullish = bullish?.takeUnless { i > it.originIndex && (isInvalidated(it, current) || isMitigated(it, current, mitigationPct)) }
            bearish = bearish?.takeUnless { i > it.originIndex && (isInvalidated(it, current) || isMitigated(it, current, mitigationPct)) }

            var bosDirection = ZERO
            if (i >= swingLookback) {
                val swingWindow = candles.subList(i - swingLookback, i)
                val swingHigh = swingWindow.maxOf { it.highRaw }
                val swingLow = swingWindow.minOf { it.lowRaw }
                val searchStart = (i - searchLookback).coerceAtLeast(0)
                val searchWindow = candles.subList(searchStart, i)

                if (current.closeRaw > swingHigh) {
                    val ob = searchWindow.asReversed().firstOrNull { it.closeRaw < it.openRaw }
                    if (ob != null) {
                        bullish = Zone(Side.BULLISH, top = ob.highRaw, bottom = ob.lowRaw, originIndex = i)
                        bosDirection = ONE
                    }
                } else if (current.closeRaw < swingLow) {
                    val ob = searchWindow.asReversed().firstOrNull { it.closeRaw > it.openRaw }
                    if (ob != null) {
                        bearish = Zone(Side.BEARISH, top = ob.highRaw, bottom = ob.lowRaw, originIndex = i)
                        bosDirection = BigDecimal("-1")
                    }
                }
            }

            series.add(
                IndicatorPoint(
                    time = current.ts,
                    values = orderBlockValues(bullish, bearish, current, bosDirection),
                ),
            )
        }

        return IndicatorResult(spec, series, "smart-money")
    }

    private fun fvgValues(
        bullish: Zone?,
        bearish: Zone?,
        current: Candle,
    ): Map<String, BigDecimal> =
        mapOf(
            "fvg" to bool(bullish != null || bearish != null),
            "bullish_active" to bool(bullish != null),
            "bearish_active" to bool(bearish != null),
            "bullish_top" to (bullish?.top ?: ZERO),
            "bullish_bottom" to (bullish?.bottom ?: ZERO),
            "bullish_mid" to (bullish?.mid ?: ZERO),
            "bullish_fill_pct" to (bullish?.let { fillPct(it, current) } ?: ZERO),
            "bearish_top" to (bearish?.top ?: ZERO),
            "bearish_bottom" to (bearish?.bottom ?: ZERO),
            "bearish_mid" to (bearish?.mid ?: ZERO),
            "bearish_fill_pct" to (bearish?.let { fillPct(it, current) } ?: ZERO),
        )

    private fun orderBlockValues(
        bullish: Zone?,
        bearish: Zone?,
        current: Candle,
        bosDirection: BigDecimal,
    ): Map<String, BigDecimal> =
        mapOf(
            "order_block" to bool(bullish != null || bearish != null),
            "bullish_active" to bool(bullish != null),
            "bearish_active" to bool(bearish != null),
            "bullish_top" to (bullish?.top ?: ZERO),
            "bullish_bottom" to (bullish?.bottom ?: ZERO),
            "bullish_mid" to (bullish?.mid ?: ZERO),
            "bullish_fill_pct" to (bullish?.let { fillPct(it, current) } ?: ZERO),
            "bearish_top" to (bearish?.top ?: ZERO),
            "bearish_bottom" to (bearish?.bottom ?: ZERO),
            "bearish_mid" to (bearish?.mid ?: ZERO),
            "bearish_fill_pct" to (bearish?.let { fillPct(it, current) } ?: ZERO),
            "bos_direction" to bosDirection,
        )

    private fun isMitigated(
        zone: Zone,
        candle: Candle,
        mitigationPct: BigDecimal,
    ): Boolean =
        when (zone.side) {
            Side.BULLISH -> candle.lowRaw <= mitigationPrice(zone, mitigationPct)
            Side.BEARISH -> candle.highRaw >= mitigationPrice(zone, mitigationPct)
        }

    private fun isInvalidated(
        zone: Zone,
        candle: Candle,
    ): Boolean =
        when (zone.side) {
            Side.BULLISH -> candle.closeRaw < zone.bottom
            Side.BEARISH -> candle.closeRaw > zone.top
        }

    private fun mitigationPrice(
        zone: Zone,
        mitigationPct: BigDecimal,
    ): BigDecimal {
        val range = zone.top.subtract(zone.bottom)
        val amount = range.multiply(mitigationPct).divide(HUNDRED, 8, RoundingMode.HALF_UP)
        return when (zone.side) {
            Side.BULLISH -> zone.top.subtract(amount)
            Side.BEARISH -> zone.bottom.add(amount)
        }
    }

    private fun fillPct(
        zone: Zone,
        candle: Candle,
    ): BigDecimal {
        val range = zone.top.subtract(zone.bottom)
        if (range.compareTo(ZERO) <= 0) return ZERO
        val filled =
            when (zone.side) {
                Side.BULLISH -> zone.top.subtract(candle.lowRaw).coerceAtLeastZero()
                Side.BEARISH -> candle.highRaw.subtract(zone.bottom).coerceAtLeastZero()
            }
        return filled
            .multiply(HUNDRED)
            .divide(range, 4, RoundingMode.HALF_UP)
            .coerceInZeroToHundred()
    }

    private fun pctParam(
        spec: IndicatorSpec,
        key: String,
        default: BigDecimal,
    ): BigDecimal = spec.bdParam(key, default).coerceInZeroToHundred()

    private fun bool(value: Boolean): BigDecimal = if (value) ONE else ZERO

    private fun BigDecimal.coerceAtLeastZero(): BigDecimal = if (this < ZERO) ZERO else this

    private fun BigDecimal.coerceInZeroToHundred(): BigDecimal =
        when {
            this < ZERO -> ZERO
            this > HUNDRED -> HUNDRED
            else -> this
        }
}
