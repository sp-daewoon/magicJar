package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.candle.CandleSource
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * AggregateBackfillRunner — 1m → 3m~12h 합산 + upsert 단위테스트.
 *
 * 검증:
 *   - 1m 0건 → emptyMap (합산 skip, upsert 0).
 *   - P0-A Phase A-3: precedence flag off(default) → 기존 무가드 upsertAll(zero-regression).
 *   - P0-A Phase A-3: precedence flag on → upsertAllWithPrecedence(REST_CONFIRMED) 경유(합산봉 재생성 REST 등급).
 */
class AggregateBackfillRunnerTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val code = "005930"

    private fun minTs(
        h: Int,
        m: Int,
    ): KtInstant = KtInstant.fromEpochSeconds(LocalDateTime.of(2026, 6, 9, h, m).atZone(zone).toEpochSecond())

    private fun oneMin(
        ts: KtInstant,
        o: Long,
        h: Long,
        l: Long,
        c: Long,
        v: Long,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_MIN,
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

    private fun sampleMinutes(): List<Candle> =
        (0 until 15).map { i ->
            oneMin(minTs(9, i), o = 1000 + i.toLong(), h = 1100, l = 950, c = 1050, v = 100)
        }

    @Test
    fun `1m 0건이면 emptyMap — upsert 0`() {
        val repo = mockk<CandleRepository>(relaxed = true)
        every { repo.findByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns emptyList()
        val runner = AggregateBackfillRunner(repo)

        val result = runner.backfillDetailed(code)

        assertThat(result).isEmpty()
        verify(exactly = 0) { repo.upsertAll(any()) }
        verify(exactly = 0) { repo.upsertAllWithPrecedence(any(), any()) }
    }

    @Test
    fun `precedence flag off(default) — 기존 무가드 upsertAll 경유(zero-regression)`() {
        val repo = mockk<CandleRepository>()
        every { repo.findByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns sampleMinutes()
        every { repo.upsertAll(any()) } answers { firstArg<List<Candle>>().size }
        val runner = AggregateBackfillRunner(repo)

        runner.backfillDetailed(code)

        // 8개 target interval 모두 무가드 upsertAll 경유(합산 결과 있는 것만) — precedence 미호출.
        verify(atLeast = 1) { repo.upsertAll(any()) }
        verify(exactly = 0) { repo.upsertAllWithPrecedence(any(), any()) }
    }

    @Test
    fun `precedence flag on — upsertAllWithPrecedence(REST_CONFIRMED) 경유, 무가드 upsertAll 미호출`() {
        val repo = mockk<CandleRepository>()
        every { repo.findByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns sampleMinutes()
        every {
            repo.upsertAllWithPrecedence(any(), CandleSource.REST_CONFIRMED)
        } answers { firstArg<List<Candle>>().size }
        // flag on — 합산봉 재생성은 1m 기반 재생 REST 확정 등급.
        val runner = AggregateBackfillRunner(repo, precedenceGuardEnabled = true)

        runner.backfillDetailed(code)

        verify(atLeast = 1) { repo.upsertAllWithPrecedence(any(), CandleSource.REST_CONFIRMED) }
        verify(exactly = 0) { repo.upsertAll(any()) }
    }
}
