package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class CandleQueryService(
    private val candleRepository: CandleRepository,
) {
    fun query(
        code: String,
        interval: Interval,
        from: Instant?,
        to: Instant?,
    ): List<Candle> {
        val toResolved = to ?: Instant.now()
        val fromResolved = from ?: defaultFrom(interval, toResolved)
        return candleRepository.find(code, interval, fromResolved, toResolved)
    }

    private fun defaultFrom(
        interval: Interval,
        to: Instant,
    ): Instant {
        val zone = ZoneId.of("Asia/Seoul")
        return when (interval) {
            Interval.D1 ->
                to
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(180)
                    .atStartOfDay(zone)
                    .toInstant()
            Interval.M1 -> to.minus(2, ChronoUnit.HOURS)
            Interval.M5 -> to.minus(12, ChronoUnit.HOURS)
            Interval.M15 -> to.minus(2, ChronoUnit.DAYS)
            Interval.M30 -> to.minus(5, ChronoUnit.DAYS)
            Interval.H1 -> to.minus(14, ChronoUnit.DAYS)
        }
    }

    companion object {
        // 'DAY' alias support — 프론트는 'DAY'로 보냄, 도메인은 D1
        fun parseInterval(raw: String): Interval =
            when (raw.uppercase()) {
                "DAY", "D", "D1", "1D" -> Interval.D1
                "M1", "1M" -> Interval.M1
                "M5", "5M" -> Interval.M5
                "M15", "15M" -> Interval.M15
                "M30", "30M" -> Interval.M30
                "H1", "1H" -> Interval.H1
                else -> Interval.valueOf(raw.uppercase())
            }
    }
}
