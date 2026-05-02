package com.mj.api.infrastructure.persistence.calendar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * market_calendar 테이블 JPA 엔티티. V10 Flyway 시드 대응.
 * spec §9.1, ADR 0032.
 */
@Entity
@Table(name = "market_calendar")
class MarketCalendarEntity(
    @Id
    @Column(name = "calendar_date", nullable = false)
    val calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "is_business_day", nullable = false)
    val isBusinessDay: Boolean = false,
    @Column(name = "holiday_name", length = 64)
    val holidayName: String? = null,
    @Column(name = "holiday_reason", length = 32)
    val holidayReason: String? = null,
    @Column(name = "source", length = 16, nullable = false)
    val source: String = "STATIC_SEED",
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
