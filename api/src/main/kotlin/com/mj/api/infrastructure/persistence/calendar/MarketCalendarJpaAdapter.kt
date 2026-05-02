package com.mj.api.infrastructure.persistence.calendar

import com.mj.domain.market.session.port.MarketCalendarRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * MarketCalendarRepository 포트의 JPA 구현체. api 모듈. spec §3.2, ADR 0032.
 *
 * V10 Flyway 시드(2026~2027)를 기반으로 영업일·휴장일 조회.
 * nextBusinessDay / previousBusinessDay는 시드 범위(2027-12-31)를 벗어나면
 * RuntimeException — 운영 가이드: 매년 1분기에 다음 연도 시드 추가 필요.
 */
@Component
class MarketCalendarJpaAdapter(
    private val repo: MarketCalendarJpaRepository,
) : MarketCalendarRepository {
    override fun isBusinessDay(date: LocalDate): Boolean = repo.findById(date).map { it.isBusinessDay }.orElse(computeFallback(date))

    override fun getHolidayReason(date: LocalDate): String? {
        val entity = repo.findById(date).orElse(null) ?: return computeHolidayReasonFallback(date)
        return if (!entity.isBusinessDay) entity.holidayReason else null
    }

    override fun nextBusinessDay(after: LocalDate): LocalDate =
        repo.findNextBusinessDay(after)?.calendarDate
            ?: error("[MarketCalendar] nextBusinessDay: 시드 범위 초과 after=$after — V10 시드 갱신 필요")

    override fun previousBusinessDay(before: LocalDate): LocalDate =
        repo.findPreviousBusinessDay(before)?.calendarDate
            ?: error("[MarketCalendar] previousBusinessDay: 시드 범위 초과 before=$before — V10 시드 갱신 필요")

    /**
     * DB에 날짜 없을 때(시드 범위 밖) 폴백 — 주말이면 false, 평일이면 true.
     * 2028년 이후 등 미래 날짜용 안전망.
     */
    private fun computeFallback(date: LocalDate): Boolean {
        val dow = date.dayOfWeek.value // 1=Mon, 7=Sun
        return dow !in listOf(6, 7)
    }

    private fun computeHolidayReasonFallback(date: LocalDate): String? {
        val dow = date.dayOfWeek.value
        return if (dow in listOf(6, 7)) "WEEKEND" else null
    }
}
