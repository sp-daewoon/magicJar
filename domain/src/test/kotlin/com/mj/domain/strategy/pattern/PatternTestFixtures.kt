package com.mj.domain.strategy.pattern

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * PatternRecognizer 단위 테스트용 합성 캔들 빌더.
 * 기준 시각은 2026-01-02T00:00:00Z (KRX 새해 첫 영업일 가정)부터 일봉 단위로 증가.
 */
internal object PatternTestFixtures {
    private val START: Instant = Instant.parse("2026-01-02T00:00:00Z")

    fun candle(
        index: Int,
        open: Number,
        high: Number,
        low: Number,
        close: Number,
        volume: Long = 100_000L,
        code: String = "TEST",
        interval: Interval = Interval.D1,
    ): Candle =
        Candle(
            code = code,
            interval = interval,
            openTime = START.plus(index.toLong(), ChronoUnit.DAYS),
            open = BigDecimal(open.toString()),
            high = BigDecimal(high.toString()),
            low = BigDecimal(low.toString()),
            close = BigDecimal(close.toString()),
            volume = volume,
            tradeCount = 0L,
        )

    /**
     * 직선 하락 추세 5봉 + 마지막 자리에 [endCandle] 배치 (총 6봉).
     * `endCandle`은 index 5에 위치, 추세 캔들은 index 0..4.
     */
    fun downtrendThen(endCandle: Candle): List<Candle> {
        val trend =
            (0..4).map { i ->
                val price = 1000 - i * 20
                candle(i, price + 5, price + 8, price - 2, price.toDouble())
            }
        return trend + endCandle.copy(openTime = START.plus(5L, ChronoUnit.DAYS))
    }

    fun uptrendThen(endCandle: Candle): List<Candle> {
        val trend =
            (0..4).map { i ->
                val price = 1000 + i * 20
                candle(i, price - 5, price + 8, price - 8, price.toDouble())
            }
        return trend + endCandle.copy(openTime = START.plus(5L, ChronoUnit.DAYS))
    }

    /** 평탄 5봉 (추세 없음, 약 1000원 박스) + 마지막 자리에 [endCandle] */
    fun flatThen(endCandle: Candle): List<Candle> {
        val trend =
            (0..4).map { i ->
                candle(i, 1000, 1005, 995, 1000)
            }
        return trend + endCandle.copy(openTime = START.plus(5L, ChronoUnit.DAYS))
    }
}
