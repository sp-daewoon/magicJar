package com.mj.consumer.infrastructure.persistence.calendar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * consumer 모듈 market_calendar 엔티티 (읽기 전용). V10 Flyway 시드 대응.
 * api 모듈과 동일 테이블 공유 — 각 모듈이 자체 엔티티·레포를 보유하는 MSA 정석(ADR 0020 영속성 정책 B).
 */
@Entity
@Table(name = "market_calendar")
class MarketCalendarJpa(
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
