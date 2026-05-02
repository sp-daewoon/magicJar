package com.mj.domain.market.session.port

import java.time.LocalDate

/**
 * KRX 영업일·휴장일 캘린더 조회 포트. spec §4.5, ADR 0032.
 *
 * 구현체 위치:
 * - api 모듈: MarketCalendarJpaAdapter (V10 Flyway 시드 + JPA)
 * - consumer 모듈: 동일 Bean 또는 경량 in-memory (mock profile)
 */
interface MarketCalendarRepository {
    /** 영업일 여부. */
    fun isBusinessDay(date: LocalDate): Boolean

    /** 공휴일/휴장 명칭. 영업일이면 null. */
    fun getHolidayReason(date: LocalDate): String?

    /** 주어진 날짜 이후 가장 가까운 다음 영업일. */
    fun nextBusinessDay(after: LocalDate): LocalDate

    /** 주어진 날짜 이전 가장 가까운 직전 영업일. */
    fun previousBusinessDay(before: LocalDate): LocalDate
}
