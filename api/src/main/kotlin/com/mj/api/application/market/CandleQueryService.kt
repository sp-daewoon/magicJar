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
            // V8 시드(어제 영업일 09~14시)와 휴일 갭을 고려해 lookback을 충분히 길게.
            // 클라이언트는 fitContent로 자동 zoom — 데이터가 있는 만큼 보여주면 됨.
            Interval.M1 -> to.minus(2, ChronoUnit.DAYS)
            Interval.M5 -> to.minus(7, ChronoUnit.DAYS)
            Interval.M15 -> to.minus(14, ChronoUnit.DAYS)
            Interval.M30 -> to.minus(14, ChronoUnit.DAYS)
            Interval.H1 -> to.minus(30, ChronoUnit.DAYS)
        }
    }

    /**
     * infinite scroll: before 이전 캔들을 최대 limit개 ASC 반환 (T19-T4).
     *
     * @param before  기준 시각 (exclusive)
     * @param limit   최대 건수 (1~1000, 컨트롤러에서 사전 검증)
     */
    fun queryBefore(
        code: String,
        interval: Interval,
        before: Instant,
        limit: Int,
    ): List<Candle> = candleRepository.findBefore(code, interval, before, limit)

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
