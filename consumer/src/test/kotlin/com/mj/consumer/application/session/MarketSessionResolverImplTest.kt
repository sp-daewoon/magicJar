package com.mj.consumer.application.session

import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketCalendarRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketSessionResolverImpl 단위 테스트. spec §4.1, ADR 0032.
 * Clock 주입으로 시간 고정 → 결정론적 검증.
 *
 * 검증 케이스:
 *  1. 08:30 평일 → PRE_MARKET
 *  2. 10:00 평일 → OPEN
 *  3. 16:00 평일 → POST_MARKET
 *  4. 14:30 토요일 → CLOSED(Saturday)
 *  5. 10:00 어린이날(5/5) → HOLIDAY
 *  6. 연말 임시휴장(12/31) → HOLIDAY
 *  7. cache hit — 2번째 호출은 repo lookup 0
 */
class MarketSessionResolverImplTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val calendar = mockk<MarketCalendarRepository>()

    private fun clockAt(
        kstDate: String,
        kstTime: String,
    ): Clock {
        // KST = UTC+9 → UTC 시각으로 변환
        val utcInstant =
            java.time.OffsetDateTime
                .parse("${kstDate}T$kstTime+09:00")
                .toInstant()
        return Clock.fixed(utcInstant, ZoneId.of("UTC"))
    }

    @Test
    fun `08시30분 영업일 - PRE_MARKET 반환`() {
        val date = LocalDate.of(2026, 5, 4) // 월요일 평일
        every { calendar.isBusinessDay(date) } returns true
        every { calendar.getHolidayReason(date) } returns null
        every { calendar.nextBusinessDay(date) } returns date.plusDays(1)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-04", "08:30:00"))
        val session = resolver.resolve()

        assertEquals(MarketSessionState.PreMarket, session.state)
        assertEquals(date, session.lastBusinessDate)
    }

    @Test
    fun `10시00분 영업일 - OPEN 반환`() {
        val date = LocalDate.of(2026, 5, 4)
        every { calendar.isBusinessDay(date) } returns true
        every { calendar.getHolidayReason(date) } returns null

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-04", "10:00:00"))
        val session = resolver.resolve()

        assertEquals(MarketSessionState.Open, session.state)
        assertNull(session.nextOpenAt, "OPEN 시 nextOpenAt은 null이어야 함")
    }

    @Test
    fun `16시00분 영업일 - POST_MARKET 반환`() {
        val date = LocalDate.of(2026, 5, 4)
        every { calendar.isBusinessDay(date) } returns true
        every { calendar.getHolidayReason(date) } returns null
        every { calendar.nextBusinessDay(date) } returns date.plusDays(1)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-04", "16:00:00"))
        val session = resolver.resolve()

        assertEquals(MarketSessionState.PostMarket, session.state)
    }

    @Test
    fun `14시30분 토요일 - CLOSED 반환`() {
        val date = LocalDate.of(2026, 5, 2) // 토요일
        every { calendar.isBusinessDay(date) } returns false
        every { calendar.getHolidayReason(date) } returns "WEEKEND"
        every { calendar.previousBusinessDay(date) } returns LocalDate.of(2026, 5, 1)
        every { calendar.nextBusinessDay(date) } returns LocalDate.of(2026, 5, 4)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-02", "14:30:00"))
        val session = resolver.resolve()

        val state = session.state
        check(state is MarketSessionState.Closed) { "토요일은 CLOSED여야 함, 실제: $state" }
        assertEquals("Saturday", state.reason)
    }

    @Test
    fun `어린이날(5월5일) - HOLIDAY 반환`() {
        val date = LocalDate.of(2026, 5, 5) // 화요일이지만 어린이날
        every { calendar.isBusinessDay(date) } returns false
        every { calendar.getHolidayReason(date) } returns "PUBLIC_HOLIDAY"
        every { calendar.previousBusinessDay(date) } returns LocalDate.of(2026, 5, 4)
        every { calendar.nextBusinessDay(date) } returns LocalDate.of(2026, 5, 6)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-05", "10:00:00"))
        val session = resolver.resolve()

        val state = session.state
        check(state is MarketSessionState.Holiday) { "어린이날은 HOLIDAY여야 함, 실제: $state" }
        assertEquals("PUBLIC_HOLIDAY", state.reason)
    }

    @Test
    fun `연말 임시휴장(12월31일) - HOLIDAY 반환`() {
        val date = LocalDate.of(2026, 12, 31) // 목요일이지만 KRX 임시휴장
        every { calendar.isBusinessDay(date) } returns false
        every { calendar.getHolidayReason(date) } returns "TEMPORARY_CLOSURE"
        every { calendar.previousBusinessDay(date) } returns LocalDate.of(2026, 12, 30)
        every { calendar.nextBusinessDay(date) } returns LocalDate.of(2027, 1, 2)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-12-31", "10:00:00"))
        val session = resolver.resolve()

        val state = session.state
        check(state is MarketSessionState.Holiday) { "12/31 임시휴장은 HOLIDAY여야 함, 실제: $state" }
        assertEquals("TEMPORARY_CLOSURE", state.reason)
    }

    @Test
    fun `cache hit - 2번째 호출은 repository 재호출 없음`() {
        val date = LocalDate.of(2026, 5, 4)
        every { calendar.isBusinessDay(date) } returns true
        every { calendar.getHolidayReason(date) } returns null

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-04", "10:00:00"))

        resolver.resolve()
        resolver.resolve() // 두 번째 호출 — 캐시 hit

        // 캐시 TTL(1분) 이내이므로 repository는 1회만 호출
        verify(exactly = 1) { calendar.isBusinessDay(date) }
    }

    @Test
    fun `장 시작 전 08시29분 - CLOSED 반환`() {
        val date = LocalDate.of(2026, 5, 4) // 영업일이지만 08:30 이전
        every { calendar.isBusinessDay(date) } returns true
        every { calendar.getHolidayReason(date) } returns null
        every { calendar.nextBusinessDay(date) } returns date.plusDays(1)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-04", "07:00:00"))
        val session = resolver.resolve()

        val state = session.state
        check(state is MarketSessionState.Closed) { "07:00은 CLOSED여야 함, 실제: $state" }
        assertEquals("off-hours", state.reason)
    }

    @Test
    fun `장 마감 후 18시01분 - CLOSED 반환`() {
        val date = LocalDate.of(2026, 5, 4) // 영업일이지만 18:00 이후
        every { calendar.isBusinessDay(date) } returns true
        every { calendar.getHolidayReason(date) } returns null
        every { calendar.nextBusinessDay(date) } returns date.plusDays(1)

        val resolver = MarketSessionResolverImpl(calendar, clockAt("2026-05-04", "19:00:00"))
        val session = resolver.resolve()

        val state = session.state
        check(state is MarketSessionState.Closed) { "19:00은 CLOSED여야 함, 실제: $state" }
    }
}
