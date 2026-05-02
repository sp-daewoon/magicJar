package com.mj.consumer.infrastructure.persistence.calendar

import com.mj.domain.market.session.port.MarketCalendarRepository
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * mock profile용 in-memory MarketCalendarRepository. DB 없이 주말만 구분.
 * spec §8 component×profile 매트릭스, ADR 0032.
 */
@Component
@Profile("mock")
class InMemoryMarketCalendarAdapter : MarketCalendarRepository {
    override fun isBusinessDay(date: LocalDate): Boolean = date.dayOfWeek.value !in listOf(6, 7)

    override fun getHolidayReason(date: LocalDate): String? = if (date.dayOfWeek.value in listOf(6, 7)) "WEEKEND" else null

    override fun nextBusinessDay(after: LocalDate): LocalDate {
        var d = after.plusDays(1)
        while (!isBusinessDay(d)) d = d.plusDays(1)
        return d
    }

    override fun previousBusinessDay(before: LocalDate): LocalDate {
        var d = before.minusDays(1)
        while (!isBusinessDay(d)) d = d.minusDays(1)
        return d
    }
}
