package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMinuteBackfillRangePort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

class MinutePoolCollectionServiceTest {
    private val rangePort = mockk<KisMinuteBackfillRangePort>()
    private val candleRepository = mockk<CandleRepository>()
    private val aggregateBackfillRunner = mockk<AggregateBackfillRunner>()

    private fun service(): MinutePoolCollectionService =
        MinutePoolCollectionService(
            kisMinuteBackfillRangePort = rangePort,
            candleRepository = candleRepository,
            aggregateBackfillRunner = aggregateBackfillRunner,
        )

    private fun candle(
        code: String,
        ts: String,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = KtInstant.parse(ts),
            openRaw = BigDecimal.TEN,
            highRaw = BigDecimal.TEN,
            lowRaw = BigDecimal.TEN,
            closeRaw = BigDecimal.TEN,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 100L,
            source = "KIS",
        )

    @Test
    fun `BACKFILL 버킷으로 range fetch 후 source 재태깅 저장 + 삽입 시 합산봉 롤업`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:05:00Z")
        val fetched = listOf(candle("005930", "2026-06-01T00:01:00Z"), candle("005930", "2026-06-01T00:02:00Z"))
        val savedSlot = slot<List<Candle>>()

        every { rangePort.fetchOneMinuteCandlesForBackfill("005930", from, to) } returns fetched
        every { candleRepository.saveAll(capture(savedSlot)) } returns 2
        every { aggregateBackfillRunner.backfill("005930") } returns mapOf("3m" to 1, "5m" to 1)

        val outcome = service().collect("005930", from, to)

        assertThat(outcome.fetched).isEqualTo(2)
        assertThat(outcome.inserted).isEqualTo(2)
        assertThat(outcome.rollupUpserted).isEqualTo(2)
        assertThat(savedSlot.captured).allMatch { it.source == MinutePoolCollectionService.SOURCE }
        verify(exactly = 1) { aggregateBackfillRunner.backfill("005930") }
    }

    @Test
    fun `fetch 0건이면 저장·롤업을 하지 않는다`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:05:00Z")
        every { rangePort.fetchOneMinuteCandlesForBackfill("005930", from, to) } returns emptyList()

        val outcome = service().collect("005930", from, to)

        assertThat(outcome.fetched).isZero()
        assertThat(outcome.inserted).isZero()
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        verify(exactly = 0) { aggregateBackfillRunner.backfill(any()) }
    }

    @Test
    fun `삽입 0건이면 롤업을 하지 않는다 (전건 중복)`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:05:00Z")
        every { rangePort.fetchOneMinuteCandlesForBackfill("005930", from, to) } returns
            listOf(candle("005930", "2026-06-01T00:01:00Z"))
        every { candleRepository.saveAll(any()) } returns 0

        val outcome = service().collect("005930", from, to)

        assertThat(outcome.inserted).isZero()
        assertThat(outcome.rollupUpserted).isZero()
        verify(exactly = 0) { aggregateBackfillRunner.backfill(any()) }
    }

    @Test
    fun `KIS 예외는 fail-closed 로 전파한다`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:05:00Z")
        every { rangePort.fetchOneMinuteCandlesForBackfill("005930", from, to) } throws
            IllegalStateException("KIS rate limit")

        assertThatThrownBy { service().collect("005930", from, to) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `from 이 to 보다 늦으면 KIS 콜 없이 빈 결과`() {
        val from = Instant.parse("2026-06-01T00:05:00Z")
        val to = Instant.parse("2026-06-01T00:00:00Z")

        val outcome = service().collect("005930", from, to)

        assertThat(outcome.fetched).isZero()
        verify(exactly = 0) { rangePort.fetchOneMinuteCandlesForBackfill(any(), any(), any()) }
    }
}
