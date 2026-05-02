package com.mj.consumer.application.session

import com.mj.consumer.infrastructure.persistence.calendar.InMemoryMarketCalendarAdapter
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketCalendarRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketSessionResolverImpl + InMemoryMarketCalendarAdapter 조합 IT.
 * spec §4.1, ADR 0032.
 *
 * InMemory calendar(주말만 구분) 사용 → DB 의존 없이 5 상태 전이 검증.
 * 공휴일 케이스는 MockK calendar(별도 단위테스트) + V10 IT로 별도 검증.
 *
 * 검증:
 *   1. 08:30 영업일 평일 → PreMarket
 *   2. 09:00 영업일 → Open
 *   3. 15:30 영업일 → PostMarket
 *   4. 18:00 영업일 → Closed(off-hours)
 *   5. 토요일 14:30 → Closed(Saturday)
 *   6. 일요일 10:00 → Closed(Sunday)
 *   7. lastBusinessDate — 토요일에 조회 시 직전 금요일
 *   8. nextOpenAt — PreMarket 시점에 오늘 09:00, Closed 시점에 다음 영업일 09:00
 *
 * Note: 공휴일 검증은 MarketSessionResolverImplTest(MockK calendar)에서 커버.
 */
class MarketSessionResolverImplIT {
    private val kst = ZoneId.of("Asia/Seoul")

    /** InMemory calendar: 주말=비영업일, 평일=영업일 */
    private val calendar: MarketCalendarRepository = InMemoryMarketCalendarAdapter()

    private fun clockAt(
        kstDate: String,
        kstTime: String,
    ): Clock {
        val utcInstant =
            java.time.OffsetDateTime
                .parse("${kstDate}T$kstTime+09:00")
                .toInstant()
        return Clock.fixed(utcInstant, ZoneId.of("UTC"))
    }

    private fun resolve(
        kstDate: String,
        kstTime: String,
    ): MarketSession = MarketSessionResolverImpl(calendar, clockAt(kstDate, kstTime)).resolve()

    // ─── 1. 08:30 평일 → PreMarket ───

    @Test
    fun `상태전이1 - 08시30분 영업일 평일 → PreMarket`() {
        val s = resolve("2026-05-04", "08:30:00") // 월요일
        s.state shouldBe MarketSessionState.PreMarket
    }

    // ─── 2. 09:00 정규장 → Open ───

    @Test
    fun `상태전이2 - 09시00분 영업일 → Open`() {
        val s = resolve("2026-05-04", "09:00:00")
        s.state shouldBe MarketSessionState.Open
        s.nextOpenAt shouldBe null // Open이면 nextOpenAt=null
    }

    @Test
    fun `상태전이2b - 12시00분 정규장 한복판 → Open`() {
        val s = resolve("2026-05-04", "12:00:00")
        s.state shouldBe MarketSessionState.Open
    }

    // ─── 3. 15:30 장후 → PostMarket ───

    @Test
    fun `상태전이3 - 15시30분 영업일 → PostMarket`() {
        val s = resolve("2026-05-04", "15:30:00")
        s.state shouldBe MarketSessionState.PostMarket
    }

    @Test
    fun `상태전이3b - 17시00분 장후 → PostMarket`() {
        val s = resolve("2026-05-04", "17:00:00")
        s.state shouldBe MarketSessionState.PostMarket
    }

    // ─── 4. 18:00 이후 → Closed(off-hours) ───

    @Test
    fun `상태전이4 - 18시00분 이후 영업일 → Closed off-hours`() {
        val s = resolve("2026-05-04", "18:00:00")
        s.state.shouldBeInstanceOf<MarketSessionState.Closed>()
        (s.state as MarketSessionState.Closed).reason shouldBe "off-hours"
    }

    @Test
    fun `상태전이4b - 07시00분 이른 아침 영업일 → Closed off-hours`() {
        val s = resolve("2026-05-04", "07:00:00")
        s.state.shouldBeInstanceOf<MarketSessionState.Closed>()
        (s.state as MarketSessionState.Closed).reason shouldBe "off-hours"
    }

    // ─── 5. 토요일 → Closed(Saturday) ───

    @Test
    fun `상태전이5 - 토요일 14시30분 → Closed Saturday`() {
        val s = resolve("2026-05-02", "14:30:00") // 토요일
        s.state.shouldBeInstanceOf<MarketSessionState.Closed>()
        (s.state as MarketSessionState.Closed).reason shouldBe "Saturday"
    }

    // ─── 6. 일요일 → Closed(Sunday) ───

    @Test
    fun `상태전이6 - 일요일 10시 → Closed Sunday`() {
        val s = resolve("2026-05-03", "10:00:00") // 일요일
        s.state.shouldBeInstanceOf<MarketSessionState.Closed>()
        (s.state as MarketSessionState.Closed).reason shouldBe "Sunday"
    }

    // ─── 7. lastBusinessDate — 토요일 조회 시 직전 금요일 ───

    @Test
    fun `lastBusinessDate - 토요일 조회 시 직전 금요일`() {
        val s = resolve("2026-05-02", "14:30:00") // 토요일
        // InMemory calendar: previousBusinessDay(토요일) = 금요일
        s.lastBusinessDate shouldBe LocalDate.of(2026, 5, 1)
    }

    @Test
    fun `lastBusinessDate - 영업일 조회 시 오늘`() {
        val s = resolve("2026-05-04", "10:00:00") // 월요일
        s.lastBusinessDate shouldBe LocalDate.of(2026, 5, 4)
    }

    // ─── 8. nextOpenAt ───

    @Test
    fun `nextOpenAt - PreMarket 시점에 오늘 09시00분`() {
        val s = resolve("2026-05-04", "08:30:00")
        s.nextOpenAt shouldNotBe null
        // 오늘 09:00 KST
        s.nextOpenAt!!.hour shouldBe 9
        s.nextOpenAt!!.minute shouldBe 0
        s.nextOpenAt!!.toLocalDate() shouldBe LocalDate.of(2026, 5, 4)
    }

    @Test
    fun `nextOpenAt - Closed off-hours 시점에 다음 영업일 09시`() {
        val s = resolve("2026-05-04", "20:00:00") // 월요일 오후 8시
        s.nextOpenAt shouldNotBe null
        s.nextOpenAt!!.toLocalDate() shouldBe LocalDate.of(2026, 5, 5) // 화요일
        s.nextOpenAt!!.hour shouldBe 9
    }

    @Test
    fun `nextOpenAt - 토요일 오후에 다음 월요일 09시`() {
        val s = resolve("2026-05-02", "14:30:00") // 토요일
        s.nextOpenAt shouldNotBe null
        // 토요일 다음 영업일: 일(건너뜀) → 월 2026-05-04
        s.nextOpenAt!!.toLocalDate() shouldBe LocalDate.of(2026, 5, 4)
    }
}
