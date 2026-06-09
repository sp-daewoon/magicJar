package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * DailyFamilyRollupRunner — 1d → 1wk/1mo/1y 롤업 + upsert 단위테스트.
 *
 * 검증:
 *   - 1d 0건 → emptyMap (롤업 skip, upsert 0).
 *   - 1d 전량 → 1wk·1mo·1y 각각 upsertAll(DO UPDATE) 호출 (KIS 콜 0).
 *   - upsert 봉 OHLCV — DailyRollupAggregator 위임 (open=첫날 close=마지막날 vol=sum).
 */
class DailyFamilyRollupRunnerTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val code = "005930"

    private fun dayTs(
        y: Int,
        mo: Int,
        d: Int,
    ): KtInstant = KtInstant.fromEpochSeconds(LocalDateTime.of(y, mo, d, 0, 0).atZone(zone).toEpochSecond())

    private fun oneDay(
        ts: KtInstant,
        o: Long,
        h: Long,
        l: Long,
        c: Long,
        v: Long,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = ts,
            openRaw = BigDecimal(o),
            highRaw = BigDecimal(h),
            lowRaw = BigDecimal(l),
            closeRaw = BigDecimal(c),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = v,
            source = "KIS",
        )

    @Test
    fun `1d 0건이면 emptyMap — upsert 0`() {
        val repo = mockk<CandleRepository>(relaxed = true)
        every { repo.findByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns emptyList()
        val runner = DailyFamilyRollupRunner(repo)

        val result = runner.rollupDetailed(code)

        assertThat(result).isEmpty()
        verify(exactly = 0) { repo.upsertAll(any()) }
    }

    @Test
    fun `1d 전량 → 1wk·1mo·1y upsert (KIS 콜 0) + OHLCV 위임 정합`() {
        val oneDays =
            listOf(
                oneDay(dayTs(2026, 6, 8), o = 293000, h = 315500, l = 292500, c = 295500, v = 38_929_682),
                oneDay(dayTs(2026, 6, 9), o = 310000, h = 324000, l = 300000, c = 322000, v = 30_124_249),
            )
        val repo = mockk<CandleRepository>()
        every { repo.findByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns oneDays
        val upsertSlot = mutableListOf<List<Candle>>()
        every { repo.upsertAll(capture(upsertSlot)) } answers { firstArg<List<Candle>>().size }
        val runner = DailyFamilyRollupRunner(repo)

        val result = runner.rollupDetailed(code)

        // 1wk·1mo·1y 3종 모두 1봉씩 (같은 주·달·해).
        assertThat(result.keys).containsExactlyInAnyOrder("1wk", "1mo", "1y")
        assertThat(result["1wk"]!!.upserted).isEqualTo(1)
        // KIS 콜 0 — findByCodeAndInterval(1d) 만 읽고 upsert. fetch 어댑터 의존 0.
        verify(exactly = 3) { repo.upsertAll(any()) }

        // 주봉 봉 — open=첫날(293000) close=마지막날(322000) high=max low=min vol=sum.
        val weekly = upsertSlot.first { it.first().interval == CandleInterval.ONE_WEEK }.first()
        assertThat(weekly.openRaw).isEqualByComparingTo(BigDecimal(293000))
        assertThat(weekly.closeRaw).isEqualByComparingTo(BigDecimal(322000))
        assertThat(weekly.highRaw).isEqualByComparingTo(BigDecimal(324000))
        assertThat(weekly.lowRaw).isEqualByComparingTo(BigDecimal(292500))
        assertThat(weekly.volume).isEqualTo(69_053_931L)
        // 주봉 ts = 첫 거래일(06-08), 월/연봉 ts = 마지막 거래일(06-09) — KIS 관례.
        assertThat(weekly.ts).isEqualTo(dayTs(2026, 6, 8))
        val monthly = upsertSlot.first { it.first().interval == CandleInterval.ONE_MONTH }.first()
        assertThat(monthly.ts).isEqualTo(dayTs(2026, 6, 9))
    }
}
