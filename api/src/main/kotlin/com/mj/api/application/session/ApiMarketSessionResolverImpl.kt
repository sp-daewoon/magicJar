package com.mj.api.application.session

import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketCalendarRepository
import com.mj.domain.market.session.port.MarketSessionResolver
import com.mj.domain.market.session.zoneKst
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * api 모듈 전용 MarketSessionResolver 구현체. consumer MarketSessionResolverImpl과 동일 로직.
 * ADR 0032, spec §4.1.
 *
 * api 모듈에서 QuoteQueryService에 장 상태를 제공한다.
 * 1분 in-memory cache로 MarketCalendarRepository 호출 최소화.
 */
@Component
class ApiMarketSessionResolverImpl(
    private val calendarRepository: MarketCalendarRepository,
    private val clock: Clock = Clock.systemUTC(),
) : MarketSessionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CacheEntry(
        val session: MarketSession,
        val cachedAtMillis: Long,
    )

    private val cache = AtomicReference<CacheEntry?>(null)

    override fun resolve(): MarketSession {
        val nowMillis = clock.millis()
        val entry = cache.get()
        if (entry != null && nowMillis - entry.cachedAtMillis < CACHE_TTL_MS) {
            return entry.session
        }
        val session = buildSession()
        cache.set(CacheEntry(session, nowMillis))
        return session
    }

    private fun buildSession(): MarketSession {
        val zone = zoneKst()
        val nowZdt = OffsetDateTime.now(clock.withZone(zone))
        val today: LocalDate = nowZdt.toLocalDate()
        val nowTime: LocalTime = nowZdt.toLocalTime()

        val isBizDay = calendarRepository.isBusinessDay(today)
        val holidayReason = calendarRepository.getHolidayReason(today)

        val state: MarketSessionState =
            when {
                !isBizDay && holidayReason != null && holidayReason != "WEEKEND" ->
                    MarketSessionState.Holiday(
                        name = calendarRepository.getHolidayReason(today) ?: "휴장",
                        reason = holidayReason,
                    )

                !isBizDay -> {
                    val reason = if (today.dayOfWeek.value == 6) "Saturday" else "Sunday"
                    MarketSessionState.Closed(reason)
                }

                nowTime.isBefore(PRE_START) || nowTime.isAfter(POST_END) || nowTime == POST_END ->
                    MarketSessionState.Closed("off-hours")

                nowTime.isBefore(OPEN_START) ->
                    MarketSessionState.PreMarket

                nowTime.isBefore(POST_START) ->
                    MarketSessionState.Open

                else ->
                    MarketSessionState.PostMarket
            }

        val lastBizDate: LocalDate = if (isBizDay) today else calendarRepository.previousBusinessDay(today)
        val nextOpenAt: OffsetDateTime? = computeNextOpenAt(state, today, zone)

        log.debug("[API_SESSION] state={} today={}", state, today)
        return MarketSession(
            state = state,
            kstAt = nowZdt,
            lastBusinessDate = lastBizDate,
            nextOpenAt = nextOpenAt,
        )
    }

    private fun computeNextOpenAt(
        state: MarketSessionState,
        today: LocalDate,
        zone: java.time.ZoneId,
    ): OffsetDateTime? =
        when (state) {
            MarketSessionState.Open -> null
            MarketSessionState.PreMarket ->
                OffsetDateTime.of(today, OPEN_START, zone.rules.getOffset(today.atTime(OPEN_START)))
            else -> {
                val nextBiz = calendarRepository.nextBusinessDay(today)
                OffsetDateTime.of(nextBiz, OPEN_START, zone.rules.getOffset(nextBiz.atTime(OPEN_START)))
            }
        }

    companion object {
        private const val CACHE_TTL_MS = 60_000L
        val PRE_START: LocalTime = LocalTime.of(8, 30)
        val OPEN_START: LocalTime = LocalTime.of(9, 0)
        val POST_START: LocalTime = LocalTime.of(15, 30)
        val POST_END: LocalTime = LocalTime.of(18, 0)
    }
}
