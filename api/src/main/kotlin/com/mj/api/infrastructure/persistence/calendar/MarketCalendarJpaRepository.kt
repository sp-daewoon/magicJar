package com.mj.api.infrastructure.persistence.calendar

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MarketCalendarJpaRepository : JpaRepository<MarketCalendarEntity, LocalDate> {
    @Query(
        """
        SELECT e FROM MarketCalendarEntity e
        WHERE e.calendarDate > :after AND e.isBusinessDay = true
        ORDER BY e.calendarDate ASC
        LIMIT 1
    """,
    )
    fun findNextBusinessDay(
        @Param("after") after: LocalDate,
    ): MarketCalendarEntity?

    @Query(
        """
        SELECT e FROM MarketCalendarEntity e
        WHERE e.calendarDate < :before AND e.isBusinessDay = true
        ORDER BY e.calendarDate DESC
        LIMIT 1
    """,
    )
    fun findPreviousBusinessDay(
        @Param("before") before: LocalDate,
    ): MarketCalendarEntity?
}
