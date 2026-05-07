package kr.spdaewoon.magicjar.consumer.infrastructure.session

import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.datetime.Instant as KtInstant

/**
 * 시간 기반 MarketSessionResolver 1차 stub (ADR 0048 §8-2 정합).
 * - 토·일 → Closed
 * - 평일 09:00~15:30 KST → Regular
 * - 그 외 평일 → PreMarket 또는 PostMarket (1차 단순화: Regular와 동일 처리)
 *
 * 공휴일 캘린더는 T-MS1 진입 시 보강 예정.
 */
@Component
class SimpleMarketSessionResolver : MarketSessionResolver {
    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private const val OPEN_HOUR = 9
        private const val OPEN_MINUTE = 0
        private const val CLOSE_HOUR = 15
        private const val CLOSE_MINUTE = 30
    }

    override fun resolve(instant: KtInstant): MarketSession {
        val zdt = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(instant.epochSeconds, instant.nanosecondsOfSecond.toLong()),
            KST,
        )
        val dayOfWeek = zdt.dayOfWeek
        // 주말 → Closed
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return MarketSession.Closed(reason = "주말")
        }
        val hour = zdt.hour
        val minute = zdt.minute
        val totalMinutes = hour * 60 + minute
        val openMinutes = OPEN_HOUR * 60 + OPEN_MINUTE
        val closeMinutes = CLOSE_HOUR * 60 + CLOSE_MINUTE

        return when {
            totalMinutes in openMinutes until closeMinutes -> MarketSession.Regular
            totalMinutes < openMinutes -> MarketSession.PreMarket
            else -> MarketSession.PostMarket
        }
    }
}
