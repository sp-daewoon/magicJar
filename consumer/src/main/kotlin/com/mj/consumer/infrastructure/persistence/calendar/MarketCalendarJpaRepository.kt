package com.mj.consumer.infrastructure.persistence.calendar

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MarketCalendarJpaRepository : JpaRepository<MarketCalendarJpa, LocalDate> {
    @Query(
        """
        SELECT e FROM MarketCalendarJpa e
        WHERE e.calendarDate > :after AND e.isBusinessDay = true
        ORDER BY e.calendarDate ASC
        LIMIT 1
    """,
    )
    fun findNextBusinessDay(
        @Param("after") after: LocalDate,
    ): MarketCalendarJpa?

    @Query(
        """
        SELECT e FROM MarketCalendarJpa e
        WHERE e.calendarDate < :before AND e.isBusinessDay = true
        ORDER BY e.calendarDate DESC
        LIMIT 1
    """,
    )
    fun findPreviousBusinessDay(
        @Param("before") before: LocalDate,
    ): MarketCalendarJpa?
}
