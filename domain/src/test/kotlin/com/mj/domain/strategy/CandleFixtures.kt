package com.mj.domain.strategy

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.time.Instant

/** 테스트 픽스처: classpath의 CSV에서 일봉 캔들을 로드한다. */
object CandleFixtures {
    fun loadSampleSamsung(): List<Candle> = loadFromClasspath("/candles/005930-daily-sample.csv", "005930")

    fun loadFromClasspath(
        path: String,
        code: String,
    ): List<Candle> {
        val stream =
            CandleFixtures::class.java.getResourceAsStream(path)
                ?: error("fixture not found: $path")
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { line ->
                    val cols = line.split(",")
                    Candle(
                        code = code,
                        interval = Interval.D1,
                        openTime = Instant.parse(cols[0]),
                        open = BigDecimal(cols[1]),
                        high = BigDecimal(cols[2]),
                        low = BigDecimal(cols[3]),
                        close = BigDecimal(cols[4]),
                        volume = cols[5].toLong(),
                        tradeCount = 0L,
                    )
                }.toList()
        }
    }
}
