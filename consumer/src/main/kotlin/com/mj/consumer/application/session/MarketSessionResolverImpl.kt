package com.mj.consumer.application.session

import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketCalendarRepository
import com.mj.domain.market.session.port.MarketSessionResolver
import com.mj.domain.market.session.zoneKst
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * KST 시간 + MarketCalendarRepository 기반 MarketSession FSM 구현. spec §4.1, ADR 0032.
 *
 * KRX 시간대 (2026-05 기준):
 *   PRE_MARKET : 08:30 ~ 09:00 (동시호가 포함)
 *   OPEN       : 09:00 ~ 15:30 (정규장)
 *   POST_MARKET: 15:30 ~ 18:00 (시간외 단일가·장후)
 *   CLOSED     : 그 외 영업일 시간대
 *   HOLIDAY / CLOSED(Saturday/Sunday): 비영업일
 *
 * 1분 in-memory cache로 MarketCalendarRepository 호출 부하 감소.
 * profile=mock에서는 NoOpMarketSessionResolverImpl 사용 — 이 Bean 미등록.
 */
@Component
@Profile("dev | prd")
class MarketSessionResolverImpl(
    private val calendarRepository: MarketCalendarRepository,
    private val clock: Clock = Clock.systemUTC(),
) : MarketSessionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 1분 캐시 엔트리. */
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

        // 1. 캘린더 조회
        val isBizDay = calendarRepository.isBusinessDay(today)
        val holidayReason = calendarRepository.getHolidayReason(today)

        // 2. 상태 결정
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

                // 영업일 시간대 분류
                nowTime.isBefore(PRE_START) || nowTime.isAfter(POST_END) || nowTime == POST_END ->
                    MarketSessionState.Closed("off-hours")

                nowTime.isBefore(OPEN_START) ->
                    MarketSessionState.PreMarket

                nowTime.isBefore(POST_START) ->
                    MarketSessionState.Open

                else ->
                    MarketSessionState.PostMarket
            }

        // 3. lastBusinessDate
        val lastBizDate: LocalDate = if (isBizDay) today else calendarRepository.previousBusinessDay(today)

        // 4. nextOpenAt
        val nextOpenAt: OffsetDateTime? = computeNextOpenAt(state, today, nowTime, zone)

        log.debug("[SESSION] state={} today={} kstAt={}", state, today, nowZdt)
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
        nowTime: LocalTime,
        zone: java.time.ZoneId,
    ): OffsetDateTime? =
        when (state) {
            MarketSessionState.Open -> null // 현재 OPEN — 다음 개장 불필요

            MarketSessionState.PreMarket ->
                OffsetDateTime.of(today, OPEN_START, zone.rules.getOffset(today.atTime(OPEN_START)))

            else -> {
                // 다음 영업일 09:00
                val nextBiz =
                    if (state == MarketSessionState.PostMarket && nowTime.isBefore(POST_END)) {
                        calendarRepository.nextBusinessDay(today)
                    } else {
                        calendarRepository.nextBusinessDay(today)
                    }
                val openTime = OPEN_START
                OffsetDateTime.of(nextBiz, openTime, zone.rules.getOffset(nextBiz.atTime(openTime)))
            }
        }

    companion object {
        /** 1분 캐시 TTL (밀리초). */
        private const val CACHE_TTL_MS = 60_000L

        val PRE_START: LocalTime = LocalTime.of(8, 30)
        val OPEN_START: LocalTime = LocalTime.of(9, 0)
        val POST_START: LocalTime = LocalTime.of(15, 30)
        val POST_END: LocalTime = LocalTime.of(18, 0)
    }
}
