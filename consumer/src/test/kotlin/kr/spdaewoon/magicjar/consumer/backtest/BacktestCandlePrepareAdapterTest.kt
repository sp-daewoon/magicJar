package kr.spdaewoon.magicjar.consumer.backtest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.config.BacktestIntradayProperties
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMinuteBackfillRangePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

class BacktestCandlePrepareAdapterTest {
    private val candleRepository = mockk<CandleRepository>(relaxed = true)
    private val kisBackfillPort = mockk<KisMinuteBackfillRangePort>()
    private val aggregateBackfillRunner = mockk<AggregateBackfillRunner>(relaxed = true)
    private val checker = CandleIntegrityChecker(candleRepository)

    private fun adapter(props: BacktestIntradayProperties = BacktestIntradayProperties()): BacktestCandlePrepareAdapter =
        BacktestCandlePrepareAdapter(
            candleRepository = candleRepository,
            kisMinuteBackfillRangePort = kisBackfillPort,
            aggregateBackfillRunner = aggregateBackfillRunner,
            candleIntegrityChecker = checker,
            backtestIntradayProperties = props,
        )

    // 2026-06-22(월)~06-26(금) = 5 거래일.
    private val from = LocalDate(2026, 6, 22)
    private val to = LocalDate(2026, 6, 26)

    @Test
    fun `backfillEnabled true면 BACKFILL port로 fetch 후 저장하고 집계봉을 재생성한다`() {
        val fetched = listOf(candle("005930", "2026-06-22T00:01:00Z"))
        val savedSlot = slot<List<Candle>>()
        every { kisBackfillPort.fetchOneMinuteCandlesForBackfill("005930", any(), any()) } returns fetched
        every { candleRepository.saveAll(capture(savedSlot)) } returns 1
        every { aggregateBackfillRunner.backfill("005930") } returns mapOf("3m" to 1)
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        val reports = adapter().prepareAndReport(listOf("005930"), "1m", from, to, backfillEnabled = true)

        // BACKFILL 버킷 전용 port 호출 — USER_PATH range port 가 아님(라이브 쿼터 침범 0).
        verify(exactly = 1) { kisBackfillPort.fetchOneMinuteCandlesForBackfill("005930", any(), any()) }
        verify(exactly = 1) { aggregateBackfillRunner.backfill("005930") }
        assertThat(savedSlot.captured.first().source).isEqualTo("KIS-BACKTEST-BACKFILL")
        assertThat(reports["005930"]!!.confidence).isEqualTo(CandleConfidence.OK)
    }

    @Test
    fun `backfillEnabled false면 KIS fetch 0 — DB 기존 봉만으로 정합성 평가`() {
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(1950)

        val reports = adapter().prepareAndReport(listOf("005930"), "1m", from, to, backfillEnabled = false)

        verify(exactly = 0) { kisBackfillPort.fetchOneMinuteCandlesForBackfill(any(), any(), any()) }
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        assertThat(reports).containsKey("005930")
    }

    @Test
    fun `요청 from이 max-lookback-days보다 과거면 clamp + lookbackClamped 경고`() {
        // max-lookback-days=30 → to(06-26) 기준 boundFrom=05-28. 요청 from=2026-01-01 은 훨씬 과거 → clamp.
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(0)

        val reports =
            adapter(BacktestIntradayProperties(maxLookbackDays = 30))
                .prepareAndReport(listOf("005930"), "1m", LocalDate(2026, 1, 1), to, backfillEnabled = false)

        assertThat(reports["005930"]!!.lookbackClamped).isTrue()
    }

    @Test
    fun `clamp 상한은 KIS 1년 bound — ENV가 더 커도 365일을 넘기지 않는다`() {
        val props = BacktestIntradayProperties(maxLookbackDays = 9999)
        assertThat(props.normalizedMaxLookbackDays()).isEqualTo(BacktestIntradayProperties.KIS_HARD_BOUND_CALENDAR_DAYS)
    }

    @Test
    fun `종목 backfill 실패는 fail-soft — 다른 종목 계속 + 실패 종목은 기존 봉으로 등급화`() {
        every { kisBackfillPort.fetchOneMinuteCandlesForBackfill("000660", any(), any()) } throws RuntimeException("EGW00201")
        every { kisBackfillPort.fetchOneMinuteCandlesForBackfill("005930", any(), any()) } returns
            listOf(candle("005930", "2026-06-22T00:01:00Z"))
        every { candleRepository.saveAll(any()) } returns 1
        every { aggregateBackfillRunner.backfill("005930") } returns mapOf("3m" to 1)
        // 000660 은 backfill 실패 후 기존 봉 적음 → INSUFFICIENT, 005930 은 충분 → OK.
        every { candleRepository.findAllTsByCodeAndIntervalBetween("000660", any(), any(), any()) } returns ts(10)
        every { candleRepository.findAllTsByCodeAndIntervalBetween("005930", any(), any(), any()) } returns ts(1950)

        val reports =
            adapter().prepareAndReport(listOf("000660", "005930"), "1m", from, to, backfillEnabled = true)

        // 실패 종목도 보고에 포함(등급으로 노출), 다른 종목 계속.
        assertThat(reports["000660"]!!.confidence).isEqualTo(CandleConfidence.INSUFFICIENT_DATA)
        assertThat(reports["005930"]!!.confidence).isEqualTo(CandleConfidence.OK)
        verify(exactly = 1) { kisBackfillPort.fetchOneMinuteCandlesForBackfill("005930", any(), any()) }
    }

    @Test
    fun `KIS 0건(uncovered)이면 save·aggregate 0 — mock 보간 0`() {
        every { kisBackfillPort.fetchOneMinuteCandlesForBackfill("005930", any(), any()) } returns emptyList()
        every { candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any()) } returns ts(0)

        adapter().prepareAndReport(listOf("005930"), "1m", from, to, backfillEnabled = true)

        // 결측을 메우지 않음 — saveAll·aggregate 호출 0(보간 0 단언).
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        verify(exactly = 0) { aggregateBackfillRunner.backfill(any()) }
    }

    private fun ts(count: Int): List<Instant> {
        val base = Instant.parse("2026-06-22T00:00:00Z")
        return (0 until count).map { base.plusSeconds(it * 60L) }
    }

    private fun candle(
        code: String,
        tsStr: String,
    ): Candle {
        val price = BigDecimal("100")
        return Candle(
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = KtInstant.parse(tsStr),
            openRaw = price,
            highRaw = price,
            lowRaw = price,
            closeRaw = price,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "KIS",
        )
    }
}
