package com.mj.consumer.infrastructure.persistence.calendar

import com.mj.domain.market.session.port.MarketCalendarRepository
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * consumer 모듈 MarketCalendarRepository JPA 구현체. spec §3.2, ADR 0032.
 * dev/prd profile에서 활성 — mock은 InMemoryMarketCalendarAdapter 사용.
 */
@Component
@Profile("dev | prd")
class MarketCalendarJpaAdapter(
    private val repo: MarketCalendarJpaRepository,
) : MarketCalendarRepository {
    override fun isBusinessDay(date: LocalDate): Boolean = repo.findById(date).map { it.isBusinessDay }.orElse(fallbackIsBusinessDay(date))

    override fun getHolidayReason(date: LocalDate): String? {
        val entity = repo.findById(date).orElse(null) ?: return fallbackHolidayReason(date)
        return if (!entity.isBusinessDay) entity.holidayReason else null
    }

    override fun nextBusinessDay(after: LocalDate): LocalDate =
        repo.findNextBusinessDay(after)?.calendarDate
            ?: error("[MarketCalendar] nextBusinessDay: 시드 범위 초과 after=$after — V10 시드 갱신 필요")

    override fun previousBusinessDay(before: LocalDate): LocalDate =
        repo.findPreviousBusinessDay(before)?.calendarDate
            ?: error("[MarketCalendar] previousBusinessDay: 시드 범위 초과 before=$before — V10 시드 갱신 필요")

    private fun fallbackIsBusinessDay(date: LocalDate): Boolean = date.dayOfWeek.value !in listOf(6, 7)

    private fun fallbackHolidayReason(date: LocalDate): String? = if (date.dayOfWeek.value in listOf(6, 7)) "WEEKEND" else null
}
